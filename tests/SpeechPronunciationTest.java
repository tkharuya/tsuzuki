package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;

/** Source/part/ruby validation only; these fixtures do not measure model reading accuracy. */
public final class SpeechPronunciationTest {
    private static int checks;
    private interface Run { void run() throws Exception; }
    private static void yes(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void eq(Object actual, Object expected) { yes(expected.equals(actual), "expected " + expected + " got " + actual); }
    private static void denied(Run run, SpeechPronunciation.Reason reason) throws Exception {
        try { run.run(); throw new AssertionError("invalid pronunciation accepted"); }
        catch (SpeechPronunciation.ValidationFailure expected) {
            eq(expected.reason(), reason); JSONObject diagnostic = expected.diagnostics();
            eq(diagnostic.optString("code"), "invalid_pronunciation");
            eq(diagnostic.optString("stage"), "pronunciation_" + reason.name().toLowerCase(java.util.Locale.ROOT));
            eq(diagnostic.optString("responseShape"), "pronunciation");
            yes(diagnostic.length() == 3 || diagnostic.length() == 4, "diagnostics contain only fixed labels and optional ordinal");
            for (String forbidden : new String[]{"母", "ハハ", "http", "api", "sourceHash", "partId"})
                yes(!diagnostic.toString().contains(forbidden), "diagnostics exclude source, reading and IDs");
        }
    }
    static JSONObject unit(String text) { return Json.obj("text", text, "spokenText", text, "spokenRuby", new JSONArray()); }
    static JSONArray readings(JSONObject unit, String... values) {
        JSONArray parts = SpeechPerformance.parts(unit), result = new JSONArray();
        if (parts.length() != values.length) throw new AssertionError("fixture reading/part count");
        for (int i = 0; i < parts.length(); i++) result.put(Json.obj("partId", parts.optJSONObject(i).optString("partId"), "reading", values[i]));
        return result;
    }
    private static JSONArray copy(JSONArray input) { return Json.arr(Json.copy(Json.obj("items", input)), "items"); }
    private static String repeat(String value, int times) { StringBuilder out = new StringBuilder(); for (int i = 0; i < times; i++) out.append(value); return out.toString(); }
    public static void main(String[] args) throws Exception {
        sourceAndRanges(); strictValidation(); rubyProtection(); japaneseAndManual(); whitespaceAndNewlines(); completion();
        System.out.println("SpeechPronunciationTest: PASS (" + checks + " source/part/ruby/limits/locks/fallback checks; no live GPT accuracy measurement)");
    }
    private static void sourceAndRanges() throws Exception {
        JSONObject unit = unit("母は花を運ぶ。今日は生物を学ぶ。"), original = Json.copy(unit);
        JSONArray proposed = readings(unit, "ハハワハナオハコブ。", "キョーワセーブツオマナブ。");
        JSONObject checked = SpeechPronunciation.validate(unit, proposed);
        eq(unit.toString(), original.toString()); eq(checked.optInt("version"), 1);
        Json.put(unit, "pronunciation", checked); yes(SpeechPronunciation.valid(unit), "source-owned reading valid");
        eq(SpeechPronunciation.text(unit), "ハハワハナオハコブ。キョーワセーブツオマナブ。");
        JSONArray canonical = checked.optJSONArray("parts"), source = SpeechPerformance.parts(unit);
        for (int i = 0; i < source.length(); i++) {
            JSONObject p = source.optJSONObject(i), saved = canonical.optJSONObject(i);
            eq(saved.optString("partId"), p.optString("partId")); eq(saved.optInt("start"), p.optInt("start")); eq(saved.optInt("end"), p.optInt("end"));
            eq(SpeechPronunciation.text(unit, p.optInt("start"), p.optInt("end")), saved.optString("reading"));
        }
        eq(SpeechPronunciation.text(unit, 1, 3), "は花"); eq(SpeechPronunciation.text(unit, 0, 0), "");
        yes(!SpeechPronunciation.signature(unit).isEmpty(), "valid reading changes cache identity");
        String fingerprint = SpeechPronunciation.signature(unit), untouched = unit.toString();
        SpeechPronunciation.template(unit); SpeechPronunciation.text(unit); SpeechPronunciation.signature(unit);
        eq(unit.toString(), untouched); eq(SpeechPronunciation.template(unit).toString(), checked.toString());
        JSONObject reordered = Json.copy(unit); JSONArray reverse = new JSONArray().put(canonical.opt(1)).put(canonical.opt(0));
        Json.put(reordered.optJSONObject("pronunciation"), "parts", reverse);
        yes(!SpeechPronunciation.valid(reordered), "persisted reading order cannot reorder speech");
        eq(SpeechPronunciation.text(reordered), original.optString("spokenText")); eq(SpeechPronunciation.signature(reordered), "");
        eq(SpeechPronunciation.validate(unit, new JSONArray().put(proposed.opt(1)).put(proposed.opt(0))).toString(), checked.toString());
        JSONObject edited = Json.copy(unit); Json.put(edited.optJSONObject("pronunciation").optJSONArray("parts").optJSONObject(0), "reading", "ハハワハナオハコビマス。");
        yes(!fingerprint.equals(SpeechPronunciation.signature(edited)), "manual reading participates in cache key");
        JSONObject stale = Json.copy(unit); Json.put(stale, "spokenText", "母は別の花を運ぶ。");
        yes(!SpeechPronunciation.valid(stale), "changed text invalidates old reading"); eq(SpeechPronunciation.text(stale), stale.optString("spokenText"));
        JSONObject old = unit("旧本文。別文。"); old.remove("spokenRuby");
        eq(SpeechPronunciation.signature(old), ""); eq(SpeechPronunciation.text(old, 0, 4), "旧本文。");
        JSONObject template = SpeechPronunciation.template(old); eq(template.optJSONArray("parts").optJSONObject(0).optString("reading"), "旧本文。");
        yes(!old.has("pronunciation"), "template getter never marks a reading complete");
        try { SpeechPronunciation.text(unit, -1, 3); throw new AssertionError("negative range"); } catch (IllegalArgumentException expected) { checks++; }
    }
    private static void strictValidation() throws Exception {
        JSONObject unit = unit("母は花を運ぶ。今日は生物を学ぶ。"); JSONArray okay = readings(unit, "ハハワハナオハコブ。", "キョーワセーブツオマナブ。");
        denied(() -> SpeechPronunciation.validate(unit, null), SpeechPronunciation.Reason.PART);
        denied(() -> SpeechPronunciation.validate(unit, new JSONArray()), SpeechPronunciation.Reason.PART);
        for (int variant = 0; variant < 5; variant++) {
            JSONArray bad = copy(okay);
            if (variant == 0) Json.put(bad.optJSONObject(0), "partId", "not-a-source-part");
            if (variant == 1) Json.put(bad.optJSONObject(1), "partId", "p0");
            if (variant == 2) Json.put(bad.optJSONObject(0), "text", "本文注入");
            if (variant == 3) bad.optJSONObject(0).remove("reading");
            if (variant == 4) bad.put(0, "not-an-object");
            denied(() -> SpeechPronunciation.validate(unit, bad), variant < 2 ? SpeechPronunciation.Reason.PART : SpeechPronunciation.Reason.FORMAT);
        }
        for (Object value : new Object[]{"", "  ", "ハ\nハ", "ハ\tハ", "\u0000", "\u007f", "\u009f", "\ud800", "\udc00", 42, JSONObject.NULL}) {
            JSONArray bad = copy(okay); Json.put(bad.optJSONObject(0), "reading", value);
            denied(() -> SpeechPronunciation.validate(unit, bad), SpeechPronunciation.Reason.READING);
        }
        JSONObject one = unit("あ");
        Json.put(one, "pronunciation", SpeechPronunciation.validate(one, readings(one, repeat("ア", 2000))));
        yes(SpeechPronunciation.valid(one), "exact 2000 UTF16 units supported");
        denied(() -> SpeechPronunciation.validate(one, readings(one, repeat("ア", 2001))), SpeechPronunciation.Reason.LIMIT);
        JSONObject two = unit("あ、い。");
        denied(() -> SpeechPronunciation.validate(two, readings(two, repeat("ア", 1001), repeat("イ", 1000))), SpeechPronunciation.Reason.LIMIT);
        Json.put(one, "pronunciation", SpeechPronunciation.validateManual(one, readings(one, repeat("😀", 1000))));
        yes(SpeechPronunciation.valid(one), "valid surrogate pairs use UTF16 length");
        denied(() -> SpeechPronunciation.validateManual(one, readings(one, repeat("😀", 1001))), SpeechPronunciation.Reason.LIMIT);
        JSONObject saved = SpeechPronunciation.validate(unit, okay); JSONArray wrong = copy(saved.optJSONArray("parts")); Json.put(wrong.optJSONObject(0), "end", 1);
        denied(() -> SpeechPronunciation.validateManual(unit, wrong), SpeechPronunciation.Reason.SOURCE);
        for (Object version : new Object[]{2, "1", 1.1, JSONObject.NULL}) {
            JSONObject wrongVersion = Json.copy(unit), annotation = Json.copy(saved); Json.put(annotation, "version", version); Json.put(wrongVersion, "pronunciation", annotation);
            yes(!SpeechPronunciation.valid(wrongVersion), "version strictly checked");
        }
        JSONObject invalidHash = Json.copy(unit), annotation = Json.copy(saved); Json.put(annotation, "sourceHash", "wrong"); Json.put(invalidHash, "pronunciation", annotation);
        yes(!SpeechPronunciation.valid(invalidHash), "source hash required");
    }
    private static void rubyProtection() throws Exception {
        JSONObject ruby = unit("生きものは動く。"); Json.put(ruby, "spokenRuby", new JSONArray().put(Json.obj("start", 0, "end", 4, "text", "生きもの")));
        JSONObject input = SpeechPronunciation.modelParts(ruby).optJSONObject(0); String token = input.optJSONArray("rubyTokens").optJSONObject(0).optString("token");
        eq(input.optString("readingSource"), token + "は動く。");
        Json.put(ruby, "pronunciation", SpeechPronunciation.validate(ruby, readings(ruby, token + "ワウゴク。")));
        eq(SpeechPronunciation.text(ruby), "生きものワウゴク。"); yes(SpeechPronunciation.valid(ruby), "author ruby Han spelling exempt");
        denied(() -> SpeechPronunciation.validate(ruby, readings(ruby, "イキモノワウゴク。")), SpeechPronunciation.Reason.RUBY);
        denied(() -> SpeechPronunciation.validate(ruby, readings(ruby, token + token + "ワウゴク。")), SpeechPronunciation.Reason.RUBY);
        denied(() -> SpeechPronunciation.validate(ruby, readings(ruby, token + "ワ動く。")), SpeechPronunciation.Reason.READING);
        Json.put(ruby, "pronunciation", SpeechPronunciation.validateManual(ruby, readings(ruby, "セーブツワウゴク。")));
        eq(SpeechPronunciation.text(ruby), "セーブツワウゴク。");
        JSONObject crossing = unit("あお、ばは来た。"); Json.put(crossing, "spokenRuby", new JSONArray().put(Json.obj("start", 0, "end", 4, "text", "あお、ば")));
        JSONArray inputParts = SpeechPronunciation.modelParts(crossing); eq(inputParts.length(), 2);
        String first = inputParts.optJSONObject(0).optJSONArray("rubyTokens").optJSONObject(0).optString("token");
        String second = inputParts.optJSONObject(1).optJSONArray("rubyTokens").optJSONObject(0).optString("token");
        Json.put(crossing, "pronunciation", SpeechPronunciation.validate(crossing, readings(crossing, first, second + "ワキタ。")));
        eq(SpeechPronunciation.text(crossing), "あお、ばワキタ。");
        eq(SpeechPronunciation.text(crossing, 0, 3), "あお、"); eq(SpeechPronunciation.text(crossing, 3, 8), "ばワキタ。");
        JSONObject pair = unit("あおばはあかね。"); Json.put(pair, "spokenRuby", new JSONArray().put(Json.obj("start", 0, "end", 3, "text", "あおば")).put(Json.obj("start", 4, "end", 7, "text", "あかね")));
        JSONArray tokens = SpeechPronunciation.modelParts(pair).optJSONObject(0).optJSONArray("rubyTokens");
        denied(() -> SpeechPronunciation.validate(pair, readings(pair, tokens.optJSONObject(1).optString("token") + "ワ" + tokens.optJSONObject(0).optString("token") + "。")), SpeechPronunciation.Reason.RUBY);
        JSONObject removedRuby = Json.copy(ruby); Json.put(removedRuby, "spokenRuby", new JSONArray());
        yes(!SpeechPronunciation.valid(removedRuby), "ruby scope is part of pronunciation source identity");
        JSONObject malformedRuby = unit("あおば"); Json.put(malformedRuby, "spokenRuby", new JSONArray().put(Json.obj("start", 0, "end", 2, "text", "別物")));
        denied(() -> SpeechPronunciation.modelParts(malformedRuby), SpeechPronunciation.Reason.SOURCE);
    }
    private static void japaneseAndManual() throws Exception {
        JSONObject japanese = unit("母は花を運ぶ。");
        denied(() -> SpeechPronunciation.validate(japanese, readings(japanese, "母は花を運ぶ。")), SpeechPronunciation.Reason.READING);
        JSONObject name = unit("青葉");
        denied(() -> SpeechPronunciation.validate(name, readings(name, "青葉"), true), SpeechPronunciation.Reason.READING);
        Json.put(name, "pronunciation", SpeechPronunciation.validate(name, readings(name, "アオバ"), true)); eq(SpeechPronunciation.text(name), "アオバ");
        JSONObject chinese = unit("今天是星期一。"), english = unit("Hello, mother!");
        Json.put(chinese, "pronunciation", SpeechPronunciation.validate(chinese, readings(chinese, "今天是星期一。"), false)); eq(SpeechPronunciation.text(chinese), "今天是星期一。");
        Json.put(english, "pronunciation", SpeechPronunciation.validate(english, readings(english, "Hello, ", "mother!"))); eq(SpeechPronunciation.text(english), "Hello, mother!");
        Json.put(japanese, "pronunciation", SpeechPronunciation.validateManual(japanese, readings(japanese, "母はハナを運ぶ。")));
        yes(SpeechPronunciation.valid(japanese), "manual mixed text remains permitted"); eq(SpeechPronunciation.text(japanese), "母はハナを運ぶ。");
        yes(SpeechPronunciation.hasKana("母は花"), "Japanese kana evidence"); yes(SpeechPronunciation.hasKana("ﾊﾊﾜ"), "halfwidth kana evidence");
        yes(!SpeechPronunciation.hasKana("Hello 中文"), "Han alone does not determine language");
    }
    private static void completion() {
        JSONObject unit = unit("母は花を運ぶ。"); Json.put(unit, "analyzed", true);
        JSONObject plan = Json.obj("units", new JSONArray().put(unit), "analysisDetail", "standard");
        eq(SpeechPronunciation.completedCount(plan), 1); eq(SpeechPronunciation.completedCount(plan, true), 0);
        Json.put(plan, "pronunciationVersion", 1); eq(SpeechPronunciation.completedCount(plan), 0);
        Json.put(unit, "pronunciation", SpeechPronunciation.validate(unit, readings(unit, "ハハワハナオハコブ。")));
        eq(SpeechPronunciation.completedCount(plan), 1); Json.put(plan, "analysisDetail", "detailed"); eq(SpeechPronunciation.completedCount(plan), 0);
        Json.put(unit, "performance", SpeechPerformance.validate(unit, SpeechPerformanceTest.annotations(unit), false)); eq(SpeechPronunciation.completedCount(plan), 1);
        unit.remove("performance"); Json.put(unit, "locked", true); Json.put(unit, "analyzed", false); eq(SpeechPronunciation.completedCount(plan), 1);
        unit.remove("pronunciation"); Json.put(unit, "pronunciationLocked", true); eq(SpeechPronunciation.completedCount(plan), 0);
        eq(SpeechPronunciation.completedCount(plan, false), 1); eq(SpeechPronunciation.completedCount(null), 0);
    }
    private static void whitespaceAndNewlines() throws Exception {
        JSONObject line = unit("母は花を運ぶ。\n今日は生物を学ぶ。"), template = SpeechPronunciation.template(line);
        eq(template.optJSONArray("parts").optJSONObject(0).optString("reading"), "母は花を運ぶ。 ");
        Json.put(line, "pronunciation", SpeechPronunciation.validateManual(line, template.optJSONArray("parts")));
        yes(SpeechPronunciation.valid(line), "newline source can keep an unedited template part on manual save");
        eq(line.optString("spokenText"), "母は花を運ぶ。\n今日は生物を学ぶ。");
        for (String whitespace : new String[]{" ", "　", "\n", "\t", " \r\n　"}) {
            JSONObject silent = unit(whitespace); JSONArray parts = SpeechPerformance.parts(silent), values = new JSONArray();
            for (int i = 0; i < parts.length(); i++) values.put(Json.obj("partId", parts.optJSONObject(i).optString("partId"), "reading", ""));
            Json.put(silent, "pronunciation", SpeechPronunciation.validate(silent, values));
            yes(SpeechPronunciation.valid(silent), "whitespace-only source permits silence"); eq(SpeechPronunciation.text(silent), "");
            Json.put(silent, "pronunciation", SpeechPronunciation.validateManual(silent, SpeechPronunciation.template(unit(whitespace)).optJSONArray("parts")));
            yes(SpeechPronunciation.valid(silent), "whitespace source editor template is saveable");
            JSONArray invented = copy(values); Json.put(invented.optJSONObject(0), "reading", "ア");
            denied(() -> SpeechPronunciation.validate(silent, invented), SpeechPronunciation.Reason.READING);
        }
        JSONObject ordinary = unit("は");
        denied(() -> SpeechPronunciation.validate(ordinary, readings(ordinary, "")), SpeechPronunciation.Reason.READING);
        Json.put(ordinary, "pronunciation", SpeechPronunciation.validate(ordinary, readings(ordinary, "ハ")));
        yes(SpeechPronunciation.valid(ordinary), "single-letter lexical source is not treated as silence");
    }
}
