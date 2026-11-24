package jp.haruyalab.novelshelf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One persisted, sequential chapter-range job. It never marks chapters read or moves a position. */
final class SpeechAnalysis {
    interface Listener { void update(JSONObject state); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static volatile Listener listener;
    private static volatile Job active;
    private static final class Config {
        String provider, accountId, model, key, voice;
        boolean useRuby, detailedActing, useStoryMemory, pronunciation = true;
        int workflowVersion = 2;
    }
    private static final class Job {
        final Context context;
        final String bookId, id;
        final SpeechStore store;
        final JSONObject range;
        final Config config;
        final SpeechAnalyzer.Transport transport;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile String url = "";
        volatile JSONObject plan = new JSONObject(), latest;
        volatile JSONArray cast = new JSONArray();
        volatile boolean invalidated, persistenceFailed;
        int savedBatch = -1;
        String activityScope = "chapter";
        int activityFrom, activityTo;
        Job(Context context, SpeechStore store, JSONObject range, Config config, SpeechAnalyzer.Transport transport) {
            this.context = context; this.store = store; this.range = range; this.config = config;
            this.transport = transport;
            bookId = range.optString("bookId"); id = range.optString("jobId");
            url = range.optString("currentChapterUrl");
            latest = SpeechAnalysisRange.publicState(range);
        }
    }
    private static final class Paused extends IOException { }

