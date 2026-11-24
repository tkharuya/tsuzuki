package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, lossless chapter segmentation. The AI never owns the text.
 * start/end are UTF-16 offsets in the original paragraph (as in the reader JS).
 */
public final class SpeechPlan {
    public static final int VERSION = 1;
    public static final int MAX_UNIT_CHARS = 350;
    public static final String DEFAULT_MODEL = "gpt-6-luna";
    private static final Set<String> VOICES = new HashSet<>(Arrays.asList(
            "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar"));
    private SpeechPlan() {}

    public static JSONObject create(String bookId, JSONObject chapter, JSONObject settings) {
        if (chapter == null) throw new IllegalArgumentException("読み上げる本文がありません。");
        boolean useRuby = settings == null || settings.optBoolean("useRuby", true);
        String hash = inputHash(chapter, useRuby);
        JSONArray paragraphs = Json.arr(chapter, "paragraphs"), units = new JSONArray();
        List<Quote> quotes = new ArrayList<>();
        for (int p = 0; p < paragraphs.length(); p++) {
            JSONObject paragraph = paragraphs.optJSONObject(p);
            String text = paragraph == null ? paragraphs.optString(p, "") : paragraph.optString("text", "");
            List<Ruby> ruby = useRuby && paragraph != null ? rubySpans(text, paragraph.optString("html", "")) : new ArrayList<Ruby>();
            int start = 0;
            String kind = quoteKind(quotes);
            for (int i = 0; i < text.length();) {
                Ruby span = rubyAt(ruby, i);
                if (span != null) { i = span.end; continue; }
                char c = text.charAt(i);
                char close = closing(c);
                boolean escaped = isEscaped(text, i);
                if (!escaped && !quotes.isEmpty() && c == quotes.get(quotes.size() - 1).close) {
                    quotes.remove(quotes.size() - 1);
                    String next = quoteKind(quotes);
                    if (!next.equals(kind)) {
                        appendRun(units, hash, p, text, start, i + 1, kind, ruby);
                        start = i + 1; kind = next;
                    }
                } else if (!escaped && close != 0) {
                    String next = c == '(' || c == '（' ? "thought" : "dialogue";
                    if (!next.equals(kind)) {
                        appendRun(units, hash, p, text, start, i, kind, ruby);
                        start = i; kind = next;
                    }
                    quotes.add(new Quote(close, next));
                }
                i += Character.charCount(text.codePointAt(i));
            }
            appendRun(units, hash, p, text, start, text.length(), kind, ruby);
        }
        return Json.obj("version", VERSION, "bookId", bookId == null ? "" : bookId,
                "chapterUrl", chapter.optString("url", chapter.optString("chapterUrl")),
                "title", chapter.optString("title"), "chapterIndex", chapter.optInt("index"),
                "inputHash", hash, "useRuby", useRuby, "paragraphCount", paragraphs.length(),
                "units", units, "totalUnits", units.length(), "analyzedUnits", 0,
                "analysisStatus", "idle", "analysisModel", "", "error", "", "batchIndex", 0);
    }

    /** Rebuild rather than reusing a plan if either original text or ruby changed. */
    public static boolean compatible(JSONObject plan, JSONObject chapter) {
        return plan != null && chapter != null && plan.optInt("version") == VERSION
                && plan.optString("chapterUrl").equals(chapter.optString("url", chapter.optString("chapterUrl")))
                && plan.optString("inputHash").equals(inputHash(chapter, plan.optBoolean("useRuby", true)));
    }

