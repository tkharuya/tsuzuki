package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Original-text-owned acting parts. GPT may annotate IDs, never replace text or offsets. */
public final class SpeechPerformance {
    public static final int VERSION = 1;
    private static final String[] EMOTIONS = {"neutral", "joy", "sadness", "anger", "fear", "surprise",
            "calm", "tension", "relief", "determination", "embarrassment", "sarcasm"};
    private static final String[] DELIVERIES = {"natural", "soft", "whisper", "firm", "urgent", "hesitant"};
    private static final String[] ENDINGS = {"auto", "rise", "fall", "hold", "cut"};
    private static final String[] FIELDS = {"partId", "emotion", "delivery", "intensity", "rate", "pitch",
            "intonation", "volume", "pauseAfterMs", "ending", "emphasis", "confidence"};
    private SpeechPerformance() { }

    public static String[] emotions() { return EMOTIONS.clone(); }
    public static String[] deliveries() { return DELIVERIES.clone(); }
    public static String[] endings() { return ENDINGS.clone(); }

    /** UTF-16 offsets into spokenText, including ruby readings already resolved by SpeechPlan. */
    public static JSONArray parts(JSONObject unit) {
        String text = spokenText(unit);
        JSONArray result = new JSONArray();
        int start = 0, at = 0;
        while (at < text.length()) {
            int cp = text.codePointAt(at), next = at + Character.charCount(cp);
            if (boundary(text, at, cp)) {
                int end = next;
                while (end < text.length()) {
                    int following = text.codePointAt(end);
                    if (boundary(text, end, following) || closing(following)) end += Character.charCount(following);
                    else break;
                }
                // Keep spaces with their preceding phrase; there is never a silent whitespace-only part.
                while (end < text.length() && Character.isWhitespace(text.codePointAt(end)))
                    end += Character.charCount(text.codePointAt(end));
                add(result, text, start, end);
                start = end; at = end;
            } else at = next;
        }
        if (start < text.length()) add(result, text, start, text.length());
        return result;
    }

    private static void add(JSONArray out, String text, int start, int end) {
        if (end <= start) return;
        out.put(Json.obj("partId", "p" + out.length(), "start", start, "end", end,
                "text", text.substring(start, end)));
    }

    private static boolean boundary(String text, int at, int cp) {
        if (cp == '.' || cp == ',') {
            // Decimal/thousands punctuation within a number is not an acting boundary.
            if (at > 0 && at + 1 < text.length() && Character.isDigit(text.codePointBefore(at))
                    && Character.isDigit(text.codePointAt(at + 1))) return false;
        }
        return cp == '。' || cp == '！' || cp == '？' || cp == '!' || cp == '?' || cp == '.'
                || cp == '、' || cp == ',' || cp == '，' || cp == ';' || cp == '；'
                || cp == '…' || cp == '‥' || cp == '\n' || cp == '\r';
    }
    private static boolean closing(int cp) {
        return cp == '」' || cp == '』' || cp == '”' || cp == '"' || cp == '\''
                || cp == '）' || cp == ')' || cp == '］' || cp == ']' || cp == '〉' || cp == '》';
    }

    public static String spokenText(JSONObject unit) {
        return unit == null ? "" : unit.optString("spokenText", unit.optString("text", ""));
    }
    public static String sourceHash(JSONObject unit) {
        return hash("speech-performance-" + VERSION + "\n" + spokenText(unit));
    }

    /**
     * Accepts model annotations without offsets, or saved/manual annotations with matching offsets.
     * The result is ordered by original text, regardless of response order.
     */
    public static JSONObject validate(JSONObject unit, JSONArray returnedParts, boolean reviewed) {
        JSONArray expected = parts(unit);
        if (returnedParts == null || expected.length() != returnedParts.length()) throw invalid();
        Map<String, JSONObject> incoming = new HashMap<>();
        Set<String> allowedIds = new HashSet<>();
        for (int i = 0; i < expected.length(); i++) allowedIds.add(expected.optJSONObject(i).optString("partId"));
        for (int i = 0; i < returnedParts.length(); i++) {
            JSONObject part = returnedParts.optJSONObject(i);
            if (part == null) throw invalid();
            Set<String> fields = new HashSet<>(Arrays.asList(FIELDS));
            boolean hasOffsets = part.has("start") || part.has("end");
            if (hasOffsets) { fields.add("start"); fields.add("end"); }
            exact(part, fields);
            Object id = part.opt("partId");
            if (!(id instanceof String) || !allowedIds.contains(id) || incoming.put((String) id, part) != null) throw invalid();
        }
        JSONArray canonical = new JSONArray();
        for (int i = 0; i < expected.length(); i++) {
            JSONObject local = expected.optJSONObject(i), part = incoming.get(local.optString("partId"));
            if (part == null) throw invalid();
            if (part.has("start") && (integer(part, "start", 0, Integer.MAX_VALUE) != local.optInt("start")
                    || integer(part, "end", 0, Integer.MAX_VALUE) != local.optInt("end"))) throw invalid();
            canonical.put(Json.obj("partId", local.optString("partId"), "start", local.optInt("start"), "end", local.optInt("end"),
                    "emotion", choice(part, "emotion", EMOTIONS), "delivery", choice(part, "delivery", DELIVERIES),
                    "intensity", number(part, "intensity", 0, 1), "rate", number(part, "rate", .7, 1.4),
                    "pitch", number(part, "pitch", .8, 1.2), "intonation", number(part, "intonation", .6, 1.6),
                    "volume", number(part, "volume", .75, 1.2), "pauseAfterMs", integer(part, "pauseAfterMs", 0, 1200),
                    "ending", choice(part, "ending", ENDINGS), "emphasis", number(part, "emphasis", 0, 1),
                    "confidence", number(part, "confidence", 0, 1)));
        }
        return Json.obj("version", VERSION, "sourceHash", sourceHash(unit), "reviewed", reviewed, "parts", canonical);
    }

