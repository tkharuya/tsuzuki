package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;

/** Chapter identities and bounded incremental queue rules, with self-authored data only. */
public final class SpeechAnalysisRangeTest {
    private static int checks;
    private interface Run { void run() throws Exception; }
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void denied(Run run) throws Exception { try { run.run(); throw new AssertionError("invalid range accepted"); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; } }
    private static JSONObject book(boolean complete, int total) { return Json.obj("id", "fixture", "url", "https://kakuyomu.jp/works/100", "source", "kakuyomu", "tocComplete", complete, "total", total); }
    private static String url(int index) { return "https://kakuyomu.jp/works/100/episodes/" + (1000 + index); }
    private static JSONObject meta(int index) { return Json.obj("index", index, "url", url(index), "title", "自作" + (index + 1)); }
    private static JSONArray catalog(int... indices) { JSONArray out = new JSONArray(); for (int index : indices) out.put(meta(index)); return out; }
    private static JSONObject chapter(int index, String next) { return Json.obj("bookId", "fixture", "url", url(index), "index", index, "title", "自作" + (index + 1), "paragraphs", new JSONArray().put(Json.obj("text", "自作の検査本文。")), "nextUrl", next); }
    private static JSONObject complete(JSONObject chapter) {
        JSONObject plan = SpeechPlan.create("fixture", chapter, null);
        for (int i = 0; i < Json.arr(plan, "units").length(); i++) Json.put(Json.arr(plan, "units").optJSONObject(i), "analyzed", true);
        Json.put(plan, "analysisStatus", "complete"); return plan;
    }
    public static void main(String[] args) throws Exception {
        JSONArray all = new JSONArray(); for (int i = 0; i < 12; i++) all.put(meta(i));
        JSONObject range = SpeechAnalysisRange.create(book(true, 12), all, 10, 10);
        eq(range.optLong("requestedCount"), 10L); eq(range.optLong("count"), 3L); eq(range.optLong("endNumber"), 12L);
        yes(range.optBoolean("limitedToEnd"), "complete catalog clamp is explicit"); eq(Json.arr(range, "queue").length(), 1);
        for (int i = 9; i < 12; i++) {
            JSONObject previous = i == 9 ? null : chapter(i - 1, url(i));
            eq(SpeechAnalysisRange.current(range, book(true, 12), all, previous).optInt("index"), i);
            JSONObject body = chapter(i, i == 11 ? "" : url(i + 1)); SpeechAnalysisRange.loaded(range, body, book(true, 12), all);
            SpeechAnalysisRange.completed(range, body, complete(body));
        }
        eq(range.optInt("completedChapters"), 3); eq(range.optString("status"), "complete");
        yes(!SpeechAnalysisRange.publicState(range).optBoolean("canResume"), "completed range does not offer resume");
        JSONObject partialBook = book(false, 20); JSONArray sparse = catalog(0, 1, 4);
        denied(() -> SpeechAnalysisRange.create(partialBook, sparse, 3, 1));
        eq(SpeechAnalysisRange.create(partialBook, sparse, 5, 2).optString("startChapterUrl"), url(4));
        denied(() -> SpeechAnalysisRange.create(book(true, 3), sparse, 1, 3));
        denied(() -> SpeechAnalysisRange.create(partialBook, catalog(0, 0), 1, 2));
        denied(() -> SpeechAnalysisRange.create(partialBook, sparse, 0, 1));
        denied(() -> SpeechAnalysisRange.create(partialBook, sparse, 1, 0));
        denied(() -> SpeechAnalysisRange.create(partialBook, sparse, 1, Long.MAX_VALUE));
        denied(() -> SpeechAnalysisRange.create(partialBook, sparse, Integer.MAX_VALUE, 2));
        JSONObject large = SpeechAnalysisRange.create(book(false, 1), catalog(0), 1, 1000000000L);
        eq(large.optLong("count"), 1000000000L); eq(Json.arr(large, "queue").length(), 1);
        JSONObject body = chapter(0, ""); SpeechAnalysisRange.completed(large, body, complete(body));
        try { SpeechAnalysisRange.current(large, book(false, 1), catalog(0), body); throw new AssertionError("unknown end called complete"); }
        catch (SpeechAnalysisRange.EndedEarly expected) { checks++; }
        eq(large.optLong("count"), 1000000000L); eq(large.optInt("completedChapters"), 1);
        JSONObject next = SpeechAnalysisRange.create(partialBook, sparse, 5, 3), fifth = chapter(4, url(5));
        SpeechAnalysisRange.completed(next, fifth, complete(fifth));
        eq(SpeechAnalysisRange.current(next, partialBook, sparse, fifth).optString("url"), url(5));
        SpeechAnalysisRange.loaded(next, chapter(5, url(6)), partialBook, catalog(0, 1, 4, 5));
        denied(() -> SpeechAnalysisRange.loaded(next, chapter(7, ""), partialBook, catalog(0, 1, 4, 5)));
        JSONObject wrongBookBody = chapter(5, ""); Json.put(wrongBookBody, "bookId", "other");
        denied(() -> SpeechAnalysisRange.loaded(next, wrongBookBody, partialBook, catalog(0, 1, 4, 5)));
        JSONObject jump = SpeechAnalysisRange.create(partialBook, sparse, 2, 4), second = chapter(1, url(4));
        SpeechAnalysisRange.completed(jump, second, complete(second));
        denied(() -> SpeechAnalysisRange.current(jump, partialBook, sparse, second));
        JSONObject foreign = chapter(1, "https://kakuyomu.jp/works/200/episodes/1002");
        denied(() -> SpeechAnalysisRange.current(jump, partialBook, sparse, foreign));
        JSONObject cycle = chapter(1, url(1)); denied(() -> SpeechAnalysisRange.current(jump, partialBook, sparse, cycle));
        JSONObject narou = Json.obj("id", "fixture", "source", "narou", "url", "https://ncode.syosetu.com/n1234aa/", "total", 3, "tocComplete", false);
        JSONArray forged = new JSONArray().put(Json.obj("index", 0, "url", "https://ncode.syosetu.com/n1234aa/1/"))
                .put(Json.obj("index", 1, "url", "https://ncode.syosetu.com/n1234aa/3/"));
        JSONObject nr = SpeechAnalysisRange.create(narou, forged, 1, 2), first = chapter(0, "https://ncode.syosetu.com/n1234aa/3/");
        Json.put(first, "url", "https://ncode.syosetu.com/n1234aa/1/"); SpeechAnalysisRange.completed(nr, first, complete(first));
        denied(() -> SpeechAnalysisRange.current(nr, narou, forged, first));
        JSONObject incomplete = SpeechAnalysisRange.create(book(false, 1), catalog(0), 1, 2), p = SpeechPlan.create("fixture", body, null);
        denied(() -> SpeechAnalysisRange.completed(incomplete, body, p)); eq(incomplete.optInt("completedChapters"), 0);
        SpeechAnalysisRange.rewind(range, 1); eq(range.optInt("completedChapters"), 1); eq(Json.arr(range, "queue").length(), 2);
        Json.put(range, "status", "paused"); Json.put(range, "accountId", "INTERNAL_ACCOUNT"); Json.put(range, "apiKey", "MUST_NEVER_APPEAR");
        JSONObject visible = SpeechAnalysisRange.publicState(range);
        yes(visible.optBoolean("canResume"), "partial range can resume");
        yes(!visible.has("queue") && !visible.has("accountId") && !visible.toString().contains("MUST_NEVER_APPEAR"), "public range is a whitelist");
        System.out.println("SpeechAnalysisRange production checks passed: " + checks + " (synthetic catalogs, no network)");
    }
}
