package jp.haruyalab.novelshelf;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import jp.hiroshiba.voicevoxcore.AccentPhrase;
import jp.hiroshiba.voicevoxcore.AudioQuery;
import jp.hiroshiba.voicevoxcore.Mora;
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer;

/** Production rendering and PCM checks with actual JNI. Acting labels below are manual test data. */
public final class VoicevoxPerformanceTest {
    private static int checks;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++; System.out.println("PASS detailed " + name);
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String why) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (IOException expected) { failed = true; }
        check(failed, why);
    }

    static void run(VoicevoxEngine engine, File sampleDirectory) throws Exception {
        check(sampleDirectory.isDirectory() || sampleDirectory.mkdirs(), "sample directory available");
        String text = "えっ、本当に来てくれたの？……ありがとう。もう、大丈夫。";
        JSONObject unit = Json.obj("spokenText", text, "text", text, "emotion", "neutral", "rate", 1, "pitch", 1);
        JSONObject speaker = Json.obj("id", "test-role", "offlineVoice", "kotaro", "rate", 1, "pitch", 1);
        JSONArray boundaries = SpeechPerformance.parts(unit), directions = new JSONArray();
        check(boundaries.length() == 5, "deterministic natural boundaries for original sample");
        for (int i = 0; i < boundaries.length(); i++) {
            String emotion = i < 2 ? "surprise" : i == 2 ? "joy" : "relief";
            directions.put(Json.obj("partId", boundaries.optJSONObject(i).optString("partId"),
                    "emotion", emotion, "delivery", i < 2 ? "hesitant" : "soft", "intensity", .65,
                    "rate", i < 2 ? 1.06 : .94, "pitch", i < 2 ? 1.04 : 1.01,
                    "intonation", i < 2 ? 1.18 : 1.08, "volume", 1, "pauseAfterMs", i == 0 ? 90 : i == 1 ? 350 : i == 3 ? 0 : 250,
                    "ending", i < 2 ? "rise" : i == 3 ? "auto" : "fall", "emphasis", .25, "confidence", .8));
        }
        byte[] standard = engine.synthesizeUnit(text, "kotaro", unit, speaker, () -> false);
        byte[] standardPcm = VoicePcm.samples(standard);
        check(voicedSamples(standardPcm) > 1000, "standard sample is actual nonempty PCM speech");
        unit.put("performance", SpeechPerformance.validate(unit, directions, true));
        check(SpeechPerformance.valid(unit), "manual test directions validate against source");
        JSONArray groups = VoicevoxEngine.renderingParts(unit);
        check(groups.length() == 4, "adjacent equal directions with no acted boundary merge");
        StringBuilder covered = new StringBuilder();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject part = groups.optJSONObject(i);
            covered.append(text.substring(part.optInt("start"), part.optInt("end")));
        }
        check(covered.toString().equals(text), "renderer covers every original UTF-16 character once");
        check(OfflineVoices.styleId("kotaro", groups.optJSONObject(2)) == 32
                && OfflineVoices.styleId("kotaro", groups.optJSONObject(3)) == 12,
                "emotion change stays within one pinned character");
        byte[] detailed = engine.synthesizeUnit(text, "kotaro", unit, speaker, () -> false);
        byte[] detailPcm = VoicePcm.samples(detailed);
        check(voicedSamples(detailPcm) > 1000, "multiple directed parts synthesize actual speech");
        check(!Arrays.equals(standard, detailed), "direction changes produce different native PCM");
        check(zero(detailPcm, 0, 40 * 48), "initial explicit padding is present");
        check(zero(detailPcm, detailPcm.length - 250 * 48, 250 * 48), "requested final pause is present once");
        Files.write(new File(sampleDirectory, "standard-kotaro.wav").toPath(), standard);
        Files.write(new File(sampleDirectory, "directed-kotaro.wav").toPath(), detailed);
        Files.writeString(new File(sampleDirectory, "sample-notes.txt").toPath(),
                "Text: " + text + "\nVoice: VOICEVOX:白上虎太郎\n"
                + "standard-kotaro.wav: whole-utterance default controls.\n"
                + "directed-kotaro.wav: production 1.8 renderer, manually authored per-part annotations.\n"
                + "This is a renderer demonstration, not a GPT accuracy test or a voice-acting quality benchmark.\n");

        JSONObject longerPause = Json.copy(unit);
        longerPause.optJSONObject("performance").optJSONArray("parts").optJSONObject(0).put("pauseAfterMs", 390);
        byte[] paused = VoicePcm.samples(engine.synthesizeUnit(text, "kotaro", longerPause, speaker, () -> false));
        check(paused.length - detailPcm.length == 300 * 48, "changing one pause adds exactly 300 ms without rate scaling");
        JSONObject changedEnding = Json.copy(unit);
        changedEnding.optJSONObject("performance").optJSONArray("parts").optJSONObject(0).put("ending", "fall");
        byte[] fallen = engine.synthesizeUnit(text, "kotaro", changedEnding, speaker, () -> false);
        check(!Arrays.equals(detailed, fallen), "a changed ending reaches real native output");

        JSONObject invalid = Json.copy(unit);
        invalid.optJSONObject("performance").optJSONArray("parts").optJSONObject(1).put("start", 0);
        check(!SpeechPerformance.valid(invalid), "overlapping source ranges are rejected");
        byte[] fallback = engine.synthesizeUnit(text, "kotaro", invalid, speaker, () -> false);
        check(VoicePcm.samples(fallback).length == standardPcm.length, "invalid detailed data uses full-text coarse synthesis");
        AtomicInteger cancellationChecks = new AtomicInteger();
        boolean stopped = false;
        try { engine.synthesizeUnit(text, "kotaro", unit, speaker, () -> cancellationChecks.incrementAndGet() >= 9); }
        catch (InterruptedIOException expected) { stopped = true; }
        check(stopped && cancellationChecks.get() >= 9, "mid-sequence cancellation returns no partial WAV");
        rejects(() -> engine.synthesizeUnit(text + "別文", "kotaro", unit, speaker, () -> false),
                "directed offsets cannot be applied to a different supplied transcript");

        Field field = VoicevoxEngine.class.getDeclaredField("synthesizer"); field.setAccessible(true);
        Synthesizer synth = (Synthesizer) field.get(engine);
        punctuationControls(engine, speaker);
        endingControls(synth);
        assemblyControls();
        System.out.println("VoicevoxPerformanceTest: PASS (" + checks
                + " real JNI/rendering checks; manual annotations only; GPT accuracy and Android device lifecycle unmeasured)");
    }

    private static void punctuationControls(VoicevoxEngine engine, JSONObject speaker) throws Exception {
        JSONObject unit = Json.obj("spokenText", "「……答えて。」");
        JSONArray parts = SpeechPerformance.parts(unit), directions = new JSONArray();
        check(parts.length() == 2, "leading ellipsis remains an explicit source-owned part");
        for (int i = 0; i < parts.length(); i++) directions.put(Json.obj("partId", parts.optJSONObject(i).optString("partId"),
                "emotion", i == 0 ? "tension" : "neutral", "delivery", "natural", "intensity", .4,
                "rate", 1, "pitch", 1, "intonation", 1, "volume", 1,
                "pauseAfterMs", i == 0 ? 180 : 120, "ending", "auto", "emphasis", 0, "confidence", .8));
        unit.put("performance", SpeechPerformance.validate(unit, directions, true));
        byte[] samples = VoicePcm.samples(engine.synthesizeUnit(unit.optString("spokenText"), "kotaro", unit, speaker, () -> false));
        check(voicedSamples(samples) > 1000, "leading quote and ellipsis cannot prevent native synthesis of following words");
        check(zero(samples, 0, 220 * 48), "punctuation-only part contributes its pause after initial padding");
        check(VoicevoxEngine.punctuationOnly("「……」、。！？　\n")
                && !VoicevoxEngine.punctuationOnly("答え") && !VoicevoxEngine.punctuationOnly("123")
                && !VoicevoxEngine.punctuationOnly("😶") && !VoicevoxEngine.punctuationOnly("……😶"),
                "only Unicode punctuation and space may use the silence path; words numbers and emoji are preserved");
        JSONObject punctuation = Json.obj("spokenText", "「……」");
        JSONObject direction = Json.copy(directions.optJSONObject(0));
        punctuation.put("performance", SpeechPerformance.validate(punctuation, new JSONArray().put(direction), true));
        byte[] silent = VoicePcm.samples(engine.synthesizeUnit(punctuation.optString("spokenText"), "kotaro", punctuation, speaker, () -> false));
        check(silent.length == 220 * 48 && zero(silent, 0, silent.length), "punctuation-only unit produces bounded requested silence");
    }

    private static void endingControls(Synthesizer synth) throws Exception {
        for (String ending : new String[]{"rise", "fall", "hold", "cut"}) {
            AudioQuery query = synth.createAudioQuery("本当に大丈夫ですか？", 12);
            AccentPhrase last = query.accentPhrases.get(query.accentPhrases.size() - 1);
            int accent = last.accent;
            List<Double> pitches = new ArrayList<>(), lengths = new ArrayList<>();
            List<Double> consonants = new ArrayList<>();
            for (Mora mora : last.moras) {
                pitches.add(mora.pitch); lengths.add(mora.vowelLength); consonants.add(mora.consonantLength);
            }
            VoicevoxEngine.applyPerformance(query, Json.obj("ending", ending, "intensity", .8));
            check(last.accent == accent && last.moras.size() == pitches.size(), ending + " preserves accent index and mora count");
            check(!last.isInterrogative && last.pauseMora == null, ending + " avoids duplicate upspeak and boundary silence");
            boolean changed = false;
            for (int i = 0; i < last.moras.size(); i++) {
                Mora mora = last.moras.get(i);
                if (pitches.get(i) == 0) check(mora.pitch == 0, ending + " preserves unvoiced pitch zero");
                check(java.util.Objects.equals(mora.consonantLength, consonants.get(i)), ending + " preserves consonant length/null");
                changed |= mora.pitch != pitches.get(i) || mora.vowelLength != lengths.get(i);
            }
            check(changed, ending + " changes the requested final contour or duration");
            query.validate();
            check(true, ending + " remains a valid upstream AudioQuery");
            if ("fall".equals(ending)) {
                byte[] automatic = synth.synthesis(query, 12).interrogativeUpspeak(true).perform();
                byte[] disabled = synth.synthesis(query, 12).interrogativeUpspeak(false).perform();
                check(Arrays.equals(automatic, disabled), "cleared final interrogative flag prevents native upspeak from undoing explicit fall");
            }
        }
        // Guarantee the silent-vowel and no-consonant cases even if a future dictionary voices all of the sample.
        AudioQuery query = new AudioQuery(); AccentPhrase phrase = new AccentPhrase();
        Mora voiced = new Mora(); voiced.vowel = "a"; voiced.pitch = 5.7; voiced.vowelLength = .1;
        Mora unvoiced = new Mora(); unvoiced.vowel = "U"; unvoiced.pitch = 0; unvoiced.vowelLength = .1;
        phrase.moras.add(voiced); phrase.moras.add(unvoiced); phrase.accent = 1;
        query.accentPhrases = new ArrayList<>(); query.accentPhrases.add(phrase);
        VoicevoxEngine.applyPerformance(query, Json.obj("ending", "rise"));
        check(unvoiced.pitch == 0 && voiced.consonantLength == null && unvoiced.consonantLength == null,
                "explicit unvoiced and nullable-consonant fixture stays untouched");
    }

    private static void assemblyControls() throws Exception {
        byte[] wave = Arrays.copyOf(VoicePcm.header(960), 1004);
        for (int i = 44; i < wave.length; i += 2) { wave[i] = (byte) 232; wave[i + 1] = 3; }
        VoicePcm output = new VoicePcm(); output.appendWave(wave); output.silence(125); output.appendWave(wave);
        byte[] joined = VoicePcm.samples(output.wave());
        check(joined.length == 960 * 2 + 125 * 48, "PCM length is the sum of samples and one requested silence");
        check(zero(joined, 960, 125 * 48), "inter-part silence occupies exactly the requested PCM region");
        check(joined[0] == 0 && joined[1] == 0 && joined[joined.length - 2] == 0 && joined[joined.length - 1] == 0,
                "edge ramps remove hard waveform discontinuities");
        check((joined[200] & 255) == 232 && joined[201] == 3, "samples outside edge ramp remain unchanged");
        check(VoicePcm.samples(wave).length == 960, "native input wave is never modified by concatenation");
        rejects(() -> VoicePcm.samples(Arrays.copyOf(wave, wave.length - 2)), "truncated RIFF is rejected");
        byte[] stereo = wave.clone(); stereo[22] = 2;
        rejects(() -> VoicePcm.samples(stereo), "stereo cannot be concatenated as mono");
        byte[] wrongRate = wave.clone(); wrongRate[24] = 0;
        rejects(() -> VoicePcm.samples(wrongRate), "unexpected sample rate is rejected");
        byte[] odd = wave.clone(); odd[40] = (byte) 191; // 959 bytes, not a PCM16 sample boundary.
        rejects(() -> VoicePcm.samples(odd), "odd PCM byte count is rejected");
        rejects(() -> output.silence(1201), "unbounded pause is rejected");
    }

    private static int voicedSamples(byte[] pcm) {
        int count = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            if (Math.abs((int) sample) > 20) count++;
        }
        return count;
    }
    private static boolean zero(byte[] bytes, int at, int count) {
        if (at < 0 || at + count > bytes.length) return false;
        for (int i = at; i < at + count; i++) if (bytes[i] != 0) return false;
        return true;
    }
}
