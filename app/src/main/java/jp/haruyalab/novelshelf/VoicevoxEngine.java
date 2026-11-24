package jp.haruyalab.novelshelf;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import jp.hiroshiba.voicevoxcore.AccelerationMode;
import jp.hiroshiba.voicevoxcore.AccentPhrase;
import jp.hiroshiba.voicevoxcore.AudioQuery;
import jp.hiroshiba.voicevoxcore.Mora;
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime;
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk;
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer;
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile;

/** Real on-device VOICEVOX synthesis. No network, API key, or server is used here. */
public final class VoicevoxEngine implements AutoCloseable {
    public interface Cancellation { boolean cancelled(); }

    private static final int MAX_TEXT_LENGTH = 2000;
    // Only two ONNX models stay resident; loading the entire ten-voice collection can exhaust RAM.
    private static final int MAX_RESIDENT_MODELS = 2;
    private final Context context;
    private final OfflineVoicePacks packs;
    private final LinkedHashMap<String, UUID> loaded = new LinkedHashMap<>(4, .75f, true);
    private Synthesizer synthesizer;
    private boolean closed;

    public VoicevoxEngine(Context context) {
        this.context = context.getApplicationContext();
        this.packs = new OfflineVoicePacks(this.context);
    }

    /** Call on a worker thread. Native inference is serialized and cancelled results are discarded. */
    public synchronized byte[] synthesize(String text, int styleId, JSONObject parameters,
                                         Cancellation cancellation) throws IOException {
        return synthesizePart(text, styleId, parameters, null, cancellation);
    }

    /** Full coverage is checked before any part reaches native synthesis. Invalid plans use 1.7 controls. */
    public synchronized byte[] synthesizeUnit(String text, String voice, JSONObject unit, JSONObject speaker,
                                             Cancellation cancellation) throws IOException {
        return synthesizeUnit(text, voice, unit, speaker, cancellation, null);
    }

    /** Publishes exactly the PCM used by the final WAV, after each complete native chunk. */
    synchronized byte[] synthesizeUnit(String text, String voice, JSONObject unit, JSONObject speaker,
                                      Cancellation cancellation, VoicePcm.Sink sink) throws IOException {
        return synthesizeUnit(text, voice, unit, speaker, cancellation, sink, false);
    }

    synchronized byte[] synthesizeUnit(String text, String voice, JSONObject unit, JSONObject speaker,
                                      Cancellation cancellation, VoicePcm.Sink sink, boolean animeMode) throws IOException {
        return synthesizeUnit(text, voice, unit, speaker, cancellation, sink, animeMode ? DialogueMode.ANIME : DialogueMode.STANDARD);
    }