    /** Attach author-ruby boundaries to an unchanged older plan without replacing its annotations. */
    public static boolean refreshRubyMetadata(JSONObject plan, JSONObject chapter) {
        if (!compatible(plan, chapter)) throw new IllegalArgumentException("本文が変わっています。章を開き直してください。");
        JSONArray units = Json.arr(plan, "units"); boolean missing = false;
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.optJSONObject(i);
            if (unit != null && !(unit.opt("spokenRuby") instanceof JSONArray)) missing = true;
        }
        if (!missing) return false;
        JSONObject generated = create(plan.optString("bookId"), chapter, Json.obj("useRuby", plan.optBoolean("useRuby", true)));
        Map<String, JSONObject> fresh = new LinkedHashMap<>();
        JSONArray rebuilt = Json.arr(generated, "units");
        if (rebuilt.length() != units.length()) throw new IllegalArgumentException("読み上げ区間と本文が一致しません。章を開き直してください。");
        for (int i = 0; i < rebuilt.length(); i++) {
            JSONObject unit = rebuilt.optJSONObject(i); fresh.put(unit.optString("unitId"), unit);
        }
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.optJSONObject(i), source = unit == null ? null : fresh.get(unit.optString("unitId"));
            if (source == null || !source.optString("spokenText").equals(unit.optString("spokenText")))
                throw new IllegalArgumentException("読み上げ区間と本文が一致しません。章を開き直してください。");
        }
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.optJSONObject(i);
            if (!(unit.opt("spokenRuby") instanceof JSONArray))
                Json.put(unit, "spokenRuby", Json.arr(fresh.get(unit.optString("unitId")), "spokenRuby"));
        }
        return true;
    }

    public static String inputHash(JSONObject chapter, boolean useRuby) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hashField(digest, "speech-plan-" + VERSION + ":ruby=" + useRuby);
            hashField(digest, chapter.optString("url", chapter.optString("chapterUrl")));
            JSONArray paragraphs = Json.arr(chapter, "paragraphs");
            hashField(digest, Integer.toString(paragraphs.length()));
            for (int i = 0; i < paragraphs.length(); i++) {
                JSONObject p = paragraphs.optJSONObject(i);
                hashField(digest, p == null ? paragraphs.optString(i, "") : p.optString("text", ""));
                // A ruby edit invalidates the plan even when ruby is temporarily disabled.
                hashField(digest, p == null ? "" : p.optString("html", ""));
            }
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("本文の識別に失敗しました。", e); }
    }

    private static void hashField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':'); digest.update(bytes); digest.update((byte) '\n');
    }

    private static final class Quote {
        final char close; final String kind;
        Quote(char close, String kind) { this.close = close; this.kind = kind; }
    }
    private static String quoteKind(List<Quote> stack) { return stack.isEmpty() ? "narration" : stack.get(stack.size() - 1).kind; }
    private static char closing(char c) {
        switch (c) {
            case '「': return '」'; case '『': return '』'; case '“': return '”'; case '"': return '"';
            case '(' : return ')'; case '（': return '）'; default: return 0;
        }
    }
    private static boolean isEscaped(String text, int index) {
        int count = 0; for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) count++;
        return count % 2 == 1;
    }

    private static void appendRun(JSONArray units, String hash, int paragraph, String text,
            int start, int end, String kind, List<Ruby> ruby) {
        while (start < end) {
            int cut = chooseCut(text, start, end, ruby);
            String original = text.substring(start, cut);
            String spoken = spokenText(text, start, cut, ruby);
            boolean narration = "narration".equals(kind);
            units.put(Json.obj("unitId", "u_" + hash.substring(0, 16) + "_" + paragraph + "_" + start + "_" + cut,
                    "index", units.length(), "paragraph", paragraph, "start", start, "end", cut,
                    "text", original, "spokenText", spoken, "spokenRuby", spokenRuby(start, cut, ruby), "kind", kind,
                    "speakerId", narration ? "narrator" : "unknown", "emotion", "neutral",
                    "intensity", 0.0, "rate", 1.0, "pitch", 1.0, "confidence", 0.0,
                    "analyzed", false, "locked", false));
            start = cut;
        }
    }

    private static int chooseCut(String text, int start, int end, List<Ruby> ruby) {
        int ceiling = Math.min(end, start + MAX_UNIT_CHARS);
        if (ceiling < end && Character.isLowSurrogate(text.charAt(ceiling)) && Character.isHighSurrogate(text.charAt(ceiling - 1))) ceiling--;
        Ruby crossing = rubyAt(ruby, ceiling);
        if (crossing != null && ceiling > crossing.start) ceiling = crossing.start > start ? crossing.start : crossing.end;
        ceiling = Math.min(end, ceiling);
        // Stop at a natural sentence/word boundary without discarding whitespace.
        int sentence = -1, word = -1;
        for (int i = start; i < ceiling;) {
            Ruby r = rubyAt(ruby, i);
            if (r != null) { i = r.end; continue; }
            int cp = text.codePointAt(i), next = i + Character.charCount(cp);
            if (next - start >= 40 && (cp == '。' || cp == '！' || cp == '？' || cp == '!' || cp == '?' || cp == '\n' || cp == '.')) sentence = next;
            if (next - start >= 80 && (Character.isWhitespace(cp) || cp == '、' || cp == ',' || cp == ';' || cp == '；')) word = next;
            i = next;
        }
        int cut = ceiling < end ? (sentence > start ? sentence : word > start ? word : ceiling) : ceiling;
        // A final closing quote belongs to the preceding sentence.
        if (cut < end && end - cut <= 2 && (text.charAt(cut) == '」' || text.charAt(cut) == '』' || text.charAt(cut) == '”' || text.charAt(cut) == '"' || text.charAt(cut) == '）' || text.charAt(cut) == ')')) cut = end;
        // Ruby readings may be longer than the written form. Reduce only at safe boundaries.
        while (cut > start + 1 && spokenText(text, start, cut, ruby).length() > MAX_UNIT_CHARS) {
            int shorter = text.offsetByCodePoints(cut, -1);
            Ruby r = rubyAt(ruby, shorter);
            if (r != null) shorter = r.start;
            if (shorter <= start) break; // A single indivisible ruby token is never dropped.
            cut = shorter;
        }
        return Math.max(start + Character.charCount(text.codePointAt(start)), cut);
    }

    private static String spokenText(String text, int start, int end, List<Ruby> spans) {
        StringBuilder out = new StringBuilder(); int at = start;
        // Locate the first possible overlap. Large ruby-heavy paragraphs must not rescan
        // every earlier ruby for every subsequent speech unit.
        int low = 0, high = spans.size();
        while (low < high) { int mid = (low + high) >>> 1; if (spans.get(mid).end <= start) low = mid + 1; else high = mid; }
        for (int i = low; i < spans.size(); i++) {
            Ruby r = spans.get(i);
            if (r.start >= end) break;
            if (r.start < start || r.end > end) continue;
            out.append(text, at, r.start).append(r.reading); at = r.end;
        }
        return out.append(text, at, end).toString();
    }
    private static JSONArray spokenRuby(int start, int end, List<Ruby> spans) {
        JSONArray result = new JSONArray(); int shift = -start;
        int low = 0, high = spans.size();
        while (low < high) { int mid = (low + high) >>> 1; if (spans.get(mid).end <= start) low = mid + 1; else high = mid; }
        for (int i = low; i < spans.size(); i++) {
            Ruby r = spans.get(i);
            if (r.start >= end) break;
            if (r.start < start || r.end > end) continue;
            int at = r.start + shift;
            result.put(Json.obj("start", at, "end", at + r.reading.length(), "text", r.reading));
            shift += r.reading.length() - (r.end - r.start);
        }
        return result;
    }
    private static final class Ruby {
        final int start, end; final String reading;
        Ruby(int start, int end, String reading) { this.start = start; this.end = end; this.reading = reading; }
    }
    private static Ruby rubyAt(List<Ruby> spans, int index) {
        int low = 0, high = spans.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1; Ruby r = spans.get(mid);
            if (index < r.start) high = mid - 1;
            else if (index >= r.end) low = mid + 1;
            else return r;
        }
        return null;
    }

    /** Reads only the app's sanitized ruby/rt/rp markup; mismatches fall back to original text. */
    private static List<Ruby> rubySpans(String original, String html) {
        List<Ruby> spans = new ArrayList<>();
        if (html.isEmpty() || !html.toLowerCase(Locale.ROOT).contains("<ruby>")) return spans;
        StringBuilder base = new StringBuilder(), reading = new StringBuilder();
        int rubyDepth = 0, rtDepth = 0, rpDepth = 0, rubyStart = -1; boolean invalid = false;
        for (int i = 0; i < html.length();) {
            if (html.charAt(i) == '<') {
                int e = html.indexOf('>', i + 1);
                if (e < 0) { invalid = true; break; }
                String tag = html.substring(i, e + 1).toLowerCase(Locale.ROOT);
                if ("<ruby>".equals(tag)) {
                    if (rubyDepth++ == 0) { rubyStart = base.length(); reading.setLength(0); }
                    else invalid = true;
                } else if ("</ruby>".equals(tag)) {
                    if (rubyDepth != 1 || rtDepth != 0 || rpDepth != 0) { invalid = true; break; }
                    rubyDepth = 0;
                    if (base.length() > rubyStart && reading.length() > 0) spans.add(new Ruby(rubyStart, base.length(), reading.toString()));
                } else if ("<rt>".equals(tag)) rtDepth++;
                else if ("</rt>".equals(tag)) { if (--rtDepth < 0) invalid = true; }
                else if ("<rp>".equals(tag)) rpDepth++;
                else if ("</rp>".equals(tag)) { if (--rpDepth < 0) invalid = true; }
                else { invalid = true; break; }
                i = e + 1; continue;
            }
            int next = html.indexOf('<', i); if (next < 0) next = html.length();
            String value = decodeEntities(html.substring(i, next));
            if (rtDepth > 0 && rubyDepth > 0 && rpDepth == 0) reading.append(value);
            else if (rtDepth == 0 && rpDepth == 0) base.append(value);
            i = next;
        }
        if (invalid || rubyDepth != 0 || rtDepth != 0 || rpDepth != 0) return new ArrayList<>();
        String plain = base.toString().replace("\r\n", "\n").replace('\r', '\n');
        if (!plain.equals(base.toString())) return new ArrayList<>(); // Offsets must match, not merely approximate.
        int left = 0, right = plain.length();
        while (left < right && layoutSpace(plain.charAt(left))) left++;
        while (right > left && layoutSpace(plain.charAt(right - 1))) right--;
        if (original.equals(plain)) return spans;
        if (!original.equals(plain.substring(left, right))) return new ArrayList<>();
        List<Ruby> adjusted = new ArrayList<>();
        for (Ruby r : spans) if (r.start >= left && r.end <= right) adjusted.add(new Ruby(r.start - left, r.end - left, r.reading));
        return adjusted;
    }
    private static boolean layoutSpace(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }
    private static String decodeEntities(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length();) {
            if (text.charAt(i) == '&') {
                int e = text.indexOf(';', i + 1);
                if (e > i && e - i <= 14) {
                    String entity = text.substring(i + 1, e); Integer cp = null;
                    if ("amp".equals(entity)) cp = 38; else if ("lt".equals(entity)) cp = 60;
                    else if ("gt".equals(entity)) cp = 62; else if ("quot".equals(entity)) cp = 34;
                    else if ("apos".equals(entity)) cp = 39; else if ("nbsp".equals(entity)) cp = 160;
                    else if (entity.startsWith("#")) {
                        try { cp = entity.startsWith("#x") || entity.startsWith("#X") ? Integer.parseInt(entity.substring(2), 16) : Integer.parseInt(entity.substring(1)); }
                        catch (NumberFormatException ignored) { }
                    }
                    if (cp != null && Character.isValidCodePoint(cp) && !(cp >= 0xd800 && cp <= 0xdfff)) { out.appendCodePoint(cp); i = e + 1; continue; }
                }
            }
            out.append(text.charAt(i++));
        }
        return out.toString();
    }

    /** Merge model metadata while retaining each established voice and every manual edit. */
    public static JSONArray mergeCast(JSONArray existing, JSONArray incoming) {
        return CastRegistry.mergeAnalysis(existing, incoming, OfflineVoices.defaultVoice());
    }

    static JSONObject defaultSpeaker(String id) {
        return Json.obj("id", id, "name", "narrator".equals(id) ? "地の文" : "未特定",
                "aliases", new JSONArray(), "gender", "neutral", "tier", "narrator".equals(id) ? "narrator" : "extra",
                "voice", "marin", "deviceVoice", "", "style", "", "rate", 1.0, "pitch", 1.0, "manual", false,
                "offlineVoice", OfflineVoices.defaultVoice(), "voiceLocked", true, "profileLocked", false);
    }
    static JSONObject cleanSpeaker(JSONObject p) {
        return CastRegistry.cleanAnalysis(p, OfflineVoices.defaultVoice());
    }
    static String voice(String value) { return VOICES.contains(value) ? value : "marin"; }
    static String allowed(String value, String fallback, String... valid) {
        for (String s : valid) if (s.equals(value)) return value; return fallback;
    }
    static double clamp(double value, double min, double max) { return Double.isNaN(value) || Double.isInfinite(value) ? 1 : Math.max(min, Math.min(max, value)); }
    static String normalizedName(String name) { return Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    public static int analyzedCount(JSONObject plan) {
        int count = 0; JSONArray units = Json.arr(plan, "units");
        for (int i = 0; i < units.length(); i++) { JSONObject unit = units.optJSONObject(i); if (unit != null && (unit.optBoolean("analyzed") || unit.optBoolean("locked"))) count++; }
        return count;
    }
}
