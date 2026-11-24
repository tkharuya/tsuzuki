package jp.haruyalab.novelshelf;

import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Reading edits exercise the real atomic store; Android persistence boundaries are JVM doubles. */
public final class SpeechPronunciationStoreTest {
    private static int checks;
    private static File root;
    private static SpeechStoreTestContext context;
    private static SpeechStore store;
    private static final String BOOK = "reading-store", URL = "local://pronunciation/chapter";
    private interface Run { void run() throws Exception; }
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    private static void denied(Run run) throws Exception {
        try { run.run(); throw new AssertionError("operation must be rejected"); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
    private static JSONObject chapter(String text, String html) {
        return Json.obj("bookId", BOOK, "url", URL, "index", 0, "title", "自作の読み検査",
                "paragraphs", new JSONArray().put(Json.obj("text", text, "html", html)));
    }
    private static JSONObject first(JSONObject plan) { return Json.arr(plan, "units").optJSONObject(0); }
    private static JSONObject reading(JSONObject unit, String... values) {
        JSONObject template = SpeechPronunciation.template(unit);
        JSONArray parts = Json.arr(template, "parts"); eq(parts.length(), values.length);
        for (int i = 0; i < parts.length(); i++) Json.put(parts.optJSONObject(i), "reading", values[i]);
        return template;
    }
    private static JSONObject fresh() {
        JSONObject plan = SpeechPlan.create(BOOK, chapter("花は美しい。", ""), Json.obj("useRuby", true));
        Json.put(plan, "pronunciationVersion", SpeechPronunciation.VERSION);
        store.savePlan(BOOK, URL, plan); return plan;
    }
    private static void delete(File file) {
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); }
        file.delete();
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("tsuzuki-reading-store-").toFile();
        context = new SpeechStoreTestContext(root); store = new SpeechStore(context);
        ReaderStore.exists(context, BOOK, true);
        try {
            rubyUpgrade(); independentEdits(); staleAndAtomicWrites(); changedCachedBody(); concurrentCorrection(); ownership();
            System.out.println("SpeechPronunciationStore production persistence passed: " + checks);
            System.out.println("Synthetic text, real Store/Plan, disk-backed Android doubles; no GPT call or Android device execution.");
        } finally { delete(root); }
    }
    private static void rubyUpgrade() throws Exception {
        JSONObject body = chapter("🌟今日は葉の話。母は花を運ぶ。", "🌟<ruby>今日<rt>きょう</rt></ruby>は<ruby>葉<rt>は</rt></ruby>の話。母は花を運ぶ。");
        JSONObject original = SpeechPlan.create(BOOK, body, Json.obj("useRuby", true));
        JSONObject unit = first(original); JSONArray ruby = Json.arr(unit, "spokenRuby");
        eq(ruby.length(), 2); eq(ruby.optJSONObject(0).optInt("start"), 2); eq(ruby.optJSONObject(0).optInt("end"), 5);
        eq(ruby.optJSONObject(1).optInt("start"), 6); eq(ruby.optJSONObject(1).optInt("end"), 7);
        eq(unit.optString("spokenText"), "🌟きょうははの話。母は花を運ぶ。");
        String text = unit.optString("text"), spoken = unit.optString("spokenText"), hash = original.optString("inputHash"), id = unit.optString("unitId");
        Json.put(unit, "speakerId", "narrator"); Json.put(unit, "analyzed", true); Json.put(unit, "locked", true);
        Json.put(unit, "emotion", "calm"); Json.put(unit, "rate", .9); unit.remove("spokenRuby");
        store.savePlan(BOOK, URL, original); store.savePosition(BOOK, URL, 0);
        String position = store.position(BOOK).toString(), cast = store.cast(BOOK).toString();
        JSONObject refreshed = store.prepareForAnalysis(BOOK, body), after = first(refreshed);
        eq(after.optString("text"), text); eq(after.optString("spokenText"), spoken); eq(after.optString("unitId"), id);
        eq(refreshed.optString("inputHash"), hash); eq(Json.arr(after, "spokenRuby").toString(), ruby.toString());
        yes(after.optBoolean("locked") && after.optBoolean("analyzed"), "old manual acting survives metadata upgrade");
        eq(after.optDouble("rate"), .9); eq(after.optString("emotion"), "calm");
        eq(store.position(BOOK).toString(), position); eq(store.cast(BOOK).toString(), cast);
        yes(!SpeechPlan.refreshRubyMetadata(refreshed, body), "second refresh needs no rewrite");
        JSONObject noRuby = SpeechPlan.create(BOOK, body, Json.obj("useRuby", false));
        eq(Json.arr(first(noRuby), "spokenRuby").length(), 0); eq(first(noRuby).optString("spokenText"), text);
        JSONObject stale = Json.copy(original); Json.put(first(stale), "spokenText", "違う本文");
        denied(() -> SpeechPlan.refreshRubyMetadata(stale, body));
    }
    private static void independentEdits() throws Exception {
        JSONObject plan = fresh(), unit = first(plan); String id = unit.optString("unitId");
        String body = unit.optString("text"), source = unit.optString("spokenText"), cast = store.cast(BOOK).toString();
        store.savePosition(BOOK, URL, 0); String position = store.position(BOOK).toString();
        JSONObject state = store.pronunciationState(BOOK, URL, id);
        yes(!state.optBoolean("hasPronunciation"), "unmodified editor template is not presented as a finished analysis");
        JSONObject incoming = reading(unit, "ハナワウツクシー。");
        JSONObject saved = store.savePronunciation(BOOK, URL, id, incoming, false), edited = first(saved);
        yes(SpeechPronunciation.valid(edited) && edited.optBoolean("pronunciationLocked"), "manual reading saved and pinned");
        yes(!edited.optBoolean("locked") && !edited.optBoolean("analyzed"), "reading edit does not lock or complete acting");
        eq(edited.optString("speakerId"), unit.optString("speakerId")); eq(edited.optString("text"), body); eq(edited.optString("spokenText"), source);
        eq(saved.optInt("analyzedUnits"), 0); eq(store.cast(BOOK).toString(), cast); eq(store.position(BOOK).toString(), position);
        String fixedReading = edited.optJSONObject("pronunciation").toString();
        JSONObject acting = store.updateUnit(BOOK, URL, id, Json.obj("emotion", "joy", "rate", 1.15));
        eq(first(acting).optJSONObject("pronunciation").toString(), fixedReading);
        yes(first(acting).optBoolean("locked"), "acting edit still uses its own lock"); eq(acting.optInt("analyzedUnits"), 1);
        JSONObject candidate = Json.copy(plan);
        Json.put(first(candidate), "pronunciation", SpeechPronunciation.validateManual(unit, Json.arr(reading(unit, "ハナハウツクシイ。"), "parts")));
        store.checkpoint(BOOK, URL, candidate, store.cast(BOOK));
        JSONObject merged = first(store.plan(BOOK, URL));
        eq(merged.optJSONObject("pronunciation").toString(), fixedReading); eq(merged.optString("emotion"), "joy");
        yes(merged.optBoolean("pronunciationLocked") && merged.optBoolean("locked"), "both independent corrections survive stale checkpoint");
        JSONObject reset = store.savePronunciation(BOOK, URL, id, null, true);
        yes(!first(reset).has("pronunciation") && !first(reset).optBoolean("pronunciationLocked"), "explicit reset removes only reading");
        eq(first(reset).optString("emotion"), "joy"); yes(first(reset).optBoolean("locked"), "reading reset preserves acting lock");
        eq(reset.optInt("analyzedUnits"), 0); eq(store.position(BOOK).toString(), position);
        store.checkpoint(BOOK, URL, candidate, store.cast(BOOK));
        yes(!first(store.plan(BOOK, URL)).has("pronunciation"), "stale checkpoint sees the reading reset revision");
    }
    private static void staleAndAtomicWrites() throws Exception {
        JSONObject plan = fresh(), unit = first(plan); String id = unit.optString("unitId");
        JSONObject good = reading(unit, "ハナワウツクシー。");
        store.savePronunciation(BOOK, URL, id, good, false); String before = store.plan(BOOK, URL).toString();
        JSONObject wrongHash = Json.copy(good); Json.put(wrongHash, "sourceHash", "stale");
        denied(() -> store.savePronunciation(BOOK, URL, id, wrongHash, false)); eq(store.plan(BOOK, URL).toString(), before);
        JSONObject wrongPart = Json.copy(good); Json.put(Json.arr(wrongPart, "parts").optJSONObject(0), "partId", "p_missing");
        denied(() -> store.savePronunciation(BOOK, URL, id, wrongPart, false)); eq(store.plan(BOOK, URL).toString(), before);
        AtomicFile.failNextStart(new File(new File(new File(context.getFilesDir(), "speech"), SpeechStore.key(BOOK)), SpeechStore.key(URL) + ".json"));
        denied(() -> store.savePronunciation(BOOK, URL, id, reading(unit, "ハナワキレイ。"), false));
        eq(new SpeechStore(new SpeechStoreTestContext(root)).plan(BOOK, URL).toString(), before);
        denied(() -> store.savePronunciation(BOOK, URL, "missing", good, false)); eq(store.plan(BOOK, URL).toString(), before);
    }
    private static void concurrentCorrection() throws Exception {
        for (int round = 0; round < 4; round++) {
            JSONObject plan = fresh(), unit = first(plan); String id = unit.optString("unitId");
            JSONObject ai = Json.copy(plan); Json.put(first(ai), "analyzed", true);
            Json.put(first(ai), "pronunciation", SpeechPronunciation.validateManual(unit, Json.arr(reading(unit, "ハナハウツクシイ。"), "parts")));
            CountDownLatch start = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread human = new Thread(() -> { try { start.await(); store.savePronunciation(BOOK, URL, id, reading(unit, "ハナワウツクシー。"), false); } catch (Throwable t) { failure.set(t); } });
            Thread checkpoint = new Thread(() -> { try { start.await(); store.checkpoint(BOOK, URL, ai, store.cast(BOOK)); } catch (Throwable t) { failure.set(t); } });
            human.start(); checkpoint.start(); start.countDown(); human.join(5000); checkpoint.join(5000);
            yes(!human.isAlive() && !checkpoint.isAlive(), "concurrent writes terminate");
            if (failure.get() != null) throw new AssertionError("concurrent save failed", failure.get());
            JSONObject saved = first(store.plan(BOOK, URL));
            eq(SpeechPronunciation.text(saved), "ハナワウツクシー。");
            yes(saved.optBoolean("pronunciationLocked"), "concurrent manual reading wins");
            yes(!saved.optBoolean("locked"), "reading update does not create acting lock");
            eq(saved.optString("text"), "花は美しい。");
        }
    }
    private static void changedCachedBody() throws Exception {
        JSONObject body = chapter("葉は花を見る。", "<ruby>葉<rt>は</rt></ruby>は花を見る。");
        JSONObject plan = SpeechPlan.create(BOOK, body, Json.obj("useRuby", true)), unit = first(plan);
        store.savePlan(BOOK, URL, plan);
        String id = unit.optString("unitId"); JSONObject fixed = reading(unit, "ハワハナオミル。");
        store.savePronunciation(BOOK, URL, id, fixed, false, body);
        String saved = store.plan(BOOK, URL).toString();
        JSONObject changed = chapter("葉は花を見る。", "<ruby>葉<rt>よう</rt></ruby>は花を見る。");
        denied(() -> store.savePronunciation(BOOK, URL, id, fixed, false, changed));
        eq(store.plan(BOOK, URL).toString(), saved);
        denied(() -> store.savePronunciation(BOOK, URL, id, null, true, changed));
        eq(store.plan(BOOK, URL).toString(), saved);
        denied(() -> store.savePronunciation(BOOK, URL, id, fixed, false, chapter("母は花を見る。", "")));
        eq(store.plan(BOOK, URL).toString(), saved);
        denied(() -> store.savePronunciation(BOOK, URL, id, fixed, false, null));
        eq(store.plan(BOOK, URL).toString(), saved);
    }
    private static void ownership() throws Exception {
        JSONObject plan = fresh(); String id = first(plan).optString("unitId");
        ReaderStore.exists(context, BOOK, false);
        denied(() -> store.pronunciationState(BOOK, URL, id));
        denied(() -> store.savePronunciation(BOOK, URL, id, null, true));
        ReaderStore.exists(context, BOOK, true);
    }
}
