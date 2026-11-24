package jp.haruyalab.novelshelf;

import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Production memory persistence with disk-backed Android contract doubles, no provider calls. */
public final class StoryMemoryStoreTest {
    private static int checks;
    private static File root;
    private static SpeechStoreTestContext context;
    private static SpeechStore store;
    private interface Run { void run() throws Exception; }
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    private static void denied(Run action) throws Exception { try { action.run(); throw new AssertionError("must reject"); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; } }
    private static SpeechStore reopen() { return new SpeechStore(new SpeechStoreTestContext(root)); }
    private static JSONObject range(String id, String job) throws Exception {
        ReaderStore.exists(context, id, true);
        JSONObject value = Json.obj("version", SpeechAnalysisRange.VERSION, "bookId", id, "jobId", job);
        store.beginAnalysisRange(id, value); return value;
    }
    private static JSONObject plan(String id, int index) {
        return SpeechPlan.create(id, Json.obj("bookId", id, "url", "https://kakuyomu.jp/works/190/episodes/" + (1000 + index),
                "index", index, "title", "自作検査" + index, "paragraphs", new JSONArray().put(Json.obj("text", "検査用の自作本文" + index + "。"))), Json.obj("useRuby", true));
    }
    private static JSONObject fact(String id, String text, JSONObject plan) {
        return Json.obj("id", id, "type", "event", "subjectId", "", "objectId", "", "text", text, "uncertain", false,
                "citations", new JSONArray().put(Json.obj("chapterUrl", plan.optString("chapterUrl"), "unitId", Json.arr(plan, "units").optJSONObject(0).optString("unitId"))));
    }
    private static JSONObject delta(JSONObject... facts) { JSONArray list = new JSONArray(); for (JSONObject fact : facts) list.put(fact); return Json.obj("facts", list); }
    private static JSONObject find(JSONObject memory, String id) { JSONArray facts = Json.arr(memory, "facts"); for (int i = 0; i < facts.length(); i++) if (id.equals(facts.optJSONObject(i).optString("id"))) return facts.optJSONObject(i); return null; }
    private static JSONObject commit(String id, JSONObject job, JSONObject plan, JSONObject... facts) {
        return store.commitStoryMemory(id, job, new JSONArray().put(plan), new JSONArray(), delta(facts), "fixture-model");
    }
    private static File memoryFile(String id, int index) { return new File(new File(new File(context.getFilesDir(), "speech"), SpeechStore.key(id)), String.format(java.util.Locale.ROOT, "story-memory/s_%010d.json", index)); }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("tsuzuki-story-memory-store-").toFile();
        context = new SpeechStoreTestContext(root); store = new SpeechStore(context);
        try {
            historicalBoundaries(); manualPriority(); manualTemporalBoundaries(); legacyManualMigration();
            invalidAndAtomicWrites(); changedSourcesAndOwnership();
            System.out.println("StoryMemoryStore production persistence passed: " + checks);
            System.out.println("Scope: historical snapshots, provenance, temporal manual edits/deletions, legacy migration, idempotence, rollback, backup recovery, invalidation, ownership and deletion.");
            System.out.println("No Android device durability or GPT semantic quality is inferred from these fixtures.");
        } finally { remove(root); }
    }
    private static void historicalBoundaries() throws Exception {
        String id = "history"; JSONObject job = range(id, "job_history"), p0 = plan(id, 0), p1 = plan(id, 1), p2 = plan(id, 2);
        eq(store.storyMemoryBefore(id, 0).optInt("throughChapterIndex"), -1);
        JSONObject first = commit(id, job, p0, fact("arrival", "主人公が到着した。", p0));
        eq(first.optInt("throughChapterIndex"), 0); eq(first.optLong("revision"), 1L);
        eq(Json.arr(reopen().storyMemoryBefore(id, 0), "facts").length(), 0);
        eq(find(reopen().storyMemoryBefore(id, 1), "arrival").optString("text"), "主人公が到着した。");
        eq(commit(id, job, p0, fact("arrival", "同じ本文の再保存は重複しない。", p0)).optLong("revision"), 1L);
        denied(() -> commit(id, job, p2, fact("future", "途中の話は未確認。", p2)));
        commit(id, job, p1, fact("secret", "第2話で初めて判明した。", p1));
        yes(find(store.storyMemoryBefore(id, 1), "secret") == null, "later facts never leak into earlier chapter");
        yes(find(store.storyMemoryBefore(id, 2), "secret") != null, "next chapter receives completed facts");
        eq(store.storyMemorySources(id, 2).length(), 2);
        eq(store.storyMemorySources(id, 2).optJSONObject(1).optString("hash"), p1.optString("inputHash"));
        range("other", "job_other"); eq(Json.arr(store.storyMemoryBefore("other", 20), "facts").length(), 0);
    }
    private static void manualPriority() throws Exception {
        String id = "manual"; JSONObject job = range(id, "job_manual"), p0 = plan(id, 0), p1 = plan(id, 1), p2 = plan(id, 2);
        store.saveCast(id, new JSONArray().put(Json.obj("id", "person", "name", "手動の配役", "offlineVoice", "zundamon", "style", "固定")));
        commit(id, job, p0, fact("state", "自動の記録。", p0), fact("hidden", "削除する記録。", p0));
        JSONObject edited = store.editStoryMemory(id, 1, new JSONArray().put(Json.obj("id", "state", "text", "利用者の修正。", "uncertain", true)), new JSONArray().put("hidden"));
        yes(find(edited, "state").optBoolean("manual"), "manual edit is pinned"); yes(find(edited, "state").optBoolean("uncertain"), "uncertainty kept");
        eq(edited.optLong("revision"), 2L); yes(find(edited, "hidden") == null, "delete hides existing fact");
        JSONObject next = commit(id, job, p1, fact("state", "AIが上書きを試みる。", p1), fact("hidden", "削除済み項目の再出現。", p1));
        eq(find(next, "state").optString("text"), "利用者の修正。"); yes(find(next, "hidden") == null, "deletion tombstone survives AI update");
        eq(next.optLong("revision"), 3L); eq(reopen().storyMemoryBefore(id, 2).optLong("revision"), 3L);
        JSONObject created = store.editStoryMemory(id, 2, new JSONArray().put(Json.obj("id", "", "text", "利用者が追加したメモ。", "uncertain", false)), new JSONArray());
        JSONObject newFact = null; JSONArray facts = Json.arr(created, "facts");
        for (int i = 0; i < facts.length(); i++) if (facts.optJSONObject(i).optString("id").startsWith("m_")) newFact = facts.optJSONObject(i);
        yes(newFact != null, "new note has generated ID"); eq(Json.arr(newFact, "citations").length(), 0); yes(newFact.optBoolean("manual"), "manual new note is accepted without fabricated source");
        JSONObject later = commit(id, job, p2, fact("later", "後の出来事。", p2)); eq(later.optLong("revision"), 5L);
        eq(find(later, "state").optString("text"), "利用者の修正。");
        JSONObject fixed = null; JSONArray cast = store.cast(id);
        for (int i = 0; i < cast.length(); i++) if ("person".equals(cast.optJSONObject(i).optString("id"))) fixed = cast.optJSONObject(i);
        yes(fixed != null, "manual cast remains present"); eq(fixed.optString("offlineVoice"), "zundamon");
        denied(() -> store.editStoryMemory(id, 3, new JSONArray().put(Json.obj("id", "missing", "text", "不明")), new JSONArray()));
        denied(() -> store.editStoryMemory(id, 3, new JSONArray().put(Json.obj("id", "state", "text", " ")), new JSONArray()));
        String longText = new String(new char[321]).replace('\0', 'あ');
        denied(() -> store.editStoryMemory(id, 3, new JSONArray().put(Json.obj("id", "state", "text", longText)), new JSONArray()));
        denied(() -> store.editStoryMemory(id, 3, new JSONArray().put(Json.obj("id", "state", "text", "変更", "uncertain", "true")), new JSONArray()));
    }
    private static void invalidAndAtomicWrites() throws Exception {
        String id = "atomic"; JSONObject job = range(id, "job_atomic"), p0 = plan(id, 0), p1 = plan(id, 1);
        JSONObject wrong = fact("bad", "別章を根拠にする。", p1);
        denied(() -> commit(id, job, p0, wrong)); eq(store.storyMemoryBefore(id, 1).optInt("throughChapterIndex"), -1);
        JSONObject person = fact("bad_person", "未登録人物。", p0); Json.put(person, "subjectId", "unknown_person");
        denied(() -> commit(id, job, p0, person));
        commit(id, job, p0, fact("ok", "保存済み。", p0));
        AtomicFile.failNextWrite(memoryFile(id, 1), 20);
        denied(() -> commit(id, job, p1, fact("next", "途中で失敗する。", p1)));
        eq(reopen().storyMemoryBefore(id, 2).optInt("throughChapterIndex"), 0); yes(find(store.storyMemoryBefore(id, 2), "next") == null, "failed write never exposes partial facts");
        commit(id, job, p1, fact("next", "再試行成功。", p1)); eq(reopen().storyMemoryBefore(id, 2).optInt("throughChapterIndex"), 1);
        File path = memoryFile(id, 1); Files.move(path.toPath(), new File(path + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        eq(reopen().storyMemoryBefore(id, 2).optInt("throughChapterIndex"), 1); yes(path.exists(), "backup-only snapshot is restored");
        File manual = new File(path.getParentFile(), "manual.json"); AtomicFile.failNextFinish(manual);
        denied(() -> store.editStoryMemory(id, 2, new JSONArray().put(Json.obj("id", "ok", "text", "保存失敗")), new JSONArray()));
        eq(find(reopen().storyMemoryBefore(id, 2), "ok").optString("text"), "保存済み。");
    }
    private static void manualTemporalBoundaries() throws Exception {
        String id = "manual-timeline"; JSONObject job = range(id, "job_timeline"), p0 = plan(id, 0), p1 = plan(id, 1), p2 = plan(id, 2);
        commit(id, job, p0, fact("arrival", "最初の自動記録。", p0));
        commit(id, job, p1, fact("secret", "第2話で判明した情報。", p1));
        long oldRevision = store.storyMemoryBefore(id, 1).optLong("revision");
        JSONObject future = store.editStoryMemory(id, 2, new JSONArray()
                .put(Json.obj("id", "secret", "text", "第3話以降に使う手動修正。"))
                .put(Json.obj("id", "", "text", "第3話以降の利用者メモ。")), new JSONArray().put("arrival"));
        JSONObject note = null;
        JSONArray facts = Json.arr(future, "facts");
        for (int i = 0; i < facts.length(); i++) if (facts.optJSONObject(i).optString("id").startsWith("m_")) note = facts.optJSONObject(i);
        yes(note != null, "future manual note created"); String noteId = note.optString("id");
        JSONObject earlier = reopen().storyMemoryBefore(id, 1);
        eq(find(earlier, "arrival").optString("text"), "最初の自動記録。");
        yes(find(earlier, "secret") == null && find(earlier, noteId) == null, "future manual facts and their later citations do not enter older memory");
        eq(earlier.optLong("revision"), oldRevision); eq(Json.arr(store.storyMemoryBefore(id, 0), "facts").length(), 0);
        JSONObject repeated = commit(id, job, p0);
        yes(find(repeated, "arrival") != null && find(repeated, "secret") == null && find(repeated, noteId) == null,
                "idempotent past commit also scopes the live overlay to its result chapter");
        store.editStoryMemory(id, 1, new JSONArray().put(Json.obj("id", "arrival", "text", "第2話の固定値。")), new JSONArray());
        eq(find(store.storyMemoryBefore(id, 1), "arrival").optString("text"), "第2話の固定値。");
        yes(find(store.storyMemoryBefore(id, 2), "arrival") == null, "later removal remains effective after an earlier edit");
        store.editStoryMemory(id, 2, new JSONArray().put(Json.obj("id", "secret", "text", "同じ時点の修正版。")), new JSONArray());
        store.editStoryMemory(id, 3, new JSONArray().put(Json.obj("id", "secret", "text", "第4話以降の新しい固定値。")), new JSONArray().put(noteId));
        eq(find(reopen().storyMemoryBefore(id, 2), "secret").optString("text"), "同じ時点の修正版。");
        yes(find(store.storyMemoryBefore(id, 2), noteId) != null, "later deletion does not erase the earlier manual note");
        eq(find(store.storyMemoryBefore(id, 3), "secret").optString("text"), "第4話以降の新しい固定値。");
        yes(find(store.storyMemoryBefore(id, 3), noteId) == null, "deletion starts at the saved chapter");
        JSONObject next = commit(id, job, p2, fact("secret", "AIによる更新候補。", p2), fact("arrival", "削除済み項目の再生成。", p2));
        eq(find(next, "secret").optString("text"), "第4話以降の新しい固定値。");
        yes(find(next, "arrival") == null && find(next, noteId) == null, "same-time manual locks and removals survive automatic commits");
        JSONObject raw = Json.parse(new String(Files.readAllBytes(memoryFile(id, 2).toPath()), java.nio.charset.StandardCharsets.UTF_8));
        JSONArray stored = Json.arr(raw.optJSONObject("memory"), "facts");
        for (int i = 0; i < stored.length(); i++) yes(!stored.optJSONObject(i).optBoolean("manual"), "derived snapshots never bake live manual facts into history");
        store.invalidateStoryMemoryFrom(id, 0);
        eq(Json.arr(store.storyMemoryBefore(id, 0), "facts").length(), 0);
        eq(find(store.storyMemoryBefore(id, 1), "arrival").optString("text"), "第2話の固定値。");
        yes(find(store.storyMemoryBefore(id, 1), "secret") == null && find(store.storyMemoryBefore(id, 1), noteId) == null,
                "source invalidation cannot shift future manual edits backwards");
        eq(find(store.storyMemoryBefore(id, 2), "secret").optString("text"), "同じ時点の修正版。");
        eq(find(store.storyMemoryBefore(id, 3), "secret").optString("text"), "第4話以降の新しい固定値。");
        JSONObject rebuilt = commit(id, job, p0, fact("arrival", "再構築した自動記録。", p0));
        eq(find(rebuilt, "arrival").optString("text"), "第2話の固定値。"); yes(find(rebuilt, "secret") == null, "past reconstruction cannot inherit a future fixed fact");
    }
    private static void legacyManualMigration() throws Exception {
        String id = "legacy-manual"; JSONObject job = range(id, "job_legacy_manual"), p0 = plan(id, 0), p1 = plan(id, 1);
        commit(id, job, p0, fact("arrival", "以前の自動情報。", p0)); commit(id, job, p1, fact("secret", "後の自動情報。", p1));
        JSONObject fixed = fact("secret", "旧形式の手動固定。", p1); Json.put(fixed, "manual", true);
        File file = new File(memoryFile(id, 1).getParentFile(), "manual.json");
        Files.write(file.toPath(), Json.obj("facts", new JSONArray().put(fixed), "removedIds", new JSONArray().put("arrival"), "revision", 4)
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JSONObject oldSnapshot = Json.parse(new String(Files.readAllBytes(memoryFile(id, 0).toPath()), java.nio.charset.StandardCharsets.UTF_8));
        Json.arr(oldSnapshot.optJSONObject("memory"), "facts").put(fixed);
        Files.write(memoryFile(id, 0).toPath(), oldSnapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JSONObject before = store.storyMemoryBefore(id, 1);
        yes(find(before, "arrival") != null && find(before, "secret") == null, "unscoped legacy overlay and baked future manual facts are excluded from past chapters");
        eq(find(store.storyMemoryBefore(id, 2), "secret").optString("text"), "旧形式の手動固定。");
        yes(find(store.storyMemoryBefore(id, 2), "arrival") == null, "legacy deletion starts at the conservative inferred boundary");
        JSONObject migrated = Json.parse(new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        eq(migrated.optInt("version"), 2); eq(Json.arr(migrated, "entries").optJSONObject(0).optInt("fromIndex"), 2);
        store.invalidateStoryMemoryFrom(id, 0);
        eq(Json.arr(reopen().storyMemoryBefore(id, 1), "facts").length(), 0);
        eq(find(reopen().storyMemoryBefore(id, 2), "secret").optString("text"), "旧形式の手動固定。");
        String orphan = "legacy-no-snapshot"; range(orphan, "job_orphan");
        File missing = new File(memoryFile(orphan, 0).getParentFile(), "manual.json"); missing.getParentFile().mkdirs();
        Files.write(missing.toPath(), Json.obj("facts", new JSONArray().put(fixed), "removedIds", new JSONArray(), "revision", 1)
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        eq(Json.arr(store.storyMemoryBefore(orphan, 20), "facts").length(), 0);
        yes(find(store.storyMemoryBefore(orphan, Integer.MAX_VALUE), "secret") != null, "unknown-scope legacy notes are retained without guessing an earlier activation chapter");
    }
    private static void changedSourcesAndOwnership() throws Exception {
        String id = "invalidate"; JSONObject job = range(id, "job_first"), p0 = plan(id, 0), p1 = plan(id, 1), p2 = plan(id, 2);
        commit(id, job, p0, fact("old", "旧本文の記録。", p0)); commit(id, job, p1); commit(id, job, p2);
        store.editStoryMemory(id, 3, new JSONArray().put(Json.obj("id", "old", "text", "手動固定を残す。")), new JSONArray());
        store.invalidateStoryMemoryFrom(id, 1);
        eq(store.storyMemoryBefore(id, 10).optInt("throughChapterIndex"), 0); eq(store.storyMemorySources(id, 10).length(), 1);
        eq(find(store.storyMemoryBefore(id, 10), "old").optString("text"), "手動固定を残す。");
        commit(id, job, p1); commit(id, job, p2);
        JSONObject updated = Json.copy(p1); Json.put(updated, "inputHash", "changed-source-hash");
        commit(id, job, updated); eq(store.storyMemoryBefore(id, 10).optInt("throughChapterIndex"), 1);
        yes(!memoryFile(id, 2).exists(), "replacing a historical snapshot invalidates later derived snapshots");
        JSONObject newJob = range(id, "job_second"); denied(() -> commit(id, job, p2));
        commit(id, newJob, p2); store.deleteBook(id);
        denied(() -> commit(id, newJob, p2)); yes(!memoryFile(id, 2).exists(), "late worker cannot resurrect deleted files");
        ReaderStore.exists(context, id, false); denied(() -> store.storyMemoryBefore(id, 20));
    }
    private static void remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); file.delete(); }
}
