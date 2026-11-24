package jp.haruyalab.novelshelf;

import java.text.Normalizer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Word anchors refer to the verified reading of one source part, never to offsets in the printed novel. */
final class WordProsody {
    private WordProsody() { }

    static String key(String text) {
        StringBuilder out = new StringBuilder();
        appendKey(out, text);
        return out.toString();
    }

    static void appendKey(StringBuilder out, String text) {
        String kana = Normalizer.normalize(text, Normalizer.Form.NFKC);
        for (int i = 0; i < kana.length();) {
            int cp = kana.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x3041 && cp <= 0x3096) cp += 0x60;
            if (cp == 'ー' && out.length() > 0) {
                char vowel = vowelOf(out.charAt(out.length() - 1));
                if (vowel != 0) cp = vowel;
            }
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(cp);
        }
    }

    /** Retain explicitly edited stops and question contours when selecting written-text accents. */
    static String pauses(String text) {
        StringBuilder out = new StringBuilder();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        for (int i = 0; i < normalized.length(); i++) {
            switch (normalized.charAt(i)) {
                case '、': case ',': out.append(','); break;
                case '。': case '.': out.append('.'); break;
                case '?': case '!': case ':': case ';': out.append(normalized.charAt(i)); break;
                default: break;
            }
        }
        return out.toString();
    }

    private static char vowelOf(char kana) {
        if ("アァカガサザタダナハバパマャヤラヮワ".indexOf(kana) >= 0) return 'ア';
        if ("イィキギシジチヂニヒビピミリヰ".indexOf(kana) >= 0) return 'イ';
        if ("ウゥクグスズツヅヌフブプムュユルヴ".indexOf(kana) >= 0) return 'ウ';
        if ("エェケゲセゼテデネヘベペメレヱ".indexOf(kana) >= 0) return 'エ';
        if ("オォコゴソゾトドノホボポモョヨロヲ".indexOf(kana) >= 0) return 'オ';
        return 0;
    }

    static int occurrence(String reading, String word, int ordinal) {
        if (word.isEmpty() || ordinal < 1) return -1;
        int at = -1;
        for (int i = 0; i < ordinal; i++) {
            at = reading.indexOf(word, at < 0 ? 0 : at + word.length());
            if (at < 0) break;
        }
        return at;
    }

    static JSONArray checked(String reading, JSONArray words) {
        JSONArray clean = new JSONArray();
        if (words == null || words.length() == 0) return clean;
        String phonetic = key(reading);
        boolean[] occupied = new boolean[phonetic.length()];
        for (int i = 0; i < words.length(); i++) {
            JSONObject cue = words.optJSONObject(i);
            if (cue == null || !(cue.opt("word") instanceof String)) continue;
            String literal = cue.optString("word").trim(), tone = cue.optString("tone");
            if (!Normalizer.normalize(literal, Normalizer.Form.NFKC).matches("[ぁ-ゖァ-ヺー]+")) continue;
            String anchor = key(literal);
            if (!("focus".equals(tone) || "soft".equals(tone) || "hesitate".equals(tone) || "firm".equals(tone))) continue;
            if (anchor.isEmpty() || !anchor.matches("[ァ-ヺー]+") || anchor.length() > phonetic.length()) continue;
            int ordinal = exactPositive(cue.opt("occurrence")), strength = exactPositive(cue.opt("strength"));
            if (strength < 1 || strength > 3) continue;
            int at = occurrence(phonetic, anchor, ordinal);
            if (at < 0) continue;
            int end = at + anchor.length();
            // A following small vowel or glide belongs to the previous kana (キョ is one mora).
            if ((at > 0 && "ァィゥェォャュョヮ".indexOf(phonetic.charAt(at)) >= 0)
                    || (end < phonetic.length() && "ァィゥェォャュョヮ".indexOf(phonetic.charAt(end)) >= 0)) continue;
            boolean overlaps = false;
            for (int n = at; n < end; n++) if (occupied[n]) { overlaps = true; break; }
            if (overlaps) continue;
            java.util.Arrays.fill(occupied, at, end, true);
            clean.put(Json.obj("word", literal, "occurrence", ordinal, "tone", tone, "strength", strength));
        }
        return clean;
    }

    private static int exactPositive(Object value) {
        if (!(value instanceof Number)) return 0;
        double n = ((Number)value).doubleValue();
        return n >= 1 && n <= Integer.MAX_VALUE && n == Math.rint(n) ? (int)n : 0;
    }

    static boolean scored(JSONObject unit) {
        JSONObject saved = unit.optJSONObject("pronunciation");
        JSONArray parts = saved == null ? null : saved.optJSONArray("parts");
        if (parts == null || parts.length() == 0) return false;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null || part.optJSONArray("words") == null) return false;
        }
        return true;
    }
}
