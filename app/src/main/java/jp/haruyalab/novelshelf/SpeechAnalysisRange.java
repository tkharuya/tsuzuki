package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Pure range/adjacency rules. A requested count never allocates a count-sized queue. */
final class SpeechAnalysisRange {
    static final int VERSION = 1;
    private SpeechAnalysisRange() { }

    static JSONObject create(JSONObject book, JSONArray catalog, int startNumber, long requestedCount) {
        if (book == null || book.optString("id").isEmpty()) throw invalid("本棚の作品を開いてください。");
        if (startNumber < 1 || requestedCount < 1 || requestedCount > (long)Integer.MAX_VALUE - startNumber + 1)
            throw invalid("開始話と話数は正の整数で指定してください。範囲が大きすぎる場合は数値を確認してください。");
        Catalog lookup = new Catalog(catalog); JSONObject first = lookup.at(startNumber - 1);
        if (first == null) throw invalid("指定した開始話が目次にありません。目次を更新してから選んでください。");
        validateUrl(book, first.optString("url"), startNumber - 1, lookup);
        boolean complete = book.optBoolean("tocComplete");
        long count = requestedCount;
        if (complete) {
            int total = book.optInt("total", -1);
            if (total < 1 || catalog.length() != total) throw invalid("目次の話数が一致しません。目次を更新してください。");
            Set<Integer> indices = new HashSet<>(); Set<String> urls = new HashSet<>();
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject item = catalog.optJSONObject(i); int index = item == null ? -1 : item.optInt("index", -1);
                if (index < 0 || index >= total || !indices.add(index) || !urls.add(item.optString("url")))
                    throw invalid("目次に欠落または重複があります。目次を更新してください。");
                validateUrl(book, item.optString("url"), index, lookup);
            }
            count = Math.min(requestedCount, (long)total - startNumber + 1);
        }
        JSONObject range = Json.obj("version", VERSION, "jobId", "", "bookId", book.optString("id"),
                "bookUrl", book.optString("url"), "source", book.optString("source"), "tocComplete", complete,
                "status", "preview", "startNumber", startNumber, "startChapterUrl", first.optString("url"),
                "startTitle", first.optString("title"), "requestedCount", requestedCount, "count", count,
                "endNumber", (long)startNumber + count - 1, "limitedToEnd", count < requestedCount,
                "completedChapters", 0, "currentNumber", startNumber, "currentChapterUrl", first.optString("url"),
                "currentTitle", first.optString("title"), "analyzedUnits", 0, "totalUnits", 0,
                "model", "", "provider", "", "error", "", "endedEarly", false, "updatedAt", System.currentTimeMillis(),
                "queue", new JSONArray().put(entry(first, startNumber - 1)));
        return range;
    }

    static void validate(JSONObject range, JSONObject book, JSONArray catalog) {
        validate(range, book, new Catalog(catalog));
    }
    private static void validate(JSONObject range, JSONObject book, Catalog lookup) {
        if (range == null || range.optInt("version") != VERSION || book == null
                || !range.optString("bookId").equals(book.optString("id"))
                || !range.optString("bookUrl").equals(book.optString("url"))
                || !range.optString("source").equals(book.optString("source")))
            throw invalid("作品または保存した解析範囲が変更されています。範囲を選び直してください。");
        int start = range.optInt("startNumber", -1); long requested = range.optLong("requestedCount", -1), count = range.optLong("count", -1);
        long completed = range.optLong("completedChapters", -1); JSONArray queue = Json.arr(range, "queue");
        if (start < 1 || requested < 1 || requested > (long)Integer.MAX_VALUE - start + 1 || count < 1 || count > requested
                || completed < 0 || completed > count || queue.length() < completed || queue.length() > count
                || queue.length() == 0 || range.optLong("endNumber") != (long)start + count - 1)
            throw invalid("保存した解析範囲を確認できません。範囲を選び直してください。");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < queue.length(); i++) {
            JSONObject item = queue.optJSONObject(i); int index = start - 1 + i;
            if (item == null || item.optInt("index", -1) != index || !seen.add(item.optString("url")))
                throw invalid("保存した章の順序が一致しません。範囲を選び直してください。");
            validateUrl(book, item.optString("url"), index, lookup);
            JSONObject known = lookup.at(index), byUrl = lookup.url(item.optString("url"));
            if (known != null && !item.optString("url").equals(known.optString("url"))
                    || byUrl != null && byUrl.optInt("index", -1) != index)
                throw invalid("解析範囲の目次が変更されています。目次を確認して範囲を選び直してください。");
        }
        if (!queue.optJSONObject(0).optString("url").equals(range.optString("startChapterUrl")))
            throw invalid("開始話が変更されています。範囲を選び直してください。");
    }

    /** Resolve just the next required chapter, never the next array element. */
    static JSONObject current(JSONObject range, JSONObject book, JSONArray catalog, JSONObject previous) {
        Catalog lookup = new Catalog(catalog); validate(range, book, lookup);
        int completed = range.optInt("completedChapters");
        if (completed >= range.optLong("count")) return null;
        JSONArray queue = Json.arr(range, "queue"); int index = range.optInt("startNumber") - 1 + completed;
        JSONObject chosen;
        if (completed < queue.length()) chosen = queue.optJSONObject(completed);
        else {
            if (previous == null || !previous.optString("bookId").equals(range.optString("bookId"))
                    || previous.optInt("index", -1) != index - 1
                    || !previous.optString("url").equals(queue.optJSONObject(completed - 1).optString("url")))
                throw invalid("直前の章を確認できません。保存状態と目次を確認してください。");
            String next = previous.optString("nextUrl"); JSONObject known = lookup.at(index);
            if (next.isEmpty() && known != null) next = known.optString("url");
            if (next.isEmpty()) throw new EndedEarly("次の話を確認できず停止しました。指定した話数は完了していません。目次・公開状態を確認して再開してください。");
            validateUrl(book, next, index, lookup);
            JSONObject linked = lookup.url(next);
            if (linked != null && linked.optInt("index", -1) != index || known != null && !next.equals(known.optString("url")))
                throw invalid("次の話の順序が連続していません。欠けた話を飛ばさず停止しました。目次を確認してください。");
            for (int i = 0; i < queue.length(); i++) if (next.equals(queue.optJSONObject(i).optString("url")))
                throw invalid("章のリンクが循環しています。目次を確認してください。");
            if ("local".equals(book.optString("source")) && known == null)
                throw invalid("次の話の保存済み本文がありません。");
            chosen = entry(known == null ? Json.obj("url", next, "title", "第" + (index + 1) + "話") : known, index);
            queue.put(chosen);
        }
        Json.put(range, "currentNumber", index + 1); Json.put(range, "currentChapterUrl", chosen.optString("url"));
        Json.put(range, "currentTitle", chosen.optString("title"));
        return Json.copy(chosen);
    }

    static void loaded(JSONObject range, JSONObject chapter, JSONObject book, JSONArray catalog) {
        Catalog lookup = new Catalog(catalog); validate(range, book, lookup); int offset = range.optInt("completedChapters");
        JSONObject expected = Json.arr(range, "queue").optJSONObject(offset);
        if (expected == null || chapter == null || !range.optString("bookId").equals(chapter.optString("bookId"))
                || !expected.optString("url").equals(chapter.optString("url"))
                || expected.optInt("index") != chapter.optInt("index", -1))
            throw invalid("取得した話の作品・URL・話数が一致しません。目次を確認してください。");
        validateUrl(book, chapter.optString("url"), chapter.optInt("index"), lookup);
        if (Json.arr(chapter, "paragraphs").length() == 0)
            throw invalid("本文がありません。ログイン・公開状態を公式画面で確認してください。");
        Json.put(expected, "title", chapter.optString("title", expected.optString("title")));
        Json.put(range, "currentTitle", expected.optString("title"));
    }

    static void completed(JSONObject range, JSONObject chapter, JSONObject plan) {
        int offset = range.optInt("completedChapters"); JSONObject entry = Json.arr(range, "queue").optJSONObject(offset);
        if (entry == null || !"complete".equals(plan.optString("analysisStatus"))
                || !range.optString("bookId").equals(plan.optString("bookId"))
                || !entry.optString("url").equals(plan.optString("chapterUrl"))
                || !SpeechPlan.compatible(plan, chapter)
                || completedUnits(range, plan) != Json.arr(plan, "units").length()
                || range.optBoolean("detailedActing") && !"detailed".equals(plan.optString("analysisDetail")))
            throw invalid("この話の解析完了を確認できません。未解析部分から再開してください。");
        Json.put(entry, "inputHash", plan.optString("inputHash"));
        Json.put(range, "completedChapters", offset + 1); progress(range, plan);
        Json.put(range, "status", offset + 1 == range.optLong("count") ? "complete" : "queued");
        Json.put(range, "error", ""); Json.put(range, "endedEarly", false); range.remove("analysisError");
    }

    static int completedUnits(JSONObject range, JSONObject plan) {
        return SpeechPronunciation.completedCount(plan, range.optInt("pronunciationVersion") == SpeechPronunciation.VERSION);
    }
    static void progress(JSONObject range, JSONObject plan) {
        Json.put(range, "analyzedUnits", completedUnits(range, plan)); Json.put(range, "totalUnits", Json.arr(plan, "units").length());
        Json.put(range, "analysisPhase", plan.optString("analysisPhase"));
        if (plan.has("analysisMethod")) Json.put(range, "analysisMethod", plan.optString("analysisMethod"));
        if (plan.has("analysisMetrics")) Json.put(range, "analysisMetrics", Json.copy(plan.optJSONObject("analysisMetrics")));
        if (plan.has("analysisError")) Json.put(range, "analysisError", Json.copy(plan.optJSONObject("analysisError")));
        else range.remove("analysisError");
        if ("pronunciation_changed".equals(plan.optString("analysisPauseReason"))) Json.put(range, "analysisPauseReason", "pronunciation_changed");
        else range.remove("analysisPauseReason");
        Json.put(range, "updatedAt", System.currentTimeMillis());
    }

    static void rewind(JSONObject range, int completed) {
        if (completed < 0 || completed > range.optInt("completedChapters")) throw invalid("解析の再開位置が不正です。");
        JSONArray queue = Json.arr(range, "queue");
        // Subsequent next links must be checked again after a changed/missing completed chapter.
        while (queue.length() > completed + 1) queue.remove(queue.length() - 1);
        if (queue.optJSONObject(completed) != null) queue.optJSONObject(completed).remove("inputHash");
        Json.put(range, "completedChapters", completed); Json.put(range, "analyzedUnits", 0); Json.put(range, "totalUnits", 0);
        Json.put(range, "status", "paused");
        resumePointer(range);
    }

    static void resumePointer(JSONObject range) {
        int completed = range.optInt("completedChapters"); JSONObject next = Json.arr(range, "queue").optJSONObject(completed);
        Json.put(range, "currentNumber", (long)range.optInt("startNumber") + completed);
        Json.put(range, "currentChapterUrl", next == null ? "" : next.optString("url"));
        Json.put(range, "currentTitle", next == null ? "" : next.optString("title"));
        if (next == null) { Json.put(range, "analyzedUnits", 0); Json.put(range, "totalUnits", 0); }
    }

    static JSONObject publicState(JSONObject range) {
        if (range == null || range.length() == 0) return new JSONObject();
        JSONObject out = new JSONObject();
        for (String key : new String[]{"jobId", "bookId", "status", "startNumber", "startChapterUrl", "startTitle", "requestedCount", "count",
                "endNumber", "limitedToEnd", "completedChapters", "currentNumber", "currentChapterUrl", "currentTitle", "analyzedUnits", "totalUnits",
                "model", "provider", "error", "analysisError", "analysisPauseReason", "endedEarly", "updatedAt", "detailedActing", "analysisDetail", "analysisPhase",
                "workflowVersion", "pronunciationVersion", "useStoryMemory", "analysisMethod", "analysisMetrics", "memoryMetrics", "memoryStatus", "historyFromNumber", "historyToNumber",
                "historyTotal", "historyCompleted", "historyThrough", "historyError", "analysisActivity"}) if (range.has(key)) Json.put(out, key, range.opt(key));
        String status = range.optString("status");
        Json.put(out, "phase", status);
        if ("queued".equals(status) || "loading".equals(status)) Json.put(out, "status", "running");
        Json.put(out, "canResume", ("paused".equals(status) || "error".equals(status)) && range.optLong("completedChapters") < range.optLong("count"));
        return Json.copy(out);
    }

    static JSONObject at(JSONArray catalog, int index) { return new Catalog(catalog).at(index); }
    static final class Catalog {
        final Map<Integer, JSONObject> byIndex = new HashMap<>();
        final Map<String, JSONObject> byUrl = new HashMap<>();
        final Set<Integer> repeatedIndices = new HashSet<>();
        final Set<String> repeatedUrls = new HashSet<>();
        Catalog(JSONArray catalog) {
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject item = catalog.optJSONObject(i); if (item == null) continue;
                int index = item.optInt("index", -1); String url = item.optString("url");
                if (byIndex.put(index, item) != null) repeatedIndices.add(index);
                if (byUrl.put(url, item) != null) repeatedUrls.add(url);
            }
        }
        JSONObject at(int index) {
            if (repeatedIndices.contains(index)) throw invalid("同じ話数に複数の章があります。目次を更新してください。");
            return byIndex.get(index);
        }
        JSONObject url(String url) {
            if (repeatedUrls.contains(url)) throw invalid("章のURLが重複しています。目次を更新してください。");
            return byUrl.get(url);
        }
    }
    static JSONObject historyAt(JSONObject book, Catalog lookup, int index) {
        JSONObject item = lookup.at(index);
        if (item == null) throw invalid("第" + (index + 1) + "話が目次にありません。");
        validateUrl(book, item.optString("url"), index, lookup); return item;
    }
    private static JSONObject entry(JSONObject metadata, int index) {
        return Json.obj("url", metadata.optString("url"), "title", Json.limit(metadata.optString("title"), 1000), "index", index);
    }
    private static void validateUrl(JSONObject book, String url, int index, Catalog lookup) {
        if (url == null || url.isEmpty()) throw invalid("章のURLを確認できません。");
        if ("local".equals(book.optString("source"))) {
            JSONObject meta = lookup.url(url);
            if (meta == null || meta.optInt("index", -1) != index || !url.equals(book.optString("url") + "/" + index))
                throw invalid("保存済み作品の章と話数が一致しません。");
            return;
        }
        try {
            if (!url.equals(Urls.input(url)) || !Urls.sameBook(url, book.optString("url"))) throw invalid("別作品または不正な章リンクです。");
            if ("narou".equals(Urls.source(url))) {
                String[] parts = URI.create(url).getPath().split("/");
                if (parts.length == 3 ? Long.parseLong(parts[2]) != (long)index + 1 : index != 0)
                    throw invalid("次の話のURLと話数が一致しません。欠けた話を飛ばさず停止しました。");
            } else if (url.equals(Urls.book(url))) throw invalid("作品の目次ではなく各話を選んでください。");
        } catch (NumberFormatException e) { throw invalid("章のURLの話数が不正です。"); }
    }
    static final class EndedEarly extends IllegalStateException { EndedEarly(String message) { super(message); } }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
