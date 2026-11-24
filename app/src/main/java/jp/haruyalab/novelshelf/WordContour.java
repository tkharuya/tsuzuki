package jp.haruyalab.novelshelf;

import java.util.ArrayList;
import java.util.List;
import jp.hiroshiba.voicevoxcore.AccentPhrase;
import jp.hiroshiba.voicevoxcore.AudioQuery;
import jp.hiroshiba.voicevoxcore.Mora;
import org.json.JSONArray;
import org.json.JSONObject;

/** Edits voiced moras in the native utterance; word boundaries never start a new synthesis call. */
final class WordContour {
    private final StringBuilder kana = new StringBuilder();
    private final List<Mora> moras = new ArrayList<>();
    private final List<Integer> boundaries = new ArrayList<>();

    WordContour(AudioQuery query) {
        boundaries.add(0);
        if (query.accentPhrases == null) return;
        for (AccentPhrase phrase : query.accentPhrases) {
            if (phrase.moras == null) continue;
            for (Mora mora : phrase.moras) {
                if (mora.text == null) continue;
                WordProsody.appendKey(kana, mora.text);
                moras.add(mora);
                boundaries.add(kana.length());
            }
        }
    }

    boolean soundsLike(String reading) {
        return kana.length() > 0 && kana.toString().equals(WordProsody.key(reading));
    }

    void apply(JSONArray parts, int start, int end) { apply(parts,start,end,1); }

    void apply(JSONArray parts, int start, int end, double strengthScale) {
        if (parts == null || kana.length() == 0) return;
        StringBuilder spoken = new StringBuilder();
        List<JSONObject> selected = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part.optInt("start") < start || part.optInt("end") > end) continue;
            selected.add(part);
            offsets.add(spoken.length());
            WordProsody.appendKey(spoken, part.optString("reading"));
        }
        String actual = kana.toString(), intended = spoken.toString();
        boolean aligned = actual.equals(intended);
        for (int i = 0; i < selected.size(); i++) {
            JSONObject part = selected.get(i);
            JSONArray saved = part.optJSONArray("words");
            if (saved == null || saved.length() == 0) continue;
            JSONArray words = WordProsody.checked(part.optString("reading"), saved);
            String segment = WordProsody.key(part.optString("reading"));
            for (int j = 0; j < words.length(); j++) {
                JSONObject cue = words.optJSONObject(j);
                String anchor = WordProsody.key(cue.optString("word"));
                int pos = WordProsody.occurrence(segment, anchor, cue.optInt("occurrence"));
                if (pos < 0) continue;
                if (aligned) pos += offsets.get(i);
                else {
                    // A number or mixed script may be expanded by Open JTalk. Transfer only a
                    // uniquely identifiable full-mora span in both versions of this group.
                    pos = actual.indexOf(anchor);
                    if (pos < 0 || pos != actual.lastIndexOf(anchor) || intended.indexOf(anchor) != intended.lastIndexOf(anchor)) continue;
                }
                int first = java.util.Collections.binarySearch(boundaries, pos);
                int stop = java.util.Collections.binarySearch(boundaries, pos + anchor.length());
                if (first >= 0 && stop > first) shape(first, stop, cue.optString("tone"), cue.optInt("strength"), strengthScale);
            }
        }
    }

    private void shape(int first, int stop, String tone, int strength, double strengthScale) {
        double power = (.35 + .2 * strength) * strengthScale;
        for (int i = first; i < stop; i++) {
            Mora mora = moras.get(i);
            double t = (i - first + .5) / (stop - first);
            double arch = Math.sin(Math.PI * t), semitones, stretch;
            switch (tone) {
                case "focus":
                    semitones = power * (1.3 + 1.8 * arch);
                    stretch = 1 + power * (.07 + .11 * arch); break;
                case "soft":
                    semitones = -power * (1.1 + 1.0 * t);
                    stretch = 1 + power * (.05 + .10 * t); break;
                case "hesitate":
                    semitones = power * (-1.6 + 2.0 * t);
                    stretch = 1 + power * (.10 + .34 * (1 - t)); break;
                case "firm":
                    semitones = power * (2.1 - 3.6 * t);
                    stretch = 1 + power * (.13 - .18 * t); break;
                default: continue;
            }
            // Native Japanese pitches are log Hz. A zero pitch designates an unvoiced mora.
            if (mora.pitch > 0 && Double.isFinite(mora.pitch)) {
                mora.pitch = Math.max(.001, mora.pitch + semitones * Math.log(2.0) / 12.0);
                if (mora.vowelLength > 0) mora.vowelLength *= stretch;
            }
        }
    }
}
