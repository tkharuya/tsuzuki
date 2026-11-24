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

/** Separate, source-owned pronunciation. Original text, ruby text and acting offsets never change. */
public final class SpeechPronunciation {
    public static final int VERSION = 1;
    public static final int MAX_READING_CHARS = 2000;
    public enum Reason { FORMAT, PART, SOURCE, READING, LIMIT, RUBY }
    public static final class ValidationFailure extends IllegalArgumentException {
        private final Reason reason;
        private final int partIndex;
        private ValidationFailure(Reason reason, int partIndex) {
            super("読みの形式または本文との対応を確認できませんでした。保存済みの読みと演技は保持しています。");
            this.reason = reason; this.partIndex = partIndex;
        }
        public Reason reason() { return reason; }
        public JSONObject diagnostics() {
            JSONObject value = Json.obj("code", "invalid_pronunciation", "stage", "pronunciation_" + reason.name().toLowerCase(java.util.Locale.ROOT),
                    "responseShape", "pronunciation");
            if (partIndex >= 0) Json.put(value, "partIndex", partIndex);
            return value;
        }
        private ValidationFailure at(int index) { return new ValidationFailure(reason, index); }
    }
    private SpeechPronunciation() { }

    public static String sourceHash(JSONObject unit) {
        JSONArray spans = ruby(unit), coordinates = new JSONArray();
        for (int i = 0; i < spans.length(); i++) {
            JSONObject span = spans.optJSONObject(i); coordinates.put(new JSONArray().put(span.optInt("start")).put(span.optInt("end")).put(span.optString("text")));
        }
        return hash("speech-pronunciation-" + VERSION + "\n" + new JSONArray()
                .put(SpeechPerformance.spokenText(unit)).put(coordinates).toString());
    }
    /** Canonical editor state. An unedited template is not proof that a pronunciation was completed. */
    public static JSONObject template(JSONObject unit) {
        if (valid(unit)) return validateManual(unit, unit.optJSONObject("pronunciation").optJSONArray("parts"));
        JSONArray source = SpeechPerformance.parts(unit), parts = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject part = source.optJSONObject(i);
            parts.put(Json.obj("partId", part.optString("partId"), "start", part.optInt("start"), "end", part.optInt("end"), "reading", editorText(part.optString("text"))));
        }
        return Json.obj("version", VERSION, "sourceHash", sourceHash(unit), "parts", parts);
    }
    /** GPT uses the displayed ruby tokens; only this entry point requires exact author-ruby retention. */
    public static JSONObject validate(JSONObject unit, JSONArray returnedParts) {
        return validate(unit, returnedParts, hasKana(SpeechPerformance.spokenText(unit)));
    }
    /** Chapter context can identify a Japanese name-only unit that contains no kana by itself. */
    public static JSONObject validate(JSONObject unit, JSONArray returnedParts, boolean japanese) {
        return validateParts(unit, returnedParts, true, japanese, null);
    }
    /** Manual input may deliberately replace author ruby, with the same immutable source boundaries. */
    public static JSONObject validateManual(JSONObject unit, JSONArray returnedParts) { return validateParts(unit, returnedParts, false, false, null); }
    static JSONObject validateManualEdit(JSONObject unit, JSONArray proposed) {
        JSONObject saved = validateManual(unit, proposed);
        Map<String,Integer> counts = new HashMap<>();
        for (int i=0; i<proposed.length(); i++) {
            JSONObject part=proposed.optJSONObject(i);
            JSONArray words=part.optJSONArray("words");
            counts.put(part.optString("partId"),words==null?0:words.length());
        }
        JSONArray parts=saved.optJSONArray("parts");
        for (int i=0; i<parts.length(); i++) {
            JSONObject part=parts.optJSONObject(i);
            JSONArray words=part.optJSONArray("words");
            if (counts.get(part.optString("partId")) != (words==null?0:words.length()))
                throw new IllegalArgumentException("句 "+(i+1)+" の単語の演技を保存できません。音声用の読みと同じかな、出現回数、他の指定との重なりを確認してください。");
        }
        return saved;
    }
    /** Add directions to an existing verified reading, including previously resolved author ruby. */
    static JSONObject annotateSaved(JSONObject unit, JSONArray proposed) {
        return validateParts(unit, proposed, true, false, unit.optJSONObject("pronunciation").optJSONArray("parts"));
    }
    private static JSONObject validateParts(JSONObject unit, JSONArray returnedParts, boolean model, boolean japanese, JSONArray prior) {
        JSONArray source = SpeechPerformance.parts(unit), modelParts = model && prior == null ? modelParts(unit) : null;
        if (source.length() == 0 || returnedParts == null || returnedParts.length() != source.length()) throw bad(Reason.PART);
        Map<String, JSONObject> incoming = new HashMap<>(); Set<String> ids = new HashSet<>();
        for (int i = 0; i < source.length(); i++) ids.add(source.optJSONObject(i).optString("partId"));
        for (int i = 0; i < returnedParts.length(); i++) {
            JSONObject part = returnedParts.optJSONObject(i); if (part == null) throw bad(Reason.FORMAT).at(i);
            boolean offsets = part.has("start") || part.has("end");
            String[] fields = !model && offsets ? new String[]{"partId", "start", "end", "reading"} : new String[]{"partId", "reading"};
            if (part.has("words")) {
                fields = Arrays.copyOf(fields, fields.length + 1);
                fields[fields.length - 1] = "words";
                if (!(part.opt("words") instanceof JSONArray)) throw bad(Reason.FORMAT);
            }
            exact(part, fields);
            Object id = part.opt("partId");
            if (!(id instanceof String) || !ids.contains((String)id) || incoming.put((String)id, part) != null) throw bad(Reason.PART).at(i);
        }
        JSONArray canonical = new JSONArray(); int length = 0;
        for (int i = 0; i < source.length(); i++) {
            JSONObject original = source.optJSONObject(i), supplied = incoming.get(original.optString("partId"));
            if (supplied == null) throw bad(Reason.PART).at(i);
            try {
                if (supplied.has("start") && (integer(supplied.opt("start")) != original.optInt("start")
                        || integer(supplied.opt("end")) != original.optInt("end"))) throw bad(Reason.SOURCE);
                boolean silent = whitespaceOnly(original.optString("text"));
                String reading = rawReading(supplied.opt("reading"));
                if (prior != null) reading = prior.optJSONObject(i).optString("reading");
                else if (model) reading = expandRuby(reading, modelParts.optJSONObject(i), japanese);
                reading = reading(reading, silent); length += reading.length();
                if (length > MAX_READING_CHARS) throw bad(Reason.LIMIT);
                JSONObject row = Json.obj("partId", original.optString("partId"), "start", original.optInt("start"),
                        "end", original.optInt("end"), "reading", reading);
                if (model || supplied.has("words")) Json.put(row, "words", WordProsody.checked(reading, supplied.optJSONArray("words")));
                canonical.put(row);
            } catch (ValidationFailure failure) { throw failure.at(i); }
        }
        return Json.obj("version", VERSION, "sourceHash", sourceHash(unit), "parts", canonical);
    }
    public static boolean valid(JSONObject unit) {
        try {
            if (unit == null) return false; JSONObject saved = unit.optJSONObject("pronunciation");
            if (saved == null) return false;
            exact(saved, "version", "sourceHash", "parts");
            if (integer(saved.opt("version")) != VERSION || !sourceHash(unit).equals(saved.opt("sourceHash"))) return false;
            JSONArray parts = saved.optJSONArray("parts"), original = SpeechPerformance.parts(unit);
            if (parts == null || parts.length() != original.length()) return false;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null || !part.has("start") || !part.has("end")
                        || !original.optJSONObject(i).optString("partId").equals(part.opt("partId"))) return false;
            }
            validateManual(unit, parts); return true;
        } catch (RuntimeException invalid) { return false; }
    }
    public static String signature(JSONObject unit) {
        if (!valid(unit)) return "";
        JSONObject canonical = validateManual(unit, unit.optJSONObject("pronunciation").optJSONArray("parts"));
        JSONArray fingerprint = new JSONArray().put(VERSION).put(canonical.optString("sourceHash")), parts = canonical.optJSONArray("parts");
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            JSONArray row = new JSONArray().put(part.optString("partId")).put(part.optInt("start")).put(part.optInt("end")).put(part.optString("reading"));
            JSONArray words = part.optJSONArray("words");
            if (words != null && words.length() > 0) {
                JSONArray cues = new JSONArray();
                for (int k = 0; k < words.length(); k++) {
                    JSONObject cue = words.optJSONObject(k);
                    cues.put(new JSONArray().put(cue.optString("word")).put(cue.optInt("occurrence")).put(cue.optString("tone")).put(cue.optInt("strength")));
                }
                row.put(cues);
            }
            fingerprint.put(row);
        }
        return hash(fingerprint.toString());
    }
    public static String text(JSONObject unit) {
        if (!valid(unit)) return SpeechPerformance.spokenText(unit);
        return joined(unit.optJSONObject("pronunciation").optJSONArray("parts"), 0, SpeechPerformance.spokenText(unit).length());
    }
    /** A substring inside a part has no verified phoneme alignment; preserve its original source verbatim. */
    public static String text(JSONObject unit, int start, int end) {
        String source = SpeechPerformance.spokenText(unit);
        if (start < 0 || end < start || end > source.length()) throw new IllegalArgumentException("読みの範囲が本文と一致しません。");
        if (start == end) return "";
        if (valid(unit)) {
            String result = joined(unit.optJSONObject("pronunciation").optJSONArray("parts"), start, end);
            if (result != null) return result;
        }
        return source.substring(start, end);
    }
    private static String joined(JSONArray parts, int start, int end) {
        StringBuilder out = new StringBuilder(); int at = start;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i); int first = part.optInt("start"), last = part.optInt("end");
            if (last <= start) continue;
            if (first != at || last > end) return null;
            out.append(part.optString("reading")); at = last;
            if (at == end) return out.toString();
        }
        return null;
    }
    /** Count complete acting plus opt-in pronunciation, while preserving the pre-1.9.2 contract. */
    public static int completedCount(JSONObject plan) {
        if (plan == null) return 0;
        return completedCount(plan, plan.optInt("pronunciationVersion") == VERSION);
    }
    public static int completedCount(JSONObject plan, boolean requirePronunciation) {
        if (plan == null) return 0;
        if (!requirePronunciation) return SpeechPerformance.completedCount(plan);
        boolean detailed = "detailed".equals(plan.optString("analysisDetail")); int count = 0; JSONArray units = Json.arr(plan, "units");
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.optJSONObject(i);
            if (unit != null && (unit.optBoolean("locked") || unit.optBoolean("analyzed") && (!detailed || SpeechPerformance.valid(unit))) && valid(unit)) count++;
        }
        return count;
    }
    /** Pronunciation-only input: source remains visible, but author ruby must be returned as opaque tokens. */
    public static JSONArray modelParts(JSONObject unit) {
        JSONArray source = SpeechPerformance.parts(unit), ruby = ruby(unit), result = new JSONArray();
        String spoken = SpeechPerformance.spokenText(unit), prefix = "[[ruby_" + sourceHash(unit).substring(0, 12) + "_";
        while (spoken.contains(prefix)) prefix += "r_";
        for (int i = 0; i < source.length(); i++) {
            JSONObject part = source.optJSONObject(i); int first = part.optInt("start"), end = part.optInt("end"), at = first;
            JSONArray tokens = new JSONArray(); StringBuilder template = new StringBuilder();
            for (int r = 0; r < ruby.length(); r++) {
                JSONObject span = ruby.optJSONObject(r); int from = Math.max(first, span.optInt("start")), to = Math.min(end, span.optInt("end"));
                if (from >= to) continue;
                String token = prefix + part.optString("partId") + "_" + tokens.length() + "]]";
                template.append(spoken, at, from).append(token); at = to;
                tokens.put(Json.obj("token", token, "reading", spoken.substring(from, to)));
            }
            template.append(spoken, at, end);
            result.put(Json.obj("partId", part.optString("partId"), "text", part.optString("text"), "readingSource", template.toString(), "rubyTokens", tokens));
        }
        return result;
    }
    private static String expandRuby(String reading, JSONObject part, boolean japanese) {
        JSONArray tokens = Json.arr(part, "rubyTokens"); StringBuilder expanded = new StringBuilder(); int at = 0;
        for (int i = 0; i < tokens.length(); i++) {
            JSONObject item = tokens.optJSONObject(i); String token = item.optString("token"); int found = reading.indexOf(token, at);
            if (found < 0 || reading.indexOf(token) != found || reading.indexOf(token, found + token.length()) >= 0) throw bad(Reason.RUBY);
            if (japanese && hasHan(reading.substring(at, found))) throw bad(Reason.READING);
            expanded.append(reading, at, found).append(item.optString("reading")); at = found + token.length();
        }
        if (japanese && hasHan(reading.substring(at))) throw bad(Reason.READING);
        expanded.append(reading, at, reading.length());
        String result = expanded.toString();
        if (result.contains("[[ruby_")) throw bad(Reason.RUBY);
        return result;
    }
    /** Evidence for Japanese reading mode; it is not a semantic language or pronunciation guarantee. */
    public static boolean hasKana(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            if (Character.isLetter(cp) && (cp >= 0x3040 && cp <= 0x30ff || cp >= 0x31f0 && cp <= 0x31ff
                    || cp >= 0xff66 && cp <= 0xff9d)) return true;
        }
        return false;
    }
    private static boolean hasHan(String text) {
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }
    private static JSONArray ruby(JSONObject unit) {
        String spoken = SpeechPerformance.spokenText(unit); JSONArray input = unit == null ? null : unit.optJSONArray("spokenRuby"), result = new JSONArray();
        if (unit != null && unit.has("spokenRuby") && input == null) throw bad(Reason.SOURCE);
        if (input == null) return result;
        int previous = 0;
        for (int i = 0; i < input.length(); i++) {
            JSONObject span = input.optJSONObject(i); exact(span, "start", "end", "text");
            int start = integer(span.opt("start")), end = integer(span.opt("end")); Object text = span.opt("text");
            if (start < previous || end <= start || end > spoken.length() || !(text instanceof String)
                    || !spoken.substring(start, end).equals(text) || splitSurrogate(spoken, start) || splitSurrogate(spoken, end)) throw bad(Reason.SOURCE);
            result.put(new JSONArray().put(start).put(end).put((String)text)); previous = end;
        }
        // Hash/input representation has named fields in deterministic source order.
        JSONArray named = new JSONArray();
        for (int i = 0; i < result.length(); i++) { JSONArray row = result.optJSONArray(i); named.put(Json.obj("start", row.optInt(0), "end", row.optInt(1), "text", row.optString(2))); }
        return named;
    }
    private static boolean splitSurrogate(String value, int at) { return at > 0 && at < value.length() && Character.isHighSurrogate(value.charAt(at - 1)) && Character.isLowSurrogate(value.charAt(at)); }
    private static String editorText(String text) {
        StringBuilder value = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char cp = text.charAt(i); value.append(cp <= 0x1f || cp >= 0x7f && cp <= 0x9f ? ' ' : cp);
        }
        return value.toString();
    }
    static boolean whitespaceOnly(String text) {
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) return false;
        }
        return true;
    }
    private static String reading(Object value, boolean silent) {
        String text = rawReading(value);
        if (whitespaceOnly(text) != silent) throw bad(Reason.READING);
        return text;
    }
    private static String rawReading(Object value) {
        if (!(value instanceof String)) throw bad(Reason.READING); String text = (String)value;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch <= 0x1f || ch >= 0x7f && ch <= 0x9f) throw bad(Reason.READING);
            if (Character.isHighSurrogate(ch)) { if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw bad(Reason.READING); }
            else if (Character.isLowSurrogate(ch)) throw bad(Reason.READING);
        }
        return text;
    }
    private static int integer(Object value) {
        if (!(value instanceof Number)) throw bad(Reason.SOURCE); double number = ((Number)value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > Integer.MAX_VALUE) throw bad(Reason.SOURCE);
        return ((Number)value).intValue();
    }
    private static void exact(JSONObject object, String... names) {
        if (object == null || object.length() != names.length) throw bad(Reason.FORMAT); Set<String> allowed = new HashSet<>(Arrays.asList(names));
        for (Iterator<String> keys = object.keys(); keys.hasNext();) if (!allowed.remove(keys.next())) throw bad(Reason.FORMAT);
        if (!allowed.isEmpty()) throw bad(Reason.FORMAT);
    }
    private static String hash(String text) {
        try {
            StringBuilder result = new StringBuilder(64);
            for (byte value : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static ValidationFailure bad(Reason reason) { return new ValidationFailure(reason, -1); }
}