    public static boolean valid(JSONObject unit) {
        try {
            if (unit == null) return false;
            JSONObject performance = unit.optJSONObject("performance");
            if (performance == null) return false;
            exact(performance, new HashSet<>(Arrays.asList("version", "sourceHash", "reviewed", "parts")));
            if (integer(performance, "version", VERSION, VERSION) != VERSION
                    || !(performance.opt("reviewed") instanceof Boolean)
                    || !sourceHash(unit).equals(performance.opt("sourceHash"))) return false;
            JSONArray saved = performance.optJSONArray("parts"), original = parts(unit);
            if (saved == null || saved.length() != original.length()) return false;
            for (int i = 0; i < saved.length(); i++) {
                JSONObject part = saved.optJSONObject(i);
                // Playback consumes the saved array in this order. A response may arrive unordered,
                // but persisted data must already be canonical to avoid reordering spoken text.
                if (part == null || !part.has("start") || !part.has("end")
                        || !original.optJSONObject(i).optString("partId").equals(part.opt("partId"))) return false;
            }
            validate(unit, saved, true);
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    /** Stable cache identity. A valid one-pass result explicitly records reviewed=false. */
    public static String signature(JSONObject unit) {
        if (!valid(unit)) return "";
        JSONObject canonical = validate(unit, unit.optJSONObject("performance").optJSONArray("parts"), true);
        JSONArray sequence = new JSONArray().put(VERSION).put(canonical.optString("sourceHash"));
        JSONArray parts = canonical.optJSONArray("parts");
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            JSONArray values = new JSONArray().put(part.optInt("start")).put(part.optInt("end"));
            for (String name : FIELDS) values.put(part.opt(name));
            sequence.put(values);
        }
        return hash(sequence.toString());
    }

    public static int completedCount(JSONObject plan) {
        if (!"detailed".equals(plan.optString("analysisDetail"))) return SpeechPlan.analyzedCount(plan);
        int count = 0; JSONArray units = Json.arr(plan, "units");
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.optJSONObject(i);
            if (unit != null && (unit.optBoolean("locked") || unit.optBoolean("analyzed") && valid(unit))) count++;
        }
        return count;
    }

    static void modelFields(JSONObject part) {
        exact(part, new HashSet<>(Arrays.asList(FIELDS)));
    }
    private static void exact(JSONObject value, Set<String> fields) {
        if (value == null || value.length() != fields.size()) throw invalid();
        for (Iterator<String> keys = value.keys(); keys.hasNext();) if (!fields.remove(keys.next())) throw invalid();
        if (!fields.isEmpty()) throw invalid();
    }
    private static String choice(JSONObject value, String name, String[] options) {
        Object raw = value.opt(name);
        if (!(raw instanceof String)) throw invalid();
        for (String choice : options) if (choice.equals(raw)) return choice;
        throw invalid();
    }
    private static double number(JSONObject value, String name, double min, double max) {
        Object raw = value.opt(name);
        if (!(raw instanceof Number)) throw invalid();
        double result = ((Number) raw).doubleValue();
        if (Double.isNaN(result) || Double.isInfinite(result) || result < min || result > max) throw invalid();
        return result;
    }
    private static int integer(JSONObject value, String name, int min, int max) {
        double result = number(value, name, min, max);
        if (result != Math.rint(result)) throw invalid();
        return (int) result;
    }
    private static String hash(String text) {
        try {
            StringBuilder result = new StringBuilder(64);
            for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
                result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("細かな演技データが本文と一致しません。確定済みの解析は保持しています。");
    }
}
