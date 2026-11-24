package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;

/** Text integrity and validated acting metadata; no model-inference accuracy claim. */
public final class SpeechPerformanceTest {
    private static int checks;
    private interface Task { void run() throws Exception; }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void eq(Object value, Object expected) { check(expected.equals(value), String.valueOf(value) + " != " + expected); }
    private static void rejects(Task task, String message) throws Exception {
        try { task.run(); throw new AssertionError("accepted " + message); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    static JSONObject acting(String id) {
        return Json.obj("partId", id, "emotion", "determination", "delivery", "firm", "intensity", .6,
                "rate", 1.05, "pitch", 1.02, "intonation", 1.15, "volume", 1.08,
                "pauseAfterMs", 180, "ending", "fall", "emphasis", .3, "confidence", .85);
    }
    static JSONArray annotations(JSONObject unit) {
        JSONArray result = new JSONArray(), parts = SpeechPerformance.parts(unit);
        for (int i = 0; i < parts.length(); i++) result.put(acting(parts.optJSONObject(i).optString("partId")));
        return result;
    }
    private static JSONArray copy(JSONArray source) { return Json.arr(Json.copy(Json.obj("items", source)), "items"); }

    public static void main(String[] args) throws Exception {
        for (String text : new String[]{"「いや、待って……。もう迷わない！」", "😀😃😄、👩‍🚀が来る。次へ。",
                "「えっ！？……」  次の話。", "3.14と1,000の値。次の文。", "改行\n　次行\r\n最終行。", "句点のない文章", ""}) {
            JSONObject unit = Json.obj("text", "表示本文", "spokenText", text);
            String before = unit.toString(); JSONArray parts = SpeechPerformance.parts(unit);
            StringBuilder restored = new StringBuilder(); int end = 0;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i); int start = p.optInt("start");
                eq(start, end); end = p.optInt("end");
                eq(p.optString("partId"), "p" + i); check(end > start, "no empty source part");
                eq(p.optString("text"), text.substring(start, end)); restored.append(p.optString("text"));
                check(start == 0 || !Character.isLowSurrogate(text.charAt(start)), "start respects emoji surrogate");
                check(end == text.length() || !Character.isLowSurrogate(text.charAt(end)), "end respects emoji surrogate");
            }
            eq(end, text.length()); eq(restored.toString(), text); eq(unit.toString(), before);
            eq(parts.toString(), SpeechPerformance.parts(unit).toString());
        }
        JSONObject numbers = Json.obj("spokenText", "3.14と1,000の値。次の文。");
        eq(SpeechPerformance.parts(numbers).optJSONObject(0).optString("text"), "3.14と1,000の値。");
        JSONObject quotes = Json.obj("spokenText", "「えっ！？……」  次の話。");
        eq(SpeechPerformance.parts(quotes).optJSONObject(0).optString("text"), "「えっ！？……」  ");

        JSONObject chapter = Json.obj("url", "local://ruby", "paragraphs", new JSONArray().put(Json.obj(
                "text", "「青葉、出発だ。」", "html", "「<ruby>青葉<rt>あおば</rt></ruby>、出発だ。」")));
        JSONObject rubyUnit = Json.arr(SpeechPlan.create("book", chapter, null), "units").optJSONObject(0);
        check(rubyUnit.optString("spokenText").contains("あおば"), "real SpeechPlan resolved ruby");
        eq(SpeechPerformance.parts(rubyUnit).optJSONObject(0).optString("text"), "「あおば、");
        String original = rubyUnit.toString(); JSONArray returned = annotations(rubyUnit);
        JSONObject performance = SpeechPerformance.validate(rubyUnit, returned, true);
        eq(rubyUnit.toString(), original); check(!performance.toString().contains("あおば"), "metadata stores no generated text");
        Json.put(rubyUnit, "performance", performance); Json.put(rubyUnit, "analyzed", true);
        check(SpeechPerformance.valid(rubyUnit), "reviewed metadata matches actual spoken source");
        check(!SpeechPerformance.signature(rubyUnit).isEmpty(), "reviewed performance participates in cache identity");
        String signature = SpeechPerformance.signature(rubyUnit);
        JSONObject manual = SpeechPerformance.validate(rubyUnit, performance.optJSONArray("parts"), true);
        eq(manual.toString(), performance.toString());
        JSONArray reversed = new JSONArray();
        for (int i = returned.length() - 1; i >= 0; i--) reversed.put(returned.opt(i));
        eq(SpeechPerformance.validate(rubyUnit, reversed, true).toString(), performance.toString());
        JSONObject reordered = Json.copy(rubyUnit); JSONArray savedReversed = new JSONArray();
        for (int i = performance.optJSONArray("parts").length() - 1; i >= 0; i--)
            savedReversed.put(performance.optJSONArray("parts").opt(i));
        Json.put(reordered.optJSONObject("performance"), "parts", savedReversed);
        check(!SpeechPerformance.valid(reordered), "persisted playback parts cannot be reordered");
        eq(SpeechPerformance.signature(reordered), "");
        Json.put(rubyUnit, "performance", SpeechPerformance.validate(rubyUnit, returned, false));
        check(SpeechPerformance.valid(rubyUnit) && !SpeechPerformance.signature(rubyUnit).isEmpty(), "complete one-pass metadata explicitly records reviewed=false and remains playable");
        Json.put(rubyUnit, "performance", performance);
        JSONObject edited = Json.copy(rubyUnit); Json.put(edited, "spokenText", "「あおば、別の文。」");
        check(!SpeechPerformance.valid(edited), "changed ruby or text invalidates old performance");
        for (Object bad : new Object[]{2, "1", null}) {
            JSONObject wrong = Json.copy(rubyUnit); Json.put(wrong.optJSONObject("performance"), "version", bad);
            check(!SpeechPerformance.valid(wrong), "version must be exact supported integer");
        }
        JSONObject wrongHash = Json.copy(rubyUnit); Json.put(wrongHash.optJSONObject("performance"), "sourceHash", "not-the-source");
        check(!SpeechPerformance.valid(wrongHash), "source hash is mandatory");
        JSONObject badReviewed = Json.copy(rubyUnit); Json.put(badReviewed.optJSONObject("performance"), "reviewed", "true");
        check(!SpeechPerformance.valid(badReviewed), "reviewed cannot be a truthy string");
        JSONArray duplicate = copy(returned); duplicate.put(0, duplicate.opt(1));
        rejects(() -> SpeechPerformance.validate(rubyUnit, duplicate, true), "duplicate part");
        JSONArray missing = copy(returned); missing.remove(0);
        rejects(() -> SpeechPerformance.validate(rubyUnit, missing, true), "missing part");
        JSONArray unknown = copy(returned); Json.put(unknown.optJSONObject(0), "partId", "p-not-in-source");
        rejects(() -> SpeechPerformance.validate(rubyUnit, unknown, true), "foreign part");
        JSONArray textOverride = copy(returned); Json.put(textOverride.optJSONObject(0), "text", "本文を書き換える");
        rejects(() -> SpeechPerformance.validate(rubyUnit, textOverride, true), "model text injection");
        for (String field : new String[]{"emotion", "delivery", "ending", "confidence", "volume"}) {
            JSONArray incomplete = copy(returned); incomplete.optJSONObject(0).remove(field);
            rejects(() -> SpeechPerformance.validate(rubyUnit, incomplete, true), "missing acting field " + field);
        }
        Object[][] badNumbers = {{"intensity", -0.01}, {"emphasis", 1.01}, {"rate", .69}, {"rate", 1.41},
                {"pitch", .79}, {"pitch", 1.21}, {"intonation", 1.61}, {"volume", .74},
                {"pauseAfterMs", 1201}, {"pauseAfterMs", 10.5}, {"confidence", "0.9"}, {"rate", new BigDecimal("1e9999")}};
        for (Object[] test : badNumbers) {
            JSONArray invalid = copy(returned); Json.put(invalid.optJSONObject(0), (String)test[0], test[1]);
            rejects(() -> SpeechPerformance.validate(rubyUnit, invalid, true), "range/type " + test[0]);
        }
        for (String field : new String[]{"emotion", "delivery", "ending"}) {
            JSONArray invalid = copy(returned); Json.put(invalid.optJSONObject(0), field, "invented");
            rejects(() -> SpeechPerformance.validate(rubyUnit, invalid, true), "unknown " + field);
        }
        JSONArray offsets = copy(performance.optJSONArray("parts")); Json.put(offsets.optJSONObject(0), "end", 1);
        rejects(() -> SpeechPerformance.validate(rubyUnit, offsets, true), "changed native offset");
        JSONArray offsetString = copy(performance.optJSONArray("parts")); Json.put(offsetString.optJSONObject(0), "start", "0");
        rejects(() -> SpeechPerformance.validate(rubyUnit, offsetString, true), "offset string");
        JSONArray changed = copy(returned); Json.put(changed.optJSONObject(0), "emphasis", .8);
        JSONObject cacheChanged = Json.copy(rubyUnit); Json.put(cacheChanged, "performance", SpeechPerformance.validate(rubyUnit, changed, true));
        check(!signature.equals(SpeechPerformance.signature(cacheChanged)), "part acting edit invalidates cached sound");

        JSONObject locked = Json.obj("locked", true, "analyzed", true), old = Json.obj("analyzed", true), fresh = new JSONObject();
        JSONObject plan = Json.obj("units", new JSONArray().put(rubyUnit).put(locked).put(old).put(fresh), "analysisDetail", "detailed");
        eq(SpeechPerformance.completedCount(plan), 2);
        Json.put(plan, "analysisDetail", "standard"); eq(SpeechPerformance.completedCount(plan), 3);
        Json.put(rubyUnit, "analyzed", false); Json.put(plan, "analysisDetail", "detailed");
        eq(SpeechPerformance.completedCount(plan), 1);
        System.out.println("SpeechPerformanceTest: PASS (" + checks + " original text, ruby, part identity, strict metadata and cache checks; no AI accuracy benchmark)");
    }
}
