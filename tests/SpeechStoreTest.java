package jp.haruyalab.novelshelf;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs unmodified production SpeechStore on a JVM with narrowly scoped Android doubles.
 * Tests application persistence/merge/error logic. It does NOT test AndroidKeyStore,
 * Android SharedPreferences scheduling, actual AtomicFile durability, or SQLite.
 */
public final class SpeechStoreTest {
    private static int checks;
    private static final String A = "book-a", B = "book-b";
    private static final String URL = "local://fixture/chapter-1", URL2 = "local://fixture/chapter-2";
    private static final String CREDENTIAL_SENTINEL = "OPAQUE_CIPHERTEXT_FIXTURE_NOT_AN_API_KEY";
    private static File root;
    private static SpeechStoreTestContext context;
    private static SpeechStore store;
    private interface Operation { void run() throws Exception; }
    private static void yes(boolean actual, String message) { checks++; if (!actual) throw new AssertionError(message); }
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual); }
    private static void same(Object actual, Object expected) { eq(canonical(actual), canonical(expected)); }
    private static String canonical(Object value) {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject)value; List<String> keys = new ArrayList<>(); for (Iterator<String> it = o.keys(); it.hasNext();) keys.add(it.next()); Collections.sort(keys);
            StringBuilder out = new StringBuilder("{"); for (String k : keys) out.append(JSONObject.quote(k)).append(':').append(canonical(o.opt(k))).append(','); return out.append('}').toString();
        }
        if (value instanceof JSONArray) { JSONArray a = (JSONArray)value; StringBuilder out = new StringBuilder("["); for (int i = 0; i < a.length(); i++) out.append(canonical(a.opt(i))).append(','); return out.append(']').toString(); }
        if (value instanceof String) return JSONObject.quote((String)value);
        if (value instanceof Number) return new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }
    private static void rejected(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("operation should have been rejected"); }
        catch (IllegalArgumentException | IllegalStateException expected) {
            checks++; yes(expected.getMessage() == null || !expected.getMessage().contains(CREDENTIAL_SENTINEL), "error must not expose stored credential bytes");
        }
    }
    private static SpeechStore reopen() { return new SpeechStore(new SpeechStoreTestContext(root)); }
    private static JSONObject chapter(String url, String... text) { JSONArray p = new JSONArray(); for (String s : text) p.put(Json.obj("text", s)); return Json.obj("url", url, "title", "自作の保存検査", "paragraphs", p); }
    private static JSONObject unit(JSONObject plan, int index) { return Json.arr(plan, "units").optJSONObject(index); }
    private static JSONObject person(String id, String name, String voice) { return Json.obj("id", id, "name", name, "aliases", new JSONArray(), "gender", "neutral", "tier", "support", "voice", voice, "deviceVoice", "", "style", "", "rate", 1.0, "pitch", 1.0, "manual", false); }
    private static JSONObject find(JSONArray cast, String id) { for (int i = 0; i < cast.length(); i++) { JSONObject p = cast.optJSONObject(i); if (p != null && id.equals(p.optString("id"))) return p; } throw new AssertionError("missing speaker " + id); }
    private static JSONArray copy(JSONArray array) throws Exception { return new JSONArray(array.toString()); }
    private static File bookDir(String id) { return new File(new File(context.getFilesDir(), "speech"), SpeechStore.key(id)); }
    private static File planFile(String id, String url) { return new File(bookDir(id), SpeechStore.key(url) + ".json"); }
    private static File castFile(String id) { return new File(bookDir(id), "cast.json"); }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("tsuzuki-speech-store-jvm-").toFile();
        context = new SpeechStoreTestContext(root); store = new SpeechStore(context);
        try {
            migrationAndOfflineState(); persistenceAndIsolation(); manualAndLockedMerges(); hashesAndPositions(); settingsAndCredentialBoundary();
            analysisCredentialValidation();
            concurrentManualEdits(); failedCheckpoints(); longChapterPersistence(); scopedDeletion(); detailedActingPersistence();
            System.out.println("SpeechStore production logic with minimal JVM Android doubles passed: " + checks);
            System.out.println("Scope: real SpeechStore/SpeechPlan/Json; disk-backed Context/SharedPreferences/AtomicFile and book-existence doubles.");
            System.out.println("NOT TESTED: Android device behavior, AndroidKeyStore encryption/decryption, OS AtomicFile durability, SharedPreferences async scheduling, SQLite, or audio playback.");
            System.out.println("No real API key, network call, or third-party novel text was used.");
        } finally { remove(root); }
    }

    private static void migrationAndOfflineState() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE);
        prefs.edit().putString("settings", Json.obj("engine", "ai", "geminiConsent", true,
                "speechProvider", "gemini", "geminiDefaultVoice", "Kore", "speechModel", "gemini-3.8-flash-tts",
                "analysisModel", "gpt-6-luna", "speed", 1.25).toString())
                .putString("api_key", CREDENTIAL_SENTINEL).putString("gemini_api_key", CREDENTIAL_SENTINEL + "_OBSOLETE").commit();
        JSONObject migratedSettings = reopen().settings();
        eq(migratedSettings.optString("speechProvider"), "voicevox");
        eq(migratedSettings.optString("offlineDefaultVoice"), "metan");
        eq(migratedSettings.optString("analysisModel"), "gpt-6-luna");
        eq(migratedSettings.optString("engine"), "offline"); eq(migratedSettings.optDouble("speed"), 1.25);
        yes(migratedSettings.optBoolean("detailedActing"), "fine acting is the default for newly started analysis");
        eq(migratedSettings.optBoolean("hasApiKey"), true); eq(prefs.contains("gemini_api_key"), false);
        yes(!migratedSettings.has("speechModel") && !migratedSettings.has("geminiConsent") && !migratedSettings.has("hasGeminiApiKey"), "cloud TTS settings removed");
        yes(!migratedSettings.toString().contains(CREDENTIAL_SENTINEL), "migration does not expose the GPT key");
        same(reopen().settings(), migratedSettings);
        final String id = "v17-migration"; ReaderStore.exists(context, id, true);
        JSONArray oldCast = new JSONArray().put(Json.obj("id", "legacy", "name", "既存人物", "voice", "cedar", "geminiVoice", "Puck",
                "gender", "male", "rate", .8, "pitch", 1.2, "style", "静かな声", "manual", true, "aliases", new JSONArray().put("旧呼び名")));
        yes(bookDir(id).mkdirs(), "migration fixture directory");
        Files.write(castFile(id).toPath(), Json.obj("cast", oldCast).toString().getBytes(StandardCharsets.UTF_8));
        JSONObject oldChapter = chapter(URL, "既に解析した自作文。", "「保存した台詞です。」");
        JSONObject oldPlan = SpeechPlan.create(id, oldChapter, Json.obj("useRuby", true));
        for (int i=0;i<Json.arr(oldPlan,"units").length();i++) {
            JSONObject u=unit(oldPlan,i); Json.put(u,"analyzed",true); Json.put(u,"speakerId","legacy");
            Json.put(u,"emotion","joy"); Json.put(u,"intensity",.65); Json.put(u,"rate",.9); Json.put(u,"pitch",1.1);
        }
        Json.put(oldPlan,"analyzedUnits",Json.arr(oldPlan,"units").length()); Json.put(oldPlan,"analysisStatus","complete");
        Json.put(oldPlan,"analysisModel","gpt-6-luna"); Json.put(unit(oldPlan,1),"locked",true);
        Files.write(planFile(id,URL).toPath(),oldPlan.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject oldPosition=Json.obj("bookId",id,"chapterUrl",URL,"unitIndex",1,
                "unitId",unit(oldPlan,1).optString("unitId"),"inputHash",oldPlan.optString("inputHash"),"paragraph",1);
        Files.write(new File(bookDir(id),"position.json").toPath(),oldPosition.toString().getBytes(StandardCharsets.UTF_8));
        JSONArray migratedCast = reopen().cast(id); JSONObject person = find(migratedCast, "legacy");
        yes(OfflineVoices.valid(person.optString("offlineVoice")), "old cast receives a supported offline voice once");
        yes(!person.has("geminiVoice"), "obsolete voice field removed after migration"); eq(person.optString("voice"), "cedar");
        eq(person.optBoolean("voiceLocked"), true); eq(person.optBoolean("profileLocked"), true);
        eq(person.optDouble("rate"), .8); eq(person.optDouble("pitch"), 1.2);
        eq(person.optString("style"), "静かな声"); eq(Json.arr(person, "aliases").optString(0), "旧呼び名");
        same(reopen().cast(id), migratedCast);
        same(reopen().prepare(id,oldChapter),oldPlan);
        same(reopen().position(id),oldPosition);
        final int[] analysisCalls={0};
        JSONObject reused=new SpeechAnalyzer((request,key,cancel)->{analysisCalls[0]++;throw new AssertionError("completed annotations must not be sent again");})
                .analyze(reopen().plan(id,URL),migratedCast,"fixture-only-key","gpt-6-luna",()->false,null);
        eq(analysisCalls[0],0); same(Json.arr(reused.optJSONObject("plan"),"units"),Json.arr(oldPlan,"units"));
        store.saveSettings(Json.obj("offlineDefaultVoice", "zundamon"));
        same(reopen().cast(id), migratedCast);
        eq(find(store.cast("new-default-book"), "narrator").optString("offlineVoice"), "zundamon");
        store.saveCast(id, new JSONArray().put(Json.obj("id", "legacy", "offlineVoice", "ryusei", "style", "利用者が選んだ話し方")));
        JSONObject edited = Json.copy(find(store.cast(id), "legacy")); eq(edited.optString("offlineVoice"), "ryusei");
        JSONObject plan = store.prepare(id, chapter(URL, "配役を保存する自作文。"));
        JSONArray stale = copy(migratedCast); Json.put(find(stale, "legacy"), "offlineVoice", "zundamon");
        store.checkpoint(id, URL, plan, stale); same(find(reopen().cast(id), "legacy"), edited);
        eq(find(stale, "legacy").optString("offlineVoice"), "ryusei");
        JSONArray beforeConflict = store.cast(id);
        rejected(() -> store.saveCast(id, new JSONArray().put(Json.obj("id", "other", "name", "別人", "aliases", new JSONArray().put("旧呼び名")))));
        same(store.cast(id), beforeConflict);
        JSONObject beforeInvalid = store.settings();
        rejected(() -> store.saveSettings(Json.obj("offlineDefaultVoice", "invented-voice"))); same(store.settings(), beforeInvalid);
        rejected(() -> store.saveSettings(Json.obj("engine", "ai"))); same(store.settings(), beforeInvalid);
        store.deleteApiKey(); store.saveSettings(Json.obj("offlineDefaultVoice", "metan", "engine", "device", "speed", 1));
        eq(reopen().settings().optString("engine"), "device"); // Explicit device selection persists.
    }

    private static void persistenceAndIsolation() throws Exception {
        ReaderStore.exists(context, A, true); ReaderStore.exists(context, B, true);
        JSONObject pa = store.prepare(A, chapter(URL, "朝になった。", "「自作の台詞です。」", "（自作の心の声。）", "終わり。"));
        JSONObject pb = store.prepare(B, chapter(URL, "別の作品の本文。", "「別の声です。」"));
        eq(pa.optString("bookId"), A); eq(pb.optString("bookId"), B);
        same(reopen().plan(A, URL), pa); same(reopen().plan(B, URL), pb);
        yes(!pa.optString("inputHash").equals(pb.optString("inputHash")), "separate book bodies must be separate");
        JSONObject pa2 = store.prepare(A, chapter(URL2, "第二話の自作本文。")); same(reopen().plan(A, URL2), pa2); same(reopen().plan(A, URL), pa);
        Json.put(unit(pa, 0), "text", "returned object mutation");
        eq(unit(reopen().plan(A, URL), 0).optString("text"), "朝になった。");
        JSONObject wrongBook = Json.copy(pb), wrongUrl = Json.copy(reopen().plan(A, URL)); Json.put(wrongUrl, "chapterUrl", "local://other");
        rejected(() -> store.savePlan(A, URL, wrongBook)); rejected(() -> store.savePlan(A, URL, wrongUrl));
        same(reopen().plan(B, URL), pb);
        store.saveCast(A, new JSONArray().put(Json.obj("id", "same-id", "name", "作品A人物", "voice", "cedar")));
        store.saveCast(B, new JSONArray().put(Json.obj("id", "same-id", "name", "作品B人物", "voice", "shimmer")));
        eq(find(reopen().cast(A), "same-id").optString("voice"), "cedar"); eq(find(reopen().cast(B), "same-id").optString("voice"), "shimmer");
    }

    private static void manualAndLockedMerges() throws Exception {
        JSONObject plan = store.plan(A, URL); JSONArray aiCast = SpeechPlan.mergeCast(store.cast(A), new JSONArray().put(person("c_aoba", "青葉", "marin")).put(person("c_sora", "空", "ash")));
        store.checkpoint(A, URL, plan, aiCast);
        eq(find(store.cast(A), "c_aoba").optBoolean("manual"), false);
        JSONArray staleCast = copy(aiCast); JSONObject stalePlan = Json.copy(plan);
        store.saveCast(A, new JSONArray().put(Json.obj("id", "c_sora", "name", "空（手動）", "voice", "ballad", "deviceVoice", "offline-voice-sora", "style", "ゆっくり", "pitch", 1.2, "manual", true)));
        JSONObject untouched = Json.copy(find(store.cast(A), "c_sora"));
        store.saveCast(A, new JSONArray().put(Json.obj("id", "c_aoba", "name", "青葉（手動）", "voice", "cedar", "deviceVoice", "offline-voice-aoba", "style", "静かに", "rate", 1.1, "aliases", new JSONArray().put("青葉さん"))));
        same(find(store.cast(A), "c_sora"), untouched); // Regression: partial patch must not clear other manual entries.
        JSONObject manual = Json.copy(find(store.cast(A), "c_aoba")); eq(manual.optBoolean("manual"), true);
        String unitId = unit(plan, 1).optString("unitId");
        store.updateUnit(A, URL, unitId, Json.obj("speakerId", "c_aoba", "kind", "thought", "emotion", "calm", "intensity", .3, "rate", .9, "pitch", 1.2,
                "text", "モデルに書き換えさせない", "spokenText", "変更しない", "start", 900));
        JSONObject locked = Json.copy(unit(store.plan(A, URL), 1));
        eq(locked.optString("text"), unit(plan, 1).optString("text")); eq(locked.optInt("start"), unit(plan, 1).optInt("start"));
        eq(locked.optBoolean("locked"), true); eq(locked.optBoolean("analyzed"), true);
        Json.put(unit(stalePlan, 0), "analyzed", true); Json.put(stalePlan, "analysisStatus", "running");
        store.checkpoint(A, URL, stalePlan, staleCast);
        same(find(reopen().cast(A), "c_aoba"), manual); same(find(staleCast, "c_aoba"), manual);
        same(find(reopen().cast(A), "c_sora"), untouched); same(unit(reopen().plan(A, URL), 1), locked); same(unit(stalePlan, 1), locked);
        eq(reopen().plan(A, URL).optInt("analyzedUnits"), 2);
        store.saveCast(A, new JSONArray().put(Json.obj("id", "c_aoba", "voice", "coral", "style", "二回目の手動編集", "deviceVoice", "second-offline-voice")));
        JSONObject second = Json.copy(find(store.cast(A), "c_aoba")); eq(second.optString("voice"), "coral"); eq(second.optBoolean("manual"), true);
        store.checkpoint(A, URL, Json.copy(stalePlan), copy(aiCast)); same(find(reopen().cast(A), "c_aoba"), second);
        store.saveCast(A, new JSONArray().put(Json.obj("id", "narrator", "voice", "verse", "deviceVoice", "narrator-device", "style", "手動の語り")));
        JSONObject narrator = Json.copy(find(store.cast(A), "narrator"));
        store.saveCast(A, new JSONArray().put(Json.obj("id", "c_aoba", "pitch", 1.3)));
        same(find(store.cast(A), "narrator"), narrator); same(find(store.cast(A), "c_sora"), untouched);
        JSONArray beforeNullPatch = store.cast(A); same(store.saveCast(A, null), beforeNullPatch); same(store.saveCast(A, new JSONArray()), beforeNullPatch);
        String beforeBad = canonical(store.cast(A)); rejected(() -> store.saveCast(A, new JSONArray().put(Json.obj("id", "../escape")))); eq(canonical(store.cast(A)), beforeBad);
        JSONObject beforeUnitError = store.plan(A, URL); rejected(() -> store.updateUnit(A, URL, unitId, Json.obj("speakerId", "not-existing"))); same(store.plan(A, URL), beforeUnitError);
    }

    private static void hashesAndPositions() throws Exception {
        JSONObject p = store.plan(A, URL); String hash = p.optString("inputHash");
        store.savePosition(A, URL, 2, hash); JSONObject saved = store.position(A);
        eq(saved.optString("inputHash"), hash); eq(saved.optString("unitId"), unit(p, 2).optString("unitId"));
        eq(saved.optInt("unitIndex"), 2); eq(saved.optInt("paragraph"), unit(p, 2).optInt("paragraph")); eq(saved.optInt("start"), unit(p, 2).optInt("start"));
        same(reopen().position(A), saved);
        store.savePosition(A, URL, 0, "old-or-foreign-hash"); same(store.position(A), saved);
        store.savePosition(A, URL, 9999, hash); eq(store.position(A).optInt("unitIndex"), Json.arr(p, "units").length() - 1);
        store.savePosition(A, URL, -4, hash); eq(store.position(A).optInt("unitIndex"), 0);
        JSONObject chapter = chapter(URL, "更新された先頭。", "「更新後の台詞です。」");
        JSONObject regenerated = store.prepare(A, chapter); yes(!hash.equals(regenerated.optString("inputHash")), "changed text must create a new hash"); eq(store.position(A).length(), 0);
        store.savePosition(A, URL, 1, hash); eq(store.position(A).length(), 0); // Old service callback cannot restore old index.
        JSONArray beforeCast = store.cast(A); rejected(() -> store.checkpoint(A, URL, p, new JSONArray().put(person("must_not_exist", "古い解析", "echo"))));
        same(store.cast(A), beforeCast); same(store.plan(A, URL), regenerated);
        store.savePosition(A, URL, 1, regenerated.optString("inputHash")); JSONObject current = store.position(A);
        same(store.prepare(A, chapter), regenerated); same(store.position(A), current); // Unchanged prepare preserves resume position.
        store.prepare(A, chapter(URL2, "別話の本文が更新された。")); same(store.position(A), current);
        store.saveSettings(Json.obj("useRuby", false)); JSONObject withoutRuby = store.prepare(A, chapter); eq(store.position(A).length(), 0);
        yes(!withoutRuby.optString("inputHash").equals(regenerated.optString("inputHash")), "reading setting must invalidate old positions");
        store.savePosition(A, URL, 1, withoutRuby.optString("inputHash"));
        JSONObject rubyChapter = Json.copy(chapter); Json.put(Json.arr(rubyChapter, "paragraphs").optJSONObject(0), "html", "更新された先頭。");
        store.prepare(A, rubyChapter); eq(store.position(A).length(), 0); // Markup change also invalidates old cache.
        ReaderStore.exists(context, "deleted-with-plan", true); JSONObject deletedPlan = store.prepare("deleted-with-plan", chapter(URL, "削除位置の検査。"));
        ReaderStore.exists(context, "deleted-with-plan", false);
        store.savePosition("deleted-with-plan", URL, 0, deletedPlan.optString("inputHash")); eq(store.position("deleted-with-plan").length(), 0);
        store.deleteBook("deleted-with-plan"); store.savePosition("deleted-with-plan", URL, 0, deletedPlan.optString("inputHash")); yes(!bookDir("deleted-with-plan").exists(), "deleted book position must not recreate its folder");
    }

    private static void settingsAndCredentialBoundary() throws Exception {
        JSONObject beforeEngine = store.settings();
        rejected(() -> store.saveSettings(Json.obj("engine", "invalid"))); same(store.settings(), beforeEngine);
        JSONObject s = store.saveSettings(Json.obj("engine", "device", "speed", 8, "sleepMinutes", 999, "defaultVoice", "not-a-voice", "followText", false,
                "geminiConsent", true, "autoNext", false, "apiKey", CREDENTIAL_SENTINEL, "api_key", CREDENTIAL_SENTINEL,
                "geminiApiKey", CREDENTIAL_SENTINEL, "gemini_api_key", CREDENTIAL_SENTINEL, "speechProvider", "attacker", "hasApiKey", true, "hasGeminiApiKey", true, "untrustedSetting", "ignored"));
        eq(s.optString("engine"), "device"); eq(s.optDouble("speed"), 2.0); eq(s.optInt("sleepMinutes"), 180); eq(s.optString("defaultVoice"), "marin");
        eq(s.optBoolean("followText"), false); eq(s.optBoolean("autoNext"), false); eq(s.optBoolean("hasApiKey"), false);
        yes(!s.has("geminiConsent") && !s.has("hasGeminiApiKey"), "obsolete settings cannot be restored by a patch"); eq(s.optString("speechProvider"), "voicevox");
        yes(!s.has("apiKey") && !s.has("api_key") && !s.has("untrustedSetting"), "only allowed settings become public");
        SharedPreferences prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE);
        yes(!prefs.getString("settings", "").contains(CREDENTIAL_SENTINEL), "setting patch must not persist an API credential");
        same(reopen().settings(), s);
        JSONObject before = store.settings(); rejected(() -> store.saveSettings(Json.obj("speed", .75, "analysisModel", "bad model"))); same(store.settings(), before);
        eq(store.saveSettings(Json.obj("speed", "NaN")).optDouble("speed"), 1.0);
        prefs.edit().putString("api_key", CREDENTIAL_SENTINEL).putString("gemini_api_key", CREDENTIAL_SENTINEL + "_GOOGLE").commit();
        JSONObject publicState = reopen().settings(); eq(publicState.optBoolean("hasApiKey"), true);
        yes(!publicState.toString().contains(CREDENTIAL_SENTINEL), "stored credential bytes must never be returned by settings");
        yes(!publicState.has("apiKey") && !publicState.has("api_key"), "only hasApiKey is public");
        yes(!publicState.has("geminiApiKey") && !publicState.has("gemini_api_key") && !prefs.contains("gemini_api_key"), "old TTS credential is deleted");
        rejected(() -> store.setApiKey("short")); eq(prefs.getString("api_key", ""), CREDENTIAL_SENTINEL);
        store.deleteApiKey(); eq(reopen().settings().optBoolean("hasApiKey"), false); eq(prefs.contains("api_key"), false);
        store.saveSettings(Json.obj("engine", "device", "useRuby", true));
    }

    private static void analysisCredentialValidation() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE);
        prefs.edit().putString("api_key", CREDENTIAL_SENTINEL).commit();
        for (String invalid : new String[]{"short", "fixture-key-123456 with space", "fixture-key-123456\r\nInjected: yes"}) {
            rejected(() -> store.setApiKey(invalid)); eq(prefs.getString("api_key", ""), CREDENTIAL_SENTINEL);
        }
        try {
            store.setApiKey("fixture-openai-key-123456");
            throw new AssertionError("JVM fixture must not provide AndroidKeyStore");
        } catch (IllegalStateException expected) {
            eq(expected.getMessage(), "APIキーを安全に保存できませんでした。端末のロック設定を確認してください。");
        }
        eq(prefs.getString("api_key", ""), CREDENTIAL_SENTINEL);
        store.deleteApiKey();
    }
    private static String repeated(char value, int length) { char[] chars = new char[length]; Arrays.fill(chars, value); return new String(chars); }

    private static void concurrentManualEdits() throws Exception {
        final String id = "concurrent-book"; ReaderStore.exists(context, id, true);
        JSONObject start = store.prepare(id, chapter(URL, "地の文。", "「自作の台詞。」", "別の文。"));
        JSONArray firstCast = SpeechPlan.mergeCast(null, new JSONArray().put(person("c_person", "試験人物", "marin")));
        store.checkpoint(id, URL, start, firstCast);
        for (int iteration = 0; iteration < 20; iteration++) {
            final int n = iteration; final JSONObject stalePlan = Json.copy(store.plan(id, URL)); final JSONArray staleCast = copy(firstCast);
            final String unitId = unit(stalePlan, 1).optString("unitId");
            Json.put(unit(stalePlan, 0), "analyzed", true); Json.put(unit(stalePlan, 1), "speakerId", "unknown"); Json.put(unit(stalePlan, 1), "locked", false);
            final CountDownLatch begin = new CountDownLatch(1), done = new CountDownLatch(2); final AtomicReference<Throwable> error = new AtomicReference<>();
            Thread manual = new Thread(() -> {
                try { begin.await(); SpeechStore s = reopen(); s.saveCast(id, new JSONArray().put(Json.obj("id", "c_person", "style", "手動改訂" + n, "deviceVoice", "device-" + n, "voice", "cedar", "offlineVoice", n % 2 == 0 ? "zundamon" : "ryusei")));
                    s.updateUnit(id, URL, unitId, Json.obj("speakerId", "c_person", "emotion", "calm", "rate", .8 + n * .01)); }
                catch (Throwable t) { error.compareAndSet(null, t); } finally { done.countDown(); }
            }, "test-manual-edit");
            Thread checkpoint = new Thread(() -> {
                try { begin.await(); reopen().checkpoint(id, URL, stalePlan, staleCast); }
                catch (Throwable t) { error.compareAndSet(null, t); } finally { done.countDown(); }
            }, "test-ai-checkpoint");
            manual.start(); checkpoint.start(); begin.countDown();
            yes(done.await(10, TimeUnit.SECONDS), "concurrent store operations must finish");
            if (error.get() != null) throw new AssertionError("concurrent persistence failed", error.get());
            JSONObject savedPerson = find(reopen().cast(id), "c_person"), savedUnit = unit(reopen().plan(id, URL), 1);
            eq(savedPerson.optString("style"), "手動改訂" + n); eq(savedPerson.optString("deviceVoice"), "device-" + n); eq(savedPerson.optBoolean("manual"), true);
            eq(savedPerson.optString("offlineVoice"), n % 2 == 0 ? "zundamon" : "ryusei"); eq(savedPerson.optBoolean("voiceLocked"), true);
            eq(savedUnit.optString("speakerId"), "c_person"); eq(savedUnit.optString("emotion"), "calm"); eq(savedUnit.optBoolean("locked"), true); eq(savedUnit.optDouble("rate"), .8 + n * .01);
        }
    }

    private static void failedCheckpoints() throws Exception {
        final String id = "failure-book"; ReaderStore.exists(context, id, true);
        JSONObject p = store.prepare(id, chapter(URL, "第一の自作文。", "第二の自作文。", "第三の自作文。"));
        JSONArray cast = SpeechPlan.mergeCast(null, new JSONArray().put(person("c_failure", "保存検査人物", "sage")));
        Json.put(unit(p, 0), "analyzed", true); store.checkpoint(id, URL, p, cast);
        JSONObject last = store.plan(id, URL); JSONArray lastCast = store.cast(id);
        byte[] planBytes = Files.readAllBytes(planFile(id, URL).toPath());
        JSONObject next = Json.copy(last); Json.put(unit(next, 1), "analyzed", true);
        AtomicFile.failNextWrite(castFile(id), 9);
        rejected(() -> store.checkpoint(id, URL, next, copy(cast)));
        same(reopen().plan(id, URL), last); same(reopen().cast(id), lastCast);
        yes(Arrays.equals(planBytes, Files.readAllBytes(planFile(id, URL).toPath())), "cast write failure must preserve the prior plan bytes");
        AtomicFile.failNextWrite(planFile(id, URL), 17);
        rejected(() -> store.checkpoint(id, URL, next, copy(cast)));
        same(reopen().plan(id, URL), last); eq(reopen().plan(id, URL).optInt("analyzedUnits"), 1);
        yes(Arrays.equals(planBytes, Files.readAllBytes(planFile(id, URL).toPath())), "partial plan write must preserve completed checkpoint");
        yes(!new File(planFile(id, URL) + ".new").exists(), "failed write must invoke AtomicFile.failWrite");
        AtomicFile.failNextFinish(planFile(id, URL));
        rejected(() -> store.checkpoint(id, URL, next, copy(cast))); same(reopen().plan(id, URL), last);
        AtomicFile.failNextStart(planFile(id, URL));
        rejected(() -> store.checkpoint(id, URL, next, copy(cast))); same(reopen().plan(id, URL), last);
        store.checkpoint(id, URL, next, copy(cast)); eq(reopen().plan(id, URL).optInt("analyzedUnits"), 2); eq(unit(reopen().plan(id, URL), 0).optBoolean("analyzed"), true);
        // Simulated interrupted atomic replacement: base survives while the unfinished new file is discarded.
        JSONObject committed = store.plan(id, URL); Files.write(new File(planFile(id, URL) + ".new").toPath(), "unfinished".getBytes(StandardCharsets.UTF_8));
        same(reopen().plan(id, URL), committed);
        // The read path must use AtomicFile's backup recovery, rather than reading the corrupt base directly.
        Files.copy(planFile(id, URL).toPath(), new File(planFile(id, URL) + ".bak").toPath());
        Files.write(planFile(id, URL).toPath(), "corrupt base".getBytes(StandardCharsets.UTF_8));
        same(reopen().plan(id, URL), committed);
    }

    private static void longChapterPersistence() throws Exception {
        final String id = "long-book"; ReaderStore.exists(context, id, true);
        JSONArray paragraphs = new JSONArray(); for (int i = 0; i < 1209; i++) paragraphs.put(Json.obj("text", "「自作の長章保存検査" + i + "。」"));
        JSONObject p = store.prepare(id, Json.obj("url", URL, "paragraphs", paragraphs));
        eq(Json.arr(p, "units").length(), 1209); eq(Json.arr(reopen().plan(id, URL), "units").length(), 1209);
        for (int i = 0; i < Json.arr(p, "units").length(); i++) Json.put(unit(p, i), "analyzed", true);
        store.checkpoint(id, URL, p, store.cast(id));
        JSONObject roundTrip = reopen().plan(id, URL); eq(roundTrip.optInt("analyzedUnits"), 1209);
        same(unit(roundTrip, 1208), unit(p, 1208)); store.savePosition(id, URL, 1208, p.optString("inputHash")); eq(reopen().position(id).optInt("unitIndex"), 1208);
    }

    private static void scopedDeletion() throws Exception {
        JSONObject otherPlan = store.plan(B, URL); JSONArray otherCast = store.cast(B);
        store.savePosition(B, URL, 1, otherPlan.optString("inputHash")); JSONObject otherPosition = store.position(B);
        File audio = store.audioDir(); String prefixA = SpeechStore.key(A), prefixB = SpeechStore.key(B);
        File owned = new File(audio, prefixA + "-generated.wav"), other = new File(audio, prefixB + "-generated.wav"), near = new File(audio, prefixA + "X-generated.wav"), unrelated = new File(audio, "unrelated.wav");
        Files.write(owned.toPath(), new byte[11]); Files.write(other.toPath(), new byte[13]); Files.write(near.toPath(), new byte[17]); Files.write(unrelated.toPath(), new byte[19]);
        File ownedDir = new File(audio, prefixA + "-temporary"); yes(ownedDir.mkdir(), "fixture nested directory"); Files.write(new File(ownedDir, "partial.part").toPath(), new byte[7]);
        ReaderStore.exists(context, A, false); store.deleteBook(A);
        yes(!bookDir(A).exists(), "deleted book state is removed"); yes(!owned.exists() && !ownedDir.exists(), "only owned prefix audio is removed");
        yes(other.exists() && near.exists() && unrelated.exists(), "other book and near-prefix files remain");
        same(reopen().plan(B, URL), otherPlan); same(reopen().cast(B), otherCast); same(reopen().position(B), otherPosition);
        store.savePosition(A, URL, 1, "stale"); yes(!bookDir(A).exists(), "late position callback cannot resurrect a deleted book");
        eq(store.clearAudioCache(), 49L); eq(audio.listFiles().length, 0);
    }

    private static JSONObject acting(JSONObject unit) {
        JSONArray parts = SpeechPerformance.parts(unit), annotations = new JSONArray();
        for (int i = 0; i < parts.length(); i++) annotations.put(Json.obj(
                "partId", parts.optJSONObject(i).optString("partId"),
                "emotion", "calm", "delivery", "natural", "intensity", .3,
                "rate", 1, "pitch", 1, "intonation", 1, "volume", 1,
                "pauseAfterMs", 120, "ending", "auto", "emphasis", .1, "confidence", .9));
        return SpeechPerformance.validate(unit, annotations, true);
    }

    private static void detailedActingPersistence() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE);
        for (boolean old : new boolean[]{true,false}) {
            JSONObject legacy=Json.parse(prefs.getString("settings","{}"));
            legacy.remove("dialogueMode"); Json.put(legacy,"animeActing",old);
            prefs.edit().putString("settings",legacy.toString()).commit();
            eq(reopen().settings().optString("dialogueMode"),old?"natural":"standard");
        }
        for (String mode : new String[]{"anime","standard","natural"}) {
            store.saveSettings(Json.obj("dialogueMode",mode));
            eq(reopen().settings().optString("dialogueMode"),mode);
            eq(reopen().settings().optBoolean("animeActing"),!"standard".equals(mode));
            store.saveSettings(Json.obj("followText",true));
            eq(reopen().settings().optString("dialogueMode"),mode);
        }
        store.saveSettings(Json.obj("animeActing",false)); eq(reopen().settings().optString("dialogueMode"),"standard");
        store.saveSettings(Json.obj("animeActing",true)); eq(reopen().settings().optString("dialogueMode"),"natural");
        store.saveSettings(Json.obj("detailedActing", false)); eq(reopen().settings().optBoolean("detailedActing"), false);
        store.saveSettings(Json.obj("detailedActing", true)); eq(reopen().settings().optBoolean("detailedActing"), true);
        final String id = "detailed-persistence"; ReaderStore.exists(context, id, true);
        JSONObject plan = store.prepare(id, chapter(URL, "「驚いた。けれど、もう大丈夫。」", "朝が来た。"));
        Json.put(plan, "analysisDetail", "detailed");
        JSONObject target = unit(plan, 0); String unitId = target.optString("unitId");
        Json.put(target, "analyzed", true); Json.put(target, "performance", acting(target));
        store.checkpoint(id, URL, plan, store.cast(id));
        eq(reopen().plan(id, URL).optInt("analyzedUnits"), 1);
        store.savePosition(id, URL, 0, plan.optString("inputHash"));
        JSONObject position = reopen().position(id), original = Json.copy(target);
        JSONArray cast = copy(reopen().cast(id));
        JSONObject speakerOnly = store.updateUnit(id, URL, unitId, Json.obj("speakerId", "narrator", "kind", "thought"));
        JSONObject saved = unit(speakerOnly, 0);
        same(saved.optJSONObject("performance"), original.optJSONObject("performance"));
        eq(saved.optString("text"), original.optString("text")); eq(saved.optString("spokenText"), original.optString("spokenText"));
        eq(saved.optString("unitId"), unitId); eq(saved.optBoolean("locked"), true);
        yes(SpeechPerformance.valid(saved), "speaker-only changes preserve validated fine acting");
        JSONObject edited = Json.copy(saved.optJSONObject("performance"));
        Json.put(edited.optJSONArray("parts").optJSONObject(0), "emotion", "surprise");
        Json.put(edited.optJSONArray("parts").optJSONObject(0), "ending", "rise");
        Json.put(edited.optJSONArray("parts").optJSONObject(0), "pauseAfterMs", 700);
        store.updateUnit(id, URL, unitId, Json.obj("performance", edited));
        same(unit(reopen().plan(id, URL), 0).optJSONObject("performance"), edited);
        JSONObject committed = reopen().plan(id, URL);
        JSONObject badHash = Json.copy(edited); Json.put(badHash, "sourceHash", "different-text");
        rejected(() -> store.updateUnit(id, URL, unitId, Json.obj("performance", badHash))); same(reopen().plan(id, URL), committed);
        JSONObject badOffset = Json.copy(edited); Json.put(badOffset.optJSONArray("parts").optJSONObject(0), "end", 1);
        rejected(() -> store.updateUnit(id, URL, unitId, Json.obj("performance", badOffset))); same(reopen().plan(id, URL), committed);
        JSONObject missing = Json.copy(edited); missing.optJSONArray("parts").remove(0);
        rejected(() -> store.updateUnit(id, URL, unitId, Json.obj("performance", missing))); same(reopen().plan(id, URL), committed);
        JSONObject duplicate = Json.copy(edited); Json.put(duplicate.optJSONArray("parts").optJSONObject(1), "partId", "p0");
        rejected(() -> store.updateUnit(id, URL, unitId, Json.obj("performance", duplicate))); same(reopen().plan(id, URL), committed);
        JSONObject stale = Json.copy(committed);
        store.updateUnit(id, URL, unitId, Json.obj("performance", JSONObject.NULL));
        yes(!unit(reopen().plan(id, URL), 0).has("performance"), "explicit clearing removes fine acting");
        store.checkpoint(id, URL, stale, cast);
        yes(!unit(reopen().plan(id, URL), 0).has("performance"), "stale analysis cannot restore a manually cleared performance");
        yes(!unit(stale, 0).has("performance"), "checkpoint reconciles cleared performance into worker snapshot");
        eq(reopen().plan(id, URL).optInt("analyzedUnits"), 1); // A manually locked unit is intentionally complete.
        store.updateUnit(id, URL, unitId, Json.obj("performance", edited));
        store.updateUnit(id, URL, unitId, Json.obj("emotion", "calm", "rate", .9));
        yes(!unit(reopen().plan(id, URL), 0).has("performance"), "legacy whole-unit acting edits explicitly replace fine acting");
        same(reopen().position(id), position); same(reopen().cast(id), cast);
        eq(reopen().plan(id, URL).optString("inputHash"), plan.optString("inputHash"));
    }

    private static void remove(File file) throws IOException {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        if (file.exists() && !file.delete()) throw new IOException("could not remove test fixture " + file.getName());
    }
}
