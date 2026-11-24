package jp.haruyalab.novelshelf;

import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;

/** Real range/plan persistence with the existing disk-backed Android boundary doubles. */
public final class SpeechAnalysisRangeStoreTest {
    private static int checks;
    private interface Run { void run() throws Exception; }
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void denied(Run run) throws Exception { try { run.run(); throw new AssertionError("stale persistence accepted"); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; } }
    private static void remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); file.delete(); }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("speech-range-store-").toFile();
        try {
            SpeechStoreTestContext context = new SpeechStoreTestContext(root); SpeechStore store = new SpeechStore(context);
            String id = "fixture", url = "local:fixture/0"; ReaderStore.exists(context, id, true);
            JSONObject body = Json.obj("bookId", id, "url", url, "index", 0, "title", "自作の保存試験", "paragraphs", new JSONArray().put(Json.obj("text", "最初の自作本文。")));
            JSONObject plan = store.prepareForAnalysis(id, body); store.savePosition(id, url, 0, plan.optString("inputHash"));
            String position = store.position(id).toString();
            JSONObject book = Json.obj("id", id, "source", "local", "url", "local:fixture", "tocComplete", true, "total", 1);
            JSONObject range = SpeechAnalysisRange.create(book, new JSONArray().put(Json.obj("url", url, "index", 0, "title", "自作")), 1, 1);
            Json.put(range, "jobId", "session-1"); Json.put(range, "status", "running"); store.beginAnalysisRange(id, range);
            eq(new SpeechStore(context).analysisRange(id).toString(), range.toString());
            JSONArray cast = store.cast(id); Json.put(Json.arr(plan, "units").optJSONObject(0), "analyzed", true);
            store.analysisCheckpoint(id, url, plan, cast, range); eq(store.position(id).toString(), position);
            eq(store.plan(id, url).optInt("analyzedUnits"), 1);
            JSONObject changed = Json.copy(body); Json.put(changed, "paragraphs", new JSONArray().put(Json.obj("text", "更新された自作本文。")));
            JSONObject replacement = store.prepareForAnalysis(id, changed);
            yes(!replacement.optString("inputHash").equals(plan.optString("inputHash")), "updated body rebuilds metadata");
            eq(store.position(id).toString(), position); // Even stale playback positions are not moved by analysis.
            denied(() -> store.analysisCheckpoint(id, url, plan, cast, range));
            String current = store.analysisRange(id).toString();
            File rangeFile = new File(new File(new File(context.getFilesDir(), "speech"), SpeechStore.key(id)), "analysis-range.json");
            JSONObject attempted = Json.copy(range); Json.put(attempted, "completedChapters", 1);
            AtomicFile.failNextWrite(rangeFile, 11); denied(() -> store.saveAnalysisRange(id, attempted));
            eq(new SpeechStore(context).analysisRange(id).toString(), current);
            JSONObject newSession = Json.copy(range); Json.put(newSession, "jobId", "session-2"); store.beginAnalysisRange(id, newSession);
            denied(() -> store.saveAnalysisRange(id, range));
            denied(() -> store.analysisCheckpoint(id, url, replacement, cast, range));
            eq(store.analysisRange(id).optString("jobId"), "session-2");
            store.deleteBook(id); // Real controller removes speech files before deleting the SQLite row.
            denied(() -> store.prepareAnalysisChapter(id, body, newSession));
            denied(() -> store.saveAnalysisRange(id, newSession));
            yes(!rangeFile.getParentFile().exists(), "range ownership rejects preparation while the book row still exists");
            ReaderStore.exists(context, id, false);
            denied(() -> store.saveAnalysisRange(id, newSession)); denied(() -> store.beginAnalysisRange(id, newSession));
            denied(() -> store.prepareForAnalysis(id, body));
            eq(store.analysisState(id).length(), 0); yes(!rangeFile.getParentFile().exists(), "late callbacks never recreate deleted book files");
            System.out.println("SpeechAnalysisRangeStore production checks passed: " + checks);
            System.out.println("Real SpeechStore, synthetic book-existence/AtomicFile/preferences boundaries; no Android durability or SQLite claim.");
        } finally { remove(root); }
    }
}