    synchronized byte[] synthesizeUnit(String text, String voice, JSONObject unit, JSONObject speaker,
                                      Cancellation cancellation, VoicePcm.Sink sink, DialogueMode requestedMode) throws IOException {
        checkCancelled(cancellation);
        if (closed) throw new IOException("VOICEVOX_UNAVAILABLE");
        if (text == null || text.trim().isEmpty()) throw new IOException("VOICEVOX_EMPTY_TEXT");
        if (text.length() > MAX_TEXT_LENGTH) throw new IOException("VOICEVOX_TEXT_TOO_LONG");
        boolean directed = SpeechPerformance.valid(unit), pronounced = SpeechPronunciation.valid(unit);
        if ((directed || pronounced) && !text.equals(SpeechPerformance.spokenText(unit)))
            throw new IOException("VOICEVOX_TEXT_MISMATCH");
        DialogueMode mode = DialogueActing.eligible(unit) ? requestedMode : DialogueMode.STANDARD;
        boolean plainConversation = mode == DialogueMode.NATURAL && "neutral".equals(unit.optString("emotion", "neutral"))
                && "natural".equals(unit.optString("delivery", "natural")) && "auto".equals(unit.optString("ending", "auto"))
                && unit.optInt("pauseAfterMs") == 0;
        if (!directed && (mode == DialogueMode.STANDARD || plainConversation)) {
            String spoken = pronounced ? SpeechReadingInput.text(unit) : text;
            JSONObject voiceControls = plainConversation ? DialogueActing.conversationControls(unit, unit.optBoolean("locked")) : unit;
            byte[] wave = synthesizePart(spoken, OfflineVoices.styleId(voice, unit),
                    OfflineVoices.parameters(voiceControls, speaker), null, cancellation, pronounced ? unit : null, 0, text.length(), null, mode);
            if (sink != null) { checkCancelled(cancellation); sink.append(VoicePcm.samples(wave), true); }
            return wave;
        }
        JSONArray groups = renderingParts(unit, directed, mode);
        VoicePcm pcm = new VoicePcm(sink);
        pcm.silence(40);
        for (int i = 0; i < groups.length(); i++) {
            checkCancelled(cancellation);
            JSONObject part = groups.optJSONObject(i);
            // Direction and reading ranges both refer to the unchanged source. Resolve the
            // complete merged group here, never slice the expanded kana with source offsets.
            String spoken = SpeechReadingInput.text(unit, part.optInt("start"), part.optInt("end"));
            // OpenJTalk has no phonemes for a leading ellipsis/quote-only part. Such a part still
            // owns its exact source range and requested pause; ordinary text, numbers and emoji
            // always reach native synthesis. The legacy full-utterance path stays unchanged.
            if (!punctuationOnly(spoken)) {
                DialogueActing beat = mode == DialogueMode.STANDARD ? null : DialogueActing.valueOf(part.optString("beat"));
                int style;
                if (beat == null) style = OfflineVoices.styleId(voice, part);
                else if (mode == DialogueMode.NATURAL) style = beat.conversationStyle(voice, part, unit.optBoolean("locked"));
                else style = beat.style(voice, part);
                JSONObject voiceControls = directed ? part : unit;
                if (mode == DialogueMode.NATURAL) voiceControls = DialogueActing.conversationControls(voiceControls, unit.optBoolean("locked"));
                JSONObject parameters = directed ? OfflineVoices.performanceParameters(voiceControls, speaker) : OfflineVoices.parameters(voiceControls, speaker);
                if (!directed) {
                    Json.put(parameters, "prePhonemeLength", 0);
                    Json.put(parameters, "postPhonemeLength", 0);
                }
                byte[] wave = synthesizePart(spoken, style, parameters, part, cancellation, pronounced ? unit : null,
                        part.optInt("start"), part.optInt("end"), beat, mode);
                checkCancelled(cancellation);
                if (beat == null) pcm.appendWave(wave);
                else {
                    byte[] samples = VoicePcm.samples(wave);
                    beat.dynamics(samples,part,mode);
                    pcm.appendSamples(samples);
                }
            }
            // Final padding is a minimum, not a second pause added to the requested final pause.
            int pause = part.optInt("pauseAfterMs");
            pcm.silence(i + 1 == groups.length() ? Math.max(80, pause) : pause);
            checkCancelled(cancellation);
        }
        return pcm.wave();
    }