    static void setListener(Listener value) { listener = value; }
    static boolean running() { return active != null; }
    static boolean runningBook(String bookId) { Job job = active; return job != null && job.bookId.equals(bookId); }
    static boolean running(String bookId, String url) { Job job = active; return job != null && job.bookId.equals(bookId) && job.url.equals(url); }
    static void cancel() { Job job = active; if (job != null) cancelJob(job.id); }
    static void cancelBook(String bookId) { Job job = active; if (job != null && job.bookId.equals(bookId)) cancelJob(job.id); }
    static synchronized void cancelJob(String jobId) {
        Job job = active;
        if (job != null && jobId != null && !jobId.isEmpty() && job.id.equals(jobId)) { job.cancelled.set(true); emit(job); }
    }
    static JSONObject activeSnapshot(Context context) {
        Job job = active; if (job == null) return new JSONObject();
        JSONObject out = Json.copy(job.latest);
        if (job.cancelled.get()) Json.put(out, "status", "pausing");
        return out;
    }
    static synchronized JSONObject snapshot(Context context, String bookId) {
        if (bookId == null || bookId.isEmpty()) return new JSONObject();
        SpeechStore store = new SpeechStore(context.getApplicationContext()); JSONObject range = store.analysisRange(bookId);
        if (range.length() == 0) return range;
        Job job = active;
        if (job != null && job.bookId.equals(bookId) && job.id.equals(range.optString("jobId"))) return activeSnapshot(context);
        String status = range.optString("status");
        if ("queued".equals(status) || "loading".equals(status) || "running".equals(status) || "pausing".equals(status)) {
            JSONObject activity = Json.copy(range.optJSONObject("analysisActivity"));
            if (activity.length() > 0) {
                Json.put(activity, "stage", "paused"); Json.put(activity, "endedAt", range.optLong("updatedAt", System.currentTimeMillis()));
                Json.put(range, "analysisActivity", activity);
            }
            Json.put(range, "status", "paused"); Json.put(range, "error", ""); Json.put(range, "updatedAt", System.currentTimeMillis());
            store.saveAnalysisRange(bookId, range);
            // A later job replaces the range, so keep the recovered stop time on this chapter too.
            store.saveAnalysisActivity(bookId, range.optString("currentChapterUrl"), range);
        }
        return SpeechAnalysisRange.publicState(range);
    }
    static JSONObject preview(Context context, String bookId, int startNumber, long requestedCount) {
        Context app = context.getApplicationContext(); ReaderStore books = new ReaderStore(app);
        try {
            JSONObject range = SpeechAnalysisRange.create(required(books, bookId), books.chapters(bookId), startNumber, requestedCount);
            JSONObject settings = new SpeechStore(app).settings(); String provider = settings.optString("analysisProvider", "chatgpt");
            Json.put(range, "provider", provider);
            Json.put(range, "detailedActing", settings.optBoolean("detailedActing", true));
            Json.put(range, "analysisDetail", settings.optBoolean("detailedActing", true) ? "detailed" : "standard");
            Json.put(range, "workflowVersion", 2);
            Json.put(range, "pronunciationVersion", SpeechPronunciation.VERSION);
            boolean useMemory = settings.optBoolean("detailedActing", true) && settings.optBoolean("useStoryMemory", true);
            Json.put(range, "useStoryMemory", useMemory);
            historyPreview(books, new SpeechStore(app), bookId, startNumber - 1, useMemory, range);
            Json.put(range, "model", "api_key".equals(provider) ? settings.optString("analysisModel") : ChatGptSession.get(app).snapshot().optString("selectedModel"));
            return SpeechAnalysisRange.publicState(range);
        } finally { books.close(); }
    }
    static synchronized void start(Context context, String bookId, String url, JSONObject confirmation) {
        ReaderStore books = new ReaderStore(context.getApplicationContext());
        try {
            required(books, bookId); JSONObject chapter = books.chapterMeta(bookId, url);
            if (chapter == null || chapter.optInt("index", -1) < 0) throw new IllegalArgumentException("開始話を目次から選んでください。");
            startRange(context, bookId, chapter.optInt("index") + 1, 1, url, null, confirmation);
        } finally { books.close(); }
    }
    static synchronized JSONObject startRange(Context context, String bookId, int startNumber, long requestedCount) {
        return startRange(context, bookId, startNumber, requestedCount, "");
    }
    static synchronized JSONObject startRange(Context context, String bookId, int startNumber, long requestedCount, String expectedStartUrl) {
        return startRange(context, bookId, startNumber, requestedCount, expectedStartUrl, null);
    }
    /** Package-local transport injection lets the full queue/analyzer run against fixture responses. */
    static synchronized JSONObject startRange(Context context, String bookId, int startNumber, long requestedCount, String expectedStartUrl, SpeechAnalyzer.Transport transport) {
        return startRange(context, bookId, startNumber, requestedCount, expectedStartUrl, transport, null);
    }
    static synchronized JSONObject startConfirmedRange(Context context, String bookId, int startNumber, long requestedCount, String expectedStartUrl, JSONObject confirmation) {
        return startRange(context, bookId, startNumber, requestedCount, expectedStartUrl, null, confirmation);
    }
    private static JSONObject startRange(Context context, String bookId, int startNumber, long requestedCount, String expectedStartUrl, SpeechAnalyzer.Transport transport, JSONObject confirmation) {
        idle(); Context app = context.getApplicationContext(); SpeechStore store = new SpeechStore(app); ReaderStore books = new ReaderStore(app);
        try {
            JSONObject range = SpeechAnalysisRange.create(required(books, bookId), books.chapters(bookId), startNumber, requestedCount);
            if (expectedStartUrl != null && !expectedStartUrl.isEmpty() && !expectedStartUrl.equals(range.optString("startChapterUrl")))
                throw new IllegalStateException("確認後に開始話の目次が変更されました。範囲を確認し直してください。");
            Config config = configuration(app, store);
            Json.put(range, "provider", config.provider); Json.put(range, "accountId", config.accountId); Json.put(range, "model", config.model);
            Json.put(range, "defaultVoice", config.voice); Json.put(range, "useRuby", config.useRuby);
            Json.put(range, "detailedActing", config.detailedActing);
            Json.put(range, "analysisDetail", config.detailedActing ? "detailed" : "standard");
            Json.put(range, "workflowVersion", config.workflowVersion); Json.put(range, "useStoryMemory", config.useStoryMemory);
            Json.put(range, "pronunciationVersion", config.pronunciation ? SpeechPronunciation.VERSION : 0);
            historyPreview(books, store, bookId, startNumber - 1, config.useStoryMemory, range);
            if (confirmation != null) checkHistoryConfirmation(confirmation, range);
            return launch(app, store, range, config, transport);
        } finally { books.close(); }
    }
    static void checkHistoryConfirmation(JSONObject input, JSONObject preview) {
        boolean required = preview.optInt("historyTotal") > 0 || input.has("expectedUseStoryMemory");
        if (required && (!(input.opt("expectedUseStoryMemory") instanceof Boolean)
                || !(input.opt("expectedHistoryFromNumber") instanceof Number) || !(input.opt("expectedHistoryToNumber") instanceof Number)
                || input.optBoolean("expectedUseStoryMemory") != preview.optBoolean("useStoryMemory")
                || input.optInt("expectedHistoryFromNumber", -1) != preview.optInt("historyFromNumber")
                || input.optInt("expectedHistoryToNumber", -1) != preview.optInt("historyToNumber")))
            throw new IllegalStateException("作品メモの対象が変わりました。確認画面を開き直してから解析してください。");
    }
    static synchronized JSONObject resumeRange(Context context, String bookId) {
        return resumeRange(context, bookId, null);
    }
    static synchronized JSONObject resumeRange(Context context, String bookId, SpeechAnalyzer.Transport transport) {
        idle(); Context app = context.getApplicationContext(); SpeechStore store = new SpeechStore(app); ReaderStore books = new ReaderStore(app);
        try {
            JSONObject range = store.analysisRange(bookId); JSONObject book = required(books, bookId);
            if (range.length() == 0) throw new IllegalArgumentException("再開する解析範囲がありません。開始話と話数を選んでください。");
            SpeechAnalysisRange.validate(range, book, books.chapters(bookId));
            if (range.optLong("completedChapters") >= range.optLong("count")) throw new IllegalStateException("選択した範囲の解析は完了しています。新しい範囲を選んでください。");
            Config config = checkedConfiguration(app, store, range);
            config.voice = range.optString("defaultVoice", config.voice);
            // Changed or removed completed bodies invalidate the completion claim from that point.
            JSONArray queue = Json.arr(range, "queue");
            for (int i = 0; i < range.optInt("completedChapters"); i++) {
                JSONObject entry = queue.optJSONObject(i), chapter = books.chapter(bookId, entry.optString("url"));
                JSONObject plan = store.plan(bookId, entry.optString("url"));
                if (chapter == null || !entry.optString("inputHash").equals(SpeechPlan.inputHash(chapter, config.useRuby))
                        || !"complete".equals(plan.optString("analysisStatus")) || !config.model.equals(plan.optString("analysisModel"))
                        || !bookId.equals(plan.optString("bookId")) || !entry.optString("url").equals(plan.optString("chapterUrl"))
                        || !SpeechPlan.compatible(plan, chapter) || Json.arr(plan, "units").length() == 0
                        || SpeechAnalysisRange.completedUnits(range, plan) != Json.arr(plan, "units").length()
                        || config.detailedActing && !"detailed".equals(plan.optString("analysisDetail"))) {
                    SpeechAnalysisRange.rewind(range, i); break;
                }
            }
            return launch(app, store, range, config, transport);
        } finally { books.close(); }
    }
    private static JSONObject launch(Context context, SpeechStore store, JSONObject range, Config config, SpeechAnalyzer.Transport transport) {
        SpeechAnalysisRange.resumePointer(range);
        Json.put(range, "detailedActing", config.detailedActing);
        Json.put(range, "analysisDetail", config.detailedActing ? "detailed" : "standard");
        Json.put(range, "workflowVersion", config.workflowVersion); Json.put(range, "useStoryMemory", config.useStoryMemory);
        Json.put(range, "pronunciationVersion", config.pronunciation ? SpeechPronunciation.VERSION : 0);
        range.remove("historyBlockedIndex");
        Json.put(range, "jobId", UUID.randomUUID().toString()); Json.put(range, "status", "queued");
        Json.put(range, "error", ""); Json.put(range, "endedEarly", false); range.remove("analysisError"); range.remove("analysisPauseReason");
        long started = System.currentTimeMillis(); Json.put(range, "runStartedAt", started);
        Json.put(range, "analysisActivity", Json.obj("stage", "preparing", "scope", "chapter", "stageStartedAt", started,
                "runStartedAt", started, "requestNumber", range.optInt("activityRequests"),
                "chapterFromNumber", range.optInt("currentNumber"), "chapterToNumber", range.optInt("currentNumber"),
                "targetUnits", 0, "targetChapters", 1));
        Json.put(range, "updatedAt", System.currentTimeMillis()); store.beginAnalysisRange(range.optString("bookId"), range);
        Job job = new Job(context, store, range, config, transport); active = job; emit(job); WORK.execute(() -> run(job));
        return Json.copy(job.latest);
    }
    private static void idle() { if (active != null) throw new IllegalStateException("解析を実行中です。完了または中断を待ってください。"); }
    private static JSONObject required(ReaderStore books, String bookId) {
        JSONObject book = bookId == null || bookId.isEmpty() ? null : books.rawBook(bookId);
        if (book == null) throw new IllegalStateException("本棚に作品がありません。");
        return book;
    }
    private static Config configuration(Context context, SpeechStore store) {
        JSONObject settings = store.settings(); Config config = new Config();
        config.provider = settings.optString("analysisProvider", "chatgpt"); config.accountId = ""; config.key = "";
        if ("api_key".equals(config.provider)) {
            config.model = settings.optString("analysisModel", "gpt-6-luna"); config.key = store.apiKey();
            if (config.key.isEmpty()) throw new IllegalArgumentException("APIキー方式を利用するにはOpenAI APIキーを登録してください。");
        } else {
            config.provider = "chatgpt"; JSONObject account = ChatGptSession.get(context).accountForAnalysis();
            config.accountId = account.optString("id"); config.model = account.optString("selected_model");
        }
        config.voice = settings.optString("offlineDefaultVoice", OfflineVoices.defaultVoice());
        config.useRuby = settings.optBoolean("useRuby", true); config.detailedActing = settings.optBoolean("detailedActing", true);
        config.useStoryMemory = config.detailedActing && settings.optBoolean("useStoryMemory", true); return config;
    }
    private static void matchConfiguration(JSONObject range, Config config) {
        if (!range.optString("provider").equals(config.provider) || !range.optString("accountId").equals(config.accountId)
                || !range.optString("model").equals(config.model))
            throw new IllegalStateException("この解析範囲を開始した接続方法・ChatGPTアカウント・モデルに戻して再開するか、新しい範囲として開始してください。");
        if (range.optBoolean("useRuby", true) != config.useRuby)
            throw new IllegalStateException("ルビの読み上げ設定が変更されています。元の設定に戻して再開するか、新しい範囲として開始してください。");
    }
    private static Config checkedConfiguration(Context context, SpeechStore store, JSONObject range) {
        if (!range.optString("provider").equals(store.settings().optString("analysisProvider", "chatgpt")))
            throw new IllegalStateException("この解析範囲を開始した接続方法に戻して再開するか、新しい範囲として開始してください。");
        Config config = configuration(context, store);
        // Resume the confirmed mode. Pre-1.8 jobs used standard analysis and do not gain extra calls.
        config.detailedActing = range.optBoolean("detailedActing", false);
        config.workflowVersion = range.optInt("workflowVersion", 1);
        config.pronunciation = range.optInt("pronunciationVersion") == SpeechPronunciation.VERSION;
        config.useStoryMemory = config.workflowVersion >= 2 && config.detailedActing && range.optBoolean("useStoryMemory", false);
        matchConfiguration(range, config); return config;
    }
    private static void check(Job job) throws Paused { if (job.cancelled.get() || Thread.currentThread().isInterrupted()) throw new Paused(); }
    private static void revalidate(Job job) throws Paused { check(job); checkedConfiguration(job.context, job.store, job.range); }

