package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;

/** Literal kana at the audio boundary. Never infer particles or long vowels from ordinary text. */
final class SpeechReadingInput {
    private SpeechReadingInput() { }

    static String text(JSONObject unit) {
        return text(unit, 0, SpeechPerformance.spokenText(unit).length());
    }

    static String text(JSONObject unit, int start, int end) {
        String source = SpeechPerformance.spokenText(unit);
        if (start < 0 || end < start || end > source.length())
            throw new IllegalArgumentException("読みの範囲が本文と一致しません。");
        if (start == end) return "";
        String original = source.substring(start, end);
        if (!SpeechPronunciation.valid(unit)) return original;
        JSONArray parts = unit.optJSONObject("pronunciation").optJSONArray("parts");
        StringBuilder out = new StringBuilder(); int at = start;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i); int first = part.optInt("start"), last = part.optInt("end");
            if (last <= start) continue;
            // No inferred position inside an expanded reading. Acting groups use whole source parts.
            if (first != at || last > end) return original;
            String reading = part.optString("reading");
            // An unchanged editor template or remaining kanji is still normal Japanese text.
            // Keep its dictionary/particle parsing, including unedited greetings such as こんにちは.
            out.append(reading.equals(source.substring(first, last)) || hasKanji(reading) ? reading : kana(reading));
            at = last; if (at == end) return out.toString();
        }
        return original;
    }

    private static boolean hasKanji(String text) {
        for (int offset = 0; offset < text.length();) {
            int code = text.codePointAt(offset); offset += Character.charCount(code);
            if (Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    static String kana(String reading) {
        StringBuilder out = new StringBuilder(reading.length());
        for (int offset = 0; offset < reading.length();) {
            int code = reading.codePointAt(offset); offset += Character.charCount(code);
            if (code >= 0x3041 && code <= 0x3096 || code >= 0x309d && code <= 0x309e) code += 0x60;
            out.appendCodePoint(code);
        }
        return out.toString();
    }
}