    static boolean punctuationOnly(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int offset = 0; offset < text.length();) {
            int code = text.codePointAt(offset); offset += Character.charCount(code);
            if (Character.isWhitespace(code) || Character.isSpaceChar(code)) continue;
            int type = Character.getType(code);
            if (type != Character.CONNECTOR_PUNCTUATION && type != Character.DASH_PUNCTUATION
                    && type != Character.START_PUNCTUATION && type != Character.END_PUNCTUATION
                    && type != Character.INITIAL_QUOTE_PUNCTUATION && type != Character.FINAL_QUOTE_PUNCTUATION
                    && type != Character.OTHER_PUNCTUATION) return false;
        }
        return true;
    }

    /** Retain the original offsets and combine adjacent directions only when no boundary is acted. */
    static JSONArray renderingParts(JSONObject unit) {
        if (!SpeechPerformance.valid(unit)) return new JSONArray();
        return renderingParts(unit, true, false);
    }

    static JSONArray renderingParts(JSONObject unit, boolean directed, boolean animated) {
        return renderingParts(unit, directed, animated ? DialogueMode.ANIME : DialogueMode.STANDARD);
    }

    static JSONArray renderingParts(JSONObject unit, boolean directed, DialogueMode mode) {
        JSONArray result = new JSONArray();
        JSONArray parts = directed ? Json.arr(unit.optJSONObject("performance"), "parts") : SpeechPerformance.parts(unit);
        String source = SpeechPerformance.spokenText(unit);
        JSONObject previous = null;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = Json.copy(parts.optJSONObject(i));
            if (!directed) {
                for (String name : new String[]{"emotion","intensity","rate","pitch","delivery","ending","pauseAfterMs"})
                    if (unit.has(name)) Json.put(part,name,unit.opt(name));
                if (i+1 < parts.length()) part.remove("pauseAfterMs");
                if (!part.has("ending")) Json.put(part,"ending","auto");
            }
            if (mode != DialogueMode.STANDARD) {
                String text = source.substring(part.optInt("start"),part.optInt("end"));
                DialogueActing beat = DialogueActing.select(part,text,"thought".equals(unit.optString("kind")));
                Json.put(part,"beat",beat.name());
                int authored = part.optInt("pauseAfterMs");
                boolean manual = unit.optBoolean("locked");
                int pause = mode == DialogueMode.NATURAL ? beat.conversationPause(text, authored, manual) : beat.pause(text,authored,manual);
                Json.put(part,"pauseAfterMs",pause);
            }
            if (previous != null && previous.optString("beat").equals(part.optString("beat"))
                    && previous.optInt("pauseAfterMs") == 0
                    && "auto".equals(previous.optString("ending")) && (sameDirection(previous, part)
                    || mode == DialogueMode.NATURAL && !unit.optBoolean("locked") && similarConversation(previous, part))) {
                Json.put(previous, "end", part.optInt("end"));
                Json.put(previous, "ending", part.optString("ending"));
                Json.put(previous, "pauseAfterMs", part.optInt("pauseAfterMs"));
            } else {
                previous = part;
                result.put(previous);
            }
        }
        return result;
    }

    private static boolean sameDirection(JSONObject a, JSONObject b) {
        for (String key : new String[]{"emotion", "delivery"})
            if (!a.optString(key).equals(b.optString(key))) return false;
        for (String key : new String[]{"intensity", "rate", "pitch", "intonation", "volume", "emphasis"})
            if (Double.compare(a.optDouble(key), b.optDouble(key)) != 0) return false;
        return true;
    }

    // Keep an ordinary utterance connected across negligible changes in automatic direction.
    private static boolean similarConversation(JSONObject a, JSONObject b) {
        if (!"TALK".equals(a.optString("beat")) && !"INNER".equals(a.optString("beat"))) return false;
        if (!a.optString("emotion").equals(b.optString("emotion")) || !a.optString("delivery").equals(b.optString("delivery"))) return false;
        for (String key : new String[]{"rate", "pitch", "intonation", "volume"})
            if (Math.abs(a.optDouble(key, 1) - b.optDouble(key, 1)) > .045) return false;
        return Math.max(a.optDouble("emphasis", 0), b.optDouble("emphasis", 0)) <= .25
                && Math.max(a.optDouble("intensity", 0), b.optDouble("intensity", 0)) <= .45;
    }

    private byte[] synthesizePart(String text, int styleId, JSONObject parameters, JSONObject performance,
                                  Cancellation cancellation) throws IOException {
        return synthesizePart(text, styleId, parameters, performance, cancellation, null, 0, text == null ? 0 : text.length(), null, DialogueMode.STANDARD);
    }

    private byte[] synthesizePart(String text, int styleId, JSONObject parameters, JSONObject performance,
                                  Cancellation cancellation, JSONObject unit, int start, int end, DialogueActing beat, DialogueMode mode) throws IOException {
        checkCancelled(cancellation);
        if (closed) throw new IOException("VOICEVOX_UNAVAILABLE");
        if (text == null || text.trim().isEmpty()) throw new IOException("VOICEVOX_EMPTY_TEXT");
        if (text.length() > MAX_TEXT_LENGTH) throw new IOException("VOICEVOX_TEXT_TOO_LONG");
        if (!packs.isReady()) throw new IOException("VOICE_PACKS_NOT_READY");
        File model = packs.modelFile(styleId);
        if (model == null) throw new IOException("VOICEVOX_UNSUPPORTED_STYLE");
        initialize();
        try {
            ensureModel(model);
            checkCancelled(cancellation);
            AudioQuery query = utteranceQuery(text, styleId, unit, start, end, cancellation);
            checkCancelled(cancellation);
            applyParameters(query, parameters);
            if (beat != null) applyActing(query, beat, performance, mode);
            if (performance != null) applyPerformance(query, performance);
            if (unit != null) new WordContour(query).apply(unit.optJSONObject("pronunciation").optJSONArray("parts"), start, end, mode.wordStrength);
            byte[] wave = synthesizer.synthesis(query, styleId).interrogativeUpspeak(true).perform();
            checkCancelled(cancellation);
            if (wave == null || wave.length < 44 || wave[0] != 'R' || wave[1] != 'I'
                    || wave[2] != 'F' || wave[3] != 'F' || wave[8] != 'W' || wave[9] != 'A'
                    || wave[10] != 'V' || wave[11] != 'E') {
                throw new IOException("VOICEVOX_SYNTHESIS_FAILED");
            }
            return wave;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            // Native failures may contain the spoken text or private paths. Expose a fixed code only.
            throw new IOException("VOICEVOX_SYNTHESIS_FAILED", e);
        }
    }

    private AudioQuery utteranceQuery(String reading, int styleId, JSONObject unit, int start, int end,
                                      Cancellation cancellation) throws Exception {
        if (unit != null) {
            String spoken = SpeechPerformance.spokenText(unit);
            String surface = start == 0 && end == spoken.length() ? unit.optString("text", spoken) : spoken.substring(start, end);
            if (!surface.equals(reading) && !punctuationOnly(surface) && WordProsody.pauses(surface).equals(WordProsody.pauses(reading))) {
                // The written sentence retains lexical accents and phrase boundaries lost in an
                // all-kana transcript. Use it only if its actual native moras give the required reading.
                AudioQuery original = null;
                try { original = synthesizer.createAudioQuery(surface, styleId); }
                catch (Exception unsupportedSurface) { /* The explicit reading can still be spoken. */ }
                checkCancelled(cancellation);
                if (original != null && new WordContour(original).soundsLike(reading)) return original;
            }
        }
        return synthesizer.createAudioQuery(reading, styleId);
    }

    /** Shape a continuous phrase while keeping native lexical accents and unvoiced consonants. */
    static void applyActing(AudioQuery query, DialogueActing beat, JSONObject part, DialogueMode mode) {
        double weight = beat.weight(part, mode);
        if (weight == 0) return;
        query.speedScale = Math.max(.60,Math.min(1.65,query.speedScale*(1+(beat.tempo-1)*weight)));
        query.intonationScale = Math.max(.65,Math.min(1.65,query.intonationScale*(1+(beat.intonation-1)*weight)));
        if (query.accentPhrases==null) return;
        java.util.ArrayList<Mora> voiced=new java.util.ArrayList<>();
        for (AccentPhrase phrase:query.accentPhrases) {
            if (phrase.moras!=null) for (Mora mora:phrase.moras)
                if (mora.pitch>0 && Double.isFinite(mora.pitch)) voiced.add(mora);
        }
        for (int i=0;i<voiced.size();i++) {
            Mora mora=voiced.get(i);
            double t=voiced.size()==1?.32:i/(double)(voiced.size()-1);
            // Authored endings take precedence over the default tail pitch (e.g. a firm question).
            boolean nativeQuestion = mode == DialogueMode.NATURAL && beat == DialogueActing.QUESTION;
            double endPitch = "auto".equals(part.optString("ending", "auto")) && !nativeQuestion ? beat.endPitch : 0;
            double semitones=DialogueActing.curve(t,beat.startPitch,beat.peakPitch,endPitch);
            mora.pitch=Math.max(.001,mora.pitch+semitones*weight*Math.log(2)/12);
            double stretch=DialogueActing.curve(t,beat.startLength,1,beat.endLength);
            if (mora.vowelLength>0) mora.vowelLength*=1+(stretch-1)*weight;
        }
        if (mode == DialogueMode.ANIME && beat==DialogueActing.QUESTION && "auto".equals(part.optString("ending","auto")) && !query.accentPhrases.isEmpty())
            query.accentPhrases.get(query.accentPhrases.size()-1).isInterrogative=false;
    }

    /** Adjust the ending only; preserve lexical accent positions and every unvoiced pitch/consonant. */
    static void applyPerformance(AudioQuery query, JSONObject part) {
        if (query.accentPhrases == null || query.accentPhrases.isEmpty()) return;
        AccentPhrase finalPhrase = query.accentPhrases.get(query.accentPhrases.size() - 1);
        // The assembler owns this boundary's exact silence. Internal punctuation pauses stay native.
        finalPhrase.pauseMora = null;
        String ending = part.optString("ending", "auto");
        if ("auto".equals(ending)) return;
        finalPhrase.isInterrogative = false; // Avoid applying native upspeak a second time.
        if (finalPhrase.moras == null || finalPhrase.moras.isEmpty()) return;
        java.util.ArrayList<Mora> voiced = new java.util.ArrayList<>();
        for (Mora mora : finalPhrase.moras) if (mora.pitch > 0 && Double.isFinite(mora.pitch)) voiced.add(mora);
        if (voiced.isEmpty()) return;
        double intensity = number(part, "intensity", .4, 0, 1);
        int begin = Math.max(0, voiced.size() - 3), count = voiced.size() - begin;
        if ("rise".equals(ending) || "fall".equals(ending)) {
            double delta = "rise".equals(ending) ? .07 + .05 * intensity : -.05 - .04 * intensity;
            for (int i = begin; i < voiced.size(); i++) {
                Mora mora = voiced.get(i);
                mora.pitch = Math.max(.001, mora.pitch + delta * (i - begin + 1) / count);
            }
        } else {
            Mora last = voiced.get(voiced.size() - 1);
            if ("hold".equals(ending)) last.vowelLength += Math.min(.08, last.vowelLength * (.12 + .10 * intensity));
            else if ("cut".equals(ending) && last.vowelLength > 0)
                last.vowelLength = Math.min(last.vowelLength, Math.max(.025, last.vowelLength * .84));
        }
    }

    private void initialize() throws IOException {
        if (synthesizer != null) return;
        try {
            File runtime = new File(context.getApplicationInfo().nativeLibraryDir,
                    "libvoicevox_onnxruntime.so");
            if (!runtime.isFile()) throw new IOException("Missing packaged runtime");
            Onnxruntime onnx = Onnxruntime.loadOnce().filename(runtime.getAbsolutePath()).perform();
            OpenJtalk openJtalk = new OpenJtalk(packs.dictionaryDirectory().getAbsolutePath());
            int threads = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2));
            synthesizer = Synthesizer.builder(onnx, openJtalk)
                    .accelerationMode(AccelerationMode.CPU).cpuNumThreads(threads).build();
        } catch (Exception | LinkageError e) {
            throw new IOException("VOICEVOX_UNAVAILABLE", e);
        }
    }

    private void ensureModel(File file) throws Exception {
        String key = file.getName();
        if (loaded.get(key) != null) return;
        while (loaded.size() >= MAX_RESIDENT_MODELS) {
            Iterator<Map.Entry<String, UUID>> oldest = loaded.entrySet().iterator();
            Map.Entry<String, UUID> entry = oldest.next();
            synthesizer.unloadVoiceModel(entry.getValue());
            oldest.remove();
        }
        try (VoiceModelFile model = new VoiceModelFile(file.getAbsolutePath())) {
            synthesizer.loadVoiceModel(model).perform();
            loaded.put(key, model.id);
        }
    }

    static void applyParameters(AudioQuery query, JSONObject value) {
        JSONObject p = value == null ? new JSONObject() : value;
        query.speedScale = number(p, "speedScale", 1, .5, 2);
        query.pitchScale = number(p, "pitchScale", 0, -.15, .15);
        query.intonationScale = number(p, "intonationScale", 1, .2, 2);
        query.volumeScale = number(p, "volumeScale", 1, .1, 1.8);
        query.prePhonemeLength = number(p, "prePhonemeLength", .08, 0, 1);
        query.postPhonemeLength = number(p, "postPhonemeLength", .12, 0, 1.5);
        query.outputSamplingRate = 24000;
        query.outputStereo = false;
        double pause = number(p, "pauseLengthScale", 1, .3, 2.5);
        // CORE's Java AudioQuery has no pauseLengthScale field. Adjust its real pause mora lengths.
        if (query.accentPhrases != null && pause != 1) {
            for (AccentPhrase phrase : query.accentPhrases) {
                if (phrase.pauseMora != null) phrase.pauseMora.vowelLength *= pause;
            }
        }
    }

    private static double number(JSONObject p, String key, double fallback, double min, double max) {
        double value = p.optDouble(key, fallback);
        return Double.isNaN(value) || Double.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value));
    }

    private static void checkCancelled(Cancellation value) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || value != null && value.cancelled())
            throw new InterruptedIOException("VOICEVOX_CANCELLED");
    }

    @Override public synchronized void close() {
        closed = true;
        if (synthesizer != null) {
            for (UUID id : loaded.values()) {
                try { synthesizer.unloadVoiceModel(id); } catch (RuntimeException ignored) { }
            }
        }
        loaded.clear();
        // Upstream 0.17.1 Synthesizer/OpenJtalk expose no close(); their JNI owners are finalized.
        synthesizer = null;
    }
}