    static JSONObject previewChapter(Context context, String bookId, String url) {
        ReaderStore books = new ReaderStore(context.getApplicationContext());
        try {
            required(books, bookId); JSONObject chapter = books.chapterMeta(bookId, url);
            if (chapter == null || chapter.optInt("index", -1) < 0) throw new IllegalArgumentException("開始話を目次から選んでください。");
            return preview(context, bookId, chapter.optInt("index") + 1, 1);
        } finally { books.close(); }
    }
    static JSONObject memoryState(Context context, String bookId, String url) {
        ReaderStore books = new ReaderStore(context.getApplicationContext()); SpeechStore store = new SpeechStore(context);
        try {
            required(books, bookId); int index = memoryTarget(books, bookId, url);
            validateMemorySources(books, store, bookId, index);
            JSONObject memory = store.storyMemoryBefore(bookId, index); int through = memory.optInt("throughChapterIndex", -1) + 1;
            return Json.obj("memory", memory, "coverage", Json.obj("fromNumber", 1, "throughNumber", through,
                    "targetNumber", index + 1, "missingCount", Math.max(0, index - through)), "cast", store.cast(bookId));
        } finally { books.close(); }
    }
    static JSONObject saveMemory(Context context, String bookId, String url, JSONArray changes, JSONArray removeIds) {
        ReaderStore books = new ReaderStore(context.getApplicationContext());
        try {
            required(books, bookId); int index = memoryTarget(books, bookId, url);
            new SpeechStore(context).editStoryMemory(bookId, index, changes, removeIds);
            return memoryState(context, bookId, url);
        } finally { books.close(); }
    }
    private static int memoryTarget(ReaderStore books, String id, String url) {
        String target = url == null || url.isEmpty() ? books.position(id).optString("chapterUrl") : url;
        JSONObject chapter = books.chapterMeta(id, target);
        return chapter == null ? 0 : Math.max(0, chapter.optInt("index"));
    }
    private static void validateMemorySources(ReaderStore books, SpeechStore store, String id, int before) {
        JSONArray sources = store.storyMemorySources(id, before);
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i); String url = source.optString("url");
            JSONObject meta = books.chapterMeta(id, url), chapter = books.chapter(id, url);
            if (meta == null || meta.optInt("index", -1) != source.optInt("index", -1)
                    || chapter != null && !source.optString("hash").equals(SpeechPlan.inputHash(chapter, source.optBoolean("useRuby", true)))) {
                store.invalidateStoryMemoryFrom(id, Math.max(0, source.optInt("index"))); return;
            }
        }
    }
    private static void historyPreview(ReaderStore books, SpeechStore store, String id, int target, boolean enabled, JSONObject range) {
        if (enabled) validateMemorySources(books, store, id, target);
        int through = enabled ? store.storyMemoryBefore(id, target).optInt("throughChapterIndex", -1) + 1 : target;
        int total = enabled ? Math.max(0, target - through) : 0;
        Json.put(range, "historyFromNumber", total > 0 ? through + 1 : 0);
        Json.put(range, "historyToNumber", total > 0 ? target : 0);
        Json.put(range, "historyTotal", total); Json.put(range, "historyCompleted", 0);
        Json.put(range, "historyThrough", enabled ? through : 0);
        Json.put(range, "memoryStatus", !enabled ? "disabled" : total == 0 ? "ready" : "pending");
        Json.put(range, "historyError", "");
    }
    private static SpeechAnalyzer analyzer(Job job, JSONObject memory) {
        SpeechAnalyzer value;
        if ("chatgpt".equals(job.config.provider)) {
            SpeechAnalyzer.Authorization auth = () -> {
                revalidate(job); return ChatGptSession.get(job.context).accessToken(job.config.accountId, () -> job.cancelled.get());
            };
            value = job.transport == null ? new SpeechAnalyzer(job.config.voice, auth) : new SpeechAnalyzer(job.transport, job.config.voice, auth);
        } else value = job.transport == null ? new SpeechAnalyzer(job.config.voice) : new SpeechAnalyzer(job.transport, job.config.voice);
        return value.withDetailedActing(job.config.detailedActing).withChapterWorkflow(job.config.workflowVersion >= 2)
                .withPronunciation(job.config.pronunciation)
                .withStoryMemory(memory).withActivity(activity -> reportActivity(job, activity));
    }
    private static void activityContext(Job job, String scope, int first, int last) {
        job.activityScope = scope; job.activityFrom = Math.max(1, first); job.activityTo = Math.max(job.activityFrom, last);
    }
    /** These are observed local phases. A provider's inference percentage is never fabricated. */
    private static void reportActivity(Job job, JSONObject update) throws Exception {
        check(job); String stage = update.optString("stage");
        if (!java.util.Arrays.asList("loading", "preparing", "authorizing", "waiting", "validating", "saving").contains(stage))
            throw new IllegalArgumentException("解析処理の状態を確認できません。");
        if ("waiting".equals(stage)) Json.put(job.range, "activityRequests", job.range.optInt("activityRequests") + 1);
        int from = job.activityFrom > 0 ? job.activityFrom : Math.max(1, job.range.optInt("currentNumber"));
        JSONObject activity = Json.obj("stage", stage, "scope", job.activityScope,
                "stageStartedAt", System.currentTimeMillis(), "runStartedAt", job.range.optLong("runStartedAt"),
                "requestNumber", job.range.optInt("activityRequests"), "chapterFromNumber", from,
                "chapterToNumber", Math.max(from, job.activityTo), "targetUnits", Math.max(0, update.optInt("targetUnits")),
                "targetChapters", Math.max(1, update.optInt("targetChapters", job.activityTo - from + 1)));
        Json.put(job.range, "analysisActivity", activity);
        if (job.plan.length() > 0) Json.put(job.plan, "analysisActivity", Json.copy(activity));
        persist(job); emit(job);
    }
    private static void activity(Job job, String stage, String scope, int first, int last) throws Exception {
        activityContext(job, scope, first, last);
        reportActivity(job, Json.obj("stage", stage, "targetUnits", "history".equals(scope) ? 0 : Json.arr(job.plan, "units").length(),
                "targetChapters", Math.max(1, last - first + 1)));
    }
    private static void endActivity(Job job, String stage) {
        JSONObject activity = Json.copy(job.range.optJSONObject("analysisActivity"));
        Json.put(activity, "stage", stage); Json.put(activity, "endedAt", System.currentTimeMillis());
        Json.put(job.range, "analysisActivity", activity);
        if (job.plan.length() > 0) Json.put(job.plan, "analysisActivity", Json.copy(activity));
    }
    private static JSONObject memoryDiagnostics(Throwable failure) {
        for (int depth = 0; failure != null && depth < 12; depth++, failure = failure.getCause())
            if (failure instanceof StoryMemory.ValidationFailure) return ((StoryMemory.ValidationFailure)failure).diagnostics();
        return null;
    }
    private static void addMetrics(JSONObject target, String field, JSONObject extra) {
        if (extra == null) return; JSONObject combined = Json.copy(target.optJSONObject(field));
        for (String name : new String[]{"requestCount", "modelMs", "validationMs", "elapsedMs"})
            Json.put(combined, name, combined.optLong(name) + Math.max(0, extra.optLong(name)));
        Json.put(target, field, combined);
    }
    private static int textLength(JSONObject plan) {
        long count = 0; JSONArray units = Json.arr(plan, "units");
        for (int i = 0; i < units.length(); i++) count += units.optJSONObject(i).optString("text").length();
        return (int)Math.min(Integer.MAX_VALUE, count);
    }
    private static void verifySources(Job job, ReaderStore books, JSONArray plans) throws Exception {
        revalidate(job);
        for (int i = 0; i < plans.length(); i++) {
            JSONObject plan = plans.optJSONObject(i), current = books.chapter(job.bookId, plan.optString("chapterUrl"));
            if (current == null || !SpeechPlan.compatible(plan, current)) throw new IllegalStateException("作品メモの準備中に本文が変わりました。再開してください。");
        }
    }
    private static JSONObject summarize(Job job, JSONObject memory, JSONArray plans) throws Exception {
        check(job); JSONArray knownCast = CastRegistry.mergeAnalysis(job.store.cast(job.bookId), job.cast, job.config.voice);
        activityContext(job, "history", plans.optJSONObject(0).optInt("chapterIndex") + 1,
                plans.optJSONObject(plans.length() - 1).optInt("chapterIndex") + 1);
        JSONObject scoped = job.store.reapplyStoryMemory(job.bookId, memory, plans.optJSONObject(0).optInt("chapterIndex"));
        JSONObject result = analyzer(job, scoped).summarizeHistory(plans, knownCast, job.config.key, job.config.model, () -> job.cancelled.get());
        check(job); job.cast = CastRegistry.mergeAnalysis(knownCast, result.optJSONArray("cast"), job.config.voice);
        Json.put(result, "cast", job.cast); addMetrics(job.range, "memoryMetrics", result.optJSONObject("analysisMetrics")); return result;
    }
    private static JSONObject summarizeBounded(Job job, JSONObject memory, JSONArray plans) throws Exception {
        JSONObject result;
        if (plans.length() == 1 && textLength(plans.optJSONObject(0)) > 32000) {
            JSONObject original = plans.optJSONObject(0); JSONArray all = Json.arr(original, "units");
            LinkedHashMap<String, JSONObject> facts = new LinkedHashMap<>();
            JSONObject working = memory; JSONArray lastCast = job.store.cast(job.bookId); int start = 0;
            while (start < all.length()) {
                JSONArray portion = new JSONArray(); int chars = 0;
                while (start < all.length()) {
                    JSONObject unit = all.optJSONObject(start); int size = unit.optString("text").length();
                    if (portion.length() > 0 && chars + size > 32000) break;
                    portion.put(unit); chars += size; start++;
                }
                JSONObject partial = Json.copy(original); Json.put(partial, "units", portion);
                result = summarize(job, working, new JSONArray().put(partial)); lastCast = result.optJSONArray("cast");
                JSONObject delta = result.optJSONObject("memoryDelta");
                JSONArray additions = Json.arr(delta, "facts");
                for (int i = 0; i < additions.length(); i++) {
                    JSONObject fact = additions.optJSONObject(i); facts.remove(fact.optString("id")); facts.put(fact.optString("id"), fact);
                }
                while (facts.size() > StoryMemory.MAX_FACTS) facts.remove(facts.keySet().iterator().next());
                working = job.store.reapplyStoryMemory(job.bookId, StoryMemory.merge(working, delta, new JSONArray().put(partial)),
                        original.optInt("chapterIndex"));
                // Cast deltas are kept locally until the complete historical chapter succeeds.
                job.cast = CastRegistry.mergeAnalysis(job.cast, lastCast, job.config.voice);
                publish(job); emit(job);
            }
            JSONArray additions = new JSONArray(); for (JSONObject fact : facts.values()) additions.put(fact);
            result = Json.obj("cast", job.cast, "memoryDelta", Json.obj("facts", additions));
        } else result = summarize(job, memory, plans);
        return result;
    }
    private static JSONObject finishHistoryBatch(Job job, ReaderStore books, JSONObject memory, JSONArray plans) throws Exception {
        // Target-chapter notes may describe later events. Bootstrap input uses the beginning of this historical batch.
        JSONObject atBatchStart = job.store.storyMemoryBefore(job.bookId, plans.optJSONObject(0).optInt("chapterIndex"));
        JSONObject result = summarizeBounded(job, atBatchStart, plans);
        verifySources(job, books, plans);
        JSONObject merged = job.store.commitStoryMemory(job.bookId, job.range, plans, result.optJSONArray("cast"), result.optJSONObject("memoryDelta"), job.config.model);
        Json.put(job.range, "historyThrough", merged.optInt("throughChapterIndex", -1) + 1);
        int first = job.range.optInt("historyFromNumber"), total = job.range.optInt("historyTotal");
        Json.put(job.range, "historyCompleted", Math.min(total, Math.max(0, merged.optInt("throughChapterIndex", -1) + 2 - first)));
        persist(job); emit(job); return merged;
    }
    /** Build missing past facts only after explicit analysis consent; a missing old chapter is visible, not fabricated. */
    private static JSONObject prepareMemory(Job job, ReaderStore books, int target) throws Exception {
        if (!job.config.useStoryMemory) return null;
        validateMemorySources(books, job.store, job.bookId, target);
        JSONObject memory = job.store.storyMemoryBefore(job.bookId, target); int next = memory.optInt("throughChapterIndex", -1) + 1;
        if (next >= target) { Json.put(job.range, "memoryStatus", "ready"); return memory; }
        int approvedFrom = job.range.optInt("historyTotal") > 0 ? job.range.optInt("historyFromNumber") - 1 : job.range.optInt("startNumber") - 1;
        if (next < approvedFrom)
            throw new IllegalStateException("過去メモの参照元が変わり、確認済み範囲より前の本文が必要になりました。解析範囲を選び直して対象を確認してください。");
        if (!job.range.has("historyBlockedIndex")) {
            Json.put(job.range, "memoryStatus", "building"); Json.put(job.range, "analysisPhase", "memory");
            Json.put(job.plan, "analysisStatus", "running"); Json.put(job.plan, "analysisPhase", "memory");
            Json.put(job.range, "status", "running"); persist(job); emit(job);
            SpeechAnalysisRange.Catalog catalog = new SpeechAnalysisRange.Catalog(books.chapters(job.bookId));
            JSONArray batch = new JSONArray(); int chars = 0;
            for (int index = next; index < target; index++) {
                revalidate(job); JSONObject chapter;
                activity(job, "loading", "history", index + 1, index + 1);
                try {
                    JSONObject entry = SpeechAnalysisRange.historyAt(required(books, job.bookId), catalog, index);
                    chapter = load(job, entry.optString("url"));
                    if (chapter.optInt("index", -1) != index || !job.bookId.equals(chapter.optString("bookId")))
                        throw new IllegalStateException("過去の話数と本文が一致しません。");
                } catch (Paused cancelled) { throw cancelled; }
                catch (Exception unavailable) {
                    check(job);
                    if (batch.length() > 0) memory = finishHistoryBatch(job, books, memory, batch);
                    Json.put(job.range, "historyBlockedIndex", index); Json.put(job.range, "memoryStatus", "partial");
                    Json.put(job.range, "historyError", "第" + (index + 1) + "話の本文を確認できないため、過去メモは第" + index + "話までです。取得できる対象話の解析を続けます。");
                    persist(job); emit(job); break;
                }
                JSONObject plan = SpeechPlan.create(job.bookId, chapter, Json.obj("useRuby", job.config.useRuby)); int size = textLength(plan);
                if (batch.length() > 0 && chars + size > 32000) {
                    memory = finishHistoryBatch(job, books, memory, batch); batch = new JSONArray(); chars = 0;
                }
                JSONObject saved = job.store.plan(job.bookId, chapter.optString("url"));
                if (batch.length() == 0 && SpeechPlan.compatible(saved, chapter) && "complete".equals(saved.optString("analysisStatus"))
                        && saved.optBoolean("memoryAssessed") && saved.optJSONObject("memoryDelta") != null
                        && plan.optString("inputHash").equals(saved.optString("memoryDeltaInputHash"))) {
                    memory = job.store.commitStoryMemory(job.bookId, job.range, new JSONArray().put(plan), job.store.cast(job.bookId), saved.optJSONObject("memoryDelta"), job.config.model);
                    Json.put(job.range, "historyThrough", index + 1);
                    Json.put(job.range, "historyCompleted", Math.min(job.range.optInt("historyTotal"), Math.max(0, index + 2 - job.range.optInt("historyFromNumber"))));
                    persist(job); emit(job); continue;
                }
                batch.put(plan); chars += size;
                if (size > 32000 || index + 1 == target) { memory = finishHistoryBatch(job, books, memory, batch); batch = new JSONArray(); chars = 0; }
            }
        }
        memory = job.store.storyMemoryBefore(job.bookId, target);
        boolean ready = memory.optInt("throughChapterIndex", -1) >= target - 1;
        Json.put(job.range, "memoryStatus", ready ? "ready" : "partial");
        if (!ready) {
            // Keep recent completed target chapters useful even when an older inaccessible chapter left a gap.
            JSONArray queue = Json.arr(job.range, "queue");
            for (int i = 0; i < job.range.optInt("completedChapters"); i++) {
                JSONObject entry = queue.optJSONObject(i), saved = job.store.plan(job.bookId, entry.optString("url"));
                JSONObject chapter = books.chapter(job.bookId, entry.optString("url"));
                if (chapter != null && saved.optInt("chapterIndex", -1) < target && "complete".equals(saved.optString("analysisStatus")) && saved.optBoolean("memoryAssessed")
                        && SpeechPlan.compatible(saved, chapter) && saved.optJSONObject("memoryDelta") != null)
                    memory = job.store.reapplyStoryMemory(job.bookId,
                            StoryMemory.merge(memory, saved.optJSONObject("memoryDelta"), new JSONArray().put(saved)), target);
            }
        }
        return memory;
    }

    private static void run(Job job) {
        ReaderStore books = new ReaderStore(job.context);
        try {
            while (job.range.optLong("completedChapters") < job.range.optLong("count")) {
                revalidate(job); JSONObject book = required(books, job.bookId); JSONArray catalog = books.chapters(job.bookId);
                int done = job.range.optInt("completedChapters"); JSONObject previous = null;
                if (done > 0) {
                    previous = books.chapter(job.bookId, Json.arr(job.range, "queue").optJSONObject(done - 1).optString("url"));
                    if (previous != null) previous = ChapterContent.withLinks(books, job.bookId, previous);
                }
                JSONObject item = SpeechAnalysisRange.current(job.range, book, catalog, previous);
                job.url = item.optString("url"); job.plan = new JSONObject(); Json.put(job.range, "status", "loading");
                JSONObject cachedPlan = job.store.plan(job.bookId, job.url); SpeechAnalysisRange.progress(job.range, cachedPlan);
                job.range.remove("analysisError"); Json.put(job.range, "error", "");
                persist(job); emit(job);
                activity(job, "loading", "chapter", item.optInt("index") + 1, item.optInt("index") + 1);
                JSONObject chapter = load(job); check(job);
                SpeechAnalysisRange.loaded(job.range, chapter, required(books, job.bookId), books.chapters(job.bookId));
                JSONObject prepared = job.store.prepareAnalysisChapter(job.bookId, chapter, job.range);
                job.plan = prepared.optJSONObject("plan"); job.cast = prepared.optJSONArray("cast");
                if (Json.arr(job.plan, "units").length() == 0) throw new IllegalArgumentException("読み上げる本文がありません。公開状態を公式画面で確認してください。");
                JSONObject memory = prepareMemory(job, books, chapter.optInt("index"));
                job.cast = job.store.cast(job.bookId); job.savedBatch = -1;
                Json.put(job.range, "status", "running"); SpeechAnalysisRange.progress(job.range, job.plan); persist(job); emit(job);
                SpeechAnalyzer.Progress progress = (plan, cast) -> {
                    job.plan = plan; job.cast = cast;
                    if (job.range.has("analysisActivity")) Json.put(plan, "analysisActivity", Json.copy(job.range.optJSONObject("analysisActivity")));
                    JSONObject current = books.chapter(job.bookId, job.url);
                    if (current == null || !SpeechPlan.compatible(plan, current)) {
                        job.invalidated = true; throw new IllegalStateException("本文が更新または削除されました。保存済み範囲を確認して再開してください。");
                    }
                    if (!job.cancelled.get()) revalidate(job);
                    String status = plan.optString("analysisStatus");
                    Json.put(job.range, "status", "paused".equals(status) || "error".equals(status) ? status : "running");
                    Json.put(job.range, "error", plan.optString("error"));
                    int batch = plan.optInt("batchIndex");
                    if (job.config.workflowVersion < 2 || job.savedBatch != batch || !"running".equals(status)) {
                        job.store.analysisCheckpoint(job.bookId, job.url, plan, cast, job.range); job.savedBatch = batch;
                    } else {
                        job.store.refreshAnalysis(job.bookId, job.url, plan, cast);
                        SpeechAnalysisRange.progress(job.range, plan); job.store.saveAnalysisRange(job.bookId, job.range);
                    }
                    publish(job); emit(job);
                };
                activityContext(job, "chapter", chapter.optInt("index") + 1, chapter.optInt("index") + 1);
                SpeechAnalyzer selected = analyzer(job, memory);
                JSONObject result = "chatgpt".equals(job.config.provider)
                        ? selected.analyze(job.plan, job.cast, job.config.model, () -> job.cancelled.get(), progress)
                        : selected.analyze(job.plan, job.cast, job.config.key, job.config.model, () -> job.cancelled.get(), progress);
                job.plan = result.optJSONObject("plan"); job.cast = result.optJSONArray("cast"); check(job);
                if (!"complete".equals(job.plan.optString("analysisStatus"))) throw new Paused();
                JSONObject current = books.chapter(job.bookId, job.url);
                if (current == null || !SpeechPlan.compatible(job.plan, current)) {
                    job.invalidated = true; throw new IllegalStateException("本文が更新または削除されました。解析範囲を確認してください。");
                }
                if (job.config.useStoryMemory) {
                    if (!job.plan.optBoolean("memoryAssessed")) {
                        Json.put(job.range, "analysisPhase", "memory"); Json.put(job.plan, "analysisPhase", "memory");
                        Json.put(job.plan, "analysisStatus", "running"); persist(job); emit(job);
                        JSONObject facts = summarizeBounded(job, memory, new JSONArray().put(job.plan));
                        verifySources(job, books, new JSONArray().put(job.plan));
                        Json.put(job.plan, "memoryDelta", facts.optJSONObject("memoryDelta")); Json.put(job.plan, "memoryAssessed", true);
                        Json.put(job.plan, "memoryDeltaInputHash", job.plan.optString("inputHash"));
                        Json.put(job.plan, "analysisPhase", ""); Json.put(job.plan, "analysisStatus", "complete");
                        job.cast = facts.optJSONArray("cast"); job.store.analysisCheckpoint(job.bookId, job.url, job.plan, job.cast, job.range);
                    }
                    int index = job.plan.optInt("chapterIndex", -1);
                    JSONObject prior = job.store.storyMemoryBefore(job.bookId, index);
                    if (prior.optInt("throughChapterIndex", -1) == index - 1) {
                        job.store.commitStoryMemory(job.bookId, job.range, new JSONArray().put(job.plan), job.cast,
                                job.plan.optJSONObject("memoryDelta"), job.config.model);
                        Json.put(job.range, "historyThrough", index + 1);
                    }
                }
                endActivity(job, "complete");
                job.store.saveAnalysisActivity(job.bookId, job.url, job.range);
                SpeechAnalysisRange.completed(job.range, current, job.plan); persist(job); emit(job);
            }
        } catch (Exception error) {
            boolean paused = error instanceof Paused || job.cancelled.get() || Thread.currentThread().isInterrupted();
            Json.put(job.range, "status", paused ? "paused" : "error");
            Json.put(job.range, "endedEarly", error instanceof SpeechAnalysisRange.EndedEarly);
            String message = "解析を完了できませんでした。接続・公開状態・利用枠を確認し、未解析部分から再開してください。";
            if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) message = error.getMessage();
            if (!job.plan.optString("error").isEmpty()) message = job.plan.optString("error");
            if (!job.config.key.isEmpty()) message = message.replace(job.config.key, "[非表示]");
            Json.put(job.range, "error", paused ? "" : Json.limit(message, 350));
            if (job.plan.has("analysisError")) Json.put(job.range, "analysisError", Json.copy(job.plan.optJSONObject("analysisError")));
            JSONObject memoryError = memoryDiagnostics(error);
            if (!paused && memoryError != null) Json.put(job.range, "analysisError", memoryError);
            if (paused && "pronunciation_changed".equals(job.plan.optString("analysisPauseReason")))
                Json.put(job.range, "analysisPauseReason", "pronunciation_changed");
            else job.range.remove("analysisPauseReason");
            endActivity(job, paused ? "paused" : "error");
            try {
                job.store.saveAnalysisActivity(job.bookId, job.url, job.range);
                persist(job);
            } catch (Exception unavailable) { job.persistenceFailed = true; publish(job); }
        } finally {
            books.close(); synchronized (SpeechAnalysis.class) { if (active == job) active = null; }
            emit(job);
        }
    }
    private static JSONObject load(Job job) throws Exception { return load(job, job.url); }
    private static JSONObject load(Job job, String url) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); AtomicReference<JSONObject> chapter = new AtomicReference<>(); AtomicReference<String> error = new AtomicReference<>();
        SpeechContent.load(job.context, job.bookId, url, new SpeechContent.Callback() {
            @Override public void loaded(JSONObject value) { chapter.set(value); ready.countDown(); }
            @Override public void failed(String message) { error.set(message); ready.countDown(); }
        });
        while (!ready.await(200, TimeUnit.MILLISECONDS)) check(job);
        check(job);
        if (chapter.get() == null) throw new IllegalStateException(error.get() == null ? "本文を取得できませんでした。" : error.get());
        return chapter.get();
    }
    private static void persist(Job job) {
        Json.put(job.range, "updatedAt", System.currentTimeMillis()); job.store.saveAnalysisRange(job.bookId, job.range); publish(job);
    }
    private static void publish(Job job) { job.latest = SpeechAnalysisRange.publicState(job.range); }
    /** Reopening a sheet must use the latest job phase, including resumed work and recovered pauses. */
    static JSONObject presentPlan(JSONObject saved, JSONObject range, String chapterUrl) {
        JSONObject plan = Json.copy(saved);
        if (plan.length() > 0) {
            JSONArray units = Json.arr(plan, "units"); int ready = 0, missingWords = 0;
            for (int i = 0; i < units.length(); i++) {
                JSONObject unit = units.optJSONObject(i); if (unit == null) continue;
                boolean valid = SpeechPronunciation.valid(unit); Json.put(unit, "pronunciationReady", valid);
                if (valid) {
                    ready++;
                    if (!unit.optBoolean("pronunciationLocked") && !WordProsody.scored(unit)) missingWords++;
                }
            }
            Json.put(plan, "pronunciationUnits", ready); Json.put(plan, "pronunciationTotalUnits", units.length());
            Json.put(plan, "missingPronunciationUnits", units.length() - ready);
            Json.put(plan, "missingWordUnits", missingWords);
        }
        if (plan.length() == 0 || range == null || !chapterUrl.equals(range.optString("currentChapterUrl"))) return plan;
        if (range.has("analysisActivity")) Json.put(plan, "analysisActivity", Json.copy(range.optJSONObject("analysisActivity")));
        String status = range.optString("status");
        if ("running".equals(status) || "queued".equals(status) || "loading".equals(status) || "pausing".equals(status)) {
            Json.put(plan, "analysisStatus", "running"); Json.put(plan, "error", ""); plan.remove("analysisError"); plan.remove("analysisPauseReason");
        } else if ("error".equals(status) || "paused".equals(status)) {
            Json.put(plan, "analysisStatus", status); Json.put(plan, "error", range.optString("error"));
            if (range.has("analysisError")) Json.put(plan, "analysisError", Json.copy(range.optJSONObject("analysisError")));
            else plan.remove("analysisError");
            if ("paused".equals(status) && "pronunciation_changed".equals(range.optString("analysisPauseReason")))
                Json.put(plan, "analysisPauseReason", "pronunciation_changed");
            else plan.remove("analysisPauseReason");
        }
        return plan;
    }
    private static void emit(Job job) {
        MAIN.post(() -> {
            Listener target = listener; if (target == null) return;
            try {
                JSONObject state = job.store.analysisState(job.bookId), range = state.optJSONObject("range");
                // Read on delivery. A callback from an older session cannot overwrite a new range.
                if (range == null || !job.id.equals(range.optString("jobId"))) return;
                JSONObject visible = job.persistenceFailed ? Json.copy(job.latest) : SpeechAnalysisRange.publicState(range);
                if (active == job && job.cancelled.get()) Json.put(visible, "status", "pausing");
                JSONObject plan = state.optJSONObject("plan");
                if (active == job && job.plan.length() > 0 && job.url.equals(range.optString("currentChapterUrl"))) {
                    // Deliver the current phase without persisting the full chapter on every phase change.
                    plan = Json.copy(plan);
                    for (String key : new String[]{"analysisPhase", "analysisMethod", "analysisMetrics", "analysisStatus", "analysisError", "analysisPauseReason", "error"})
                        if (job.plan.has(key)) Json.put(plan, key, job.plan.opt(key));
                }
                plan = presentPlan(plan, visible, visible.optString("currentChapterUrl"));
                boolean invalid = job.invalidated || plan != null && job.plan.length() > 0
                        && range.optString("currentChapterUrl").equals(job.plan.optString("chapterUrl"))
                        && !plan.optString("inputHash").equals(job.plan.optString("inputHash"));
                target.update(Json.obj("bookId", job.bookId, "chapterUrl", visible.optString("currentChapterUrl"),
                        "plan", plan == null ? new JSONObject() : plan, "cast", state.optJSONArray("cast"), "invalidated", invalid,
                        "status", plan != null && plan.length() > 0 ? plan.optString("analysisStatus", visible.optString("status")) : visible.optString("status"),
                        "error", plan != null && plan.length() > 0 ? plan.optString("error") : visible.optString("error"), "analysisRange", visible));
            } catch (Exception ignored) { }
        });
    }
}
