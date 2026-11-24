package jp.haruyalab.novelshelf;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private, atomic per-book speech state. API credentials never enter WebView state. */
public final class SpeechStore {
    private static final Object LOCK = new Object();
    private static final String OPENAI_ALIAS = "tsuzuki.openai.v1";
    private final Context context;
    private final SharedPreferences prefs;
    private final File directory;
    public SpeechStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("speech", Context.MODE_PRIVATE);
        directory = new File(this.context.getFilesDir(), "speech");
    }
    public JSONObject settings() {
        synchronized (LOCK) {
            JSONObject defaults = Json.obj("engine", "offline", "speed", 1.0, "sleepMinutes", 0,
                "autoNext", true, "followText", true, "useRuby", true, "animeActing", true,
                "defaultVoice", "marin", "deviceVoice", "", "speechProvider", "voicevox",
                "offlineDefaultVoice", OfflineVoices.defaultVoice(),
                "analysisModel", "gpt-6-luna", "analysisProvider", "chatgpt", "detailedActing", true, "useStoryMemory", true);
            JSONObject saved = Json.parse(prefs.getString("settings", "{}"));
            JSONObject result = Json.merge(defaults, saved);
            DialogueMode.fromSettings(saved).storeIn(result);
            Json.put(result, "engine", "voicevox".equals(saved.optString("speechProvider"))
                    && "device".equals(saved.optString("engine")) ? "device" : "offline");
            Json.put(result, "speechProvider", "voicevox");
            if (!OfflineVoices.valid(result.optString("offlineDefaultVoice")))
                Json.put(result, "offlineDefaultVoice", OfflineVoices.defaultVoice());
            removeObsoleteSettings(result);
            // Upgrading removes the obsolete encrypted credential without touching GPT credentials.
            if (!saved.toString().equals(result.toString()) || prefs.contains("gemini_api_key"))
                prefs.edit().putString("settings", result.toString()).remove("gemini_api_key").apply();
            Json.put(result, "hasApiKey", prefs.contains("api_key"));
            return result;
        }
    }
    private static void removeObsoleteSettings(JSONObject settings) {
        for (String key : new String[]{"hasApiKey", "hasGeminiApiKey", "apiKey", "geminiApiKey",
                "geminiDefaultVoice", "geminiConsent", "speechModel", "aiConsent"}) settings.remove(key);
    }
    public JSONObject saveSettings(JSONObject patch) {
        synchronized (LOCK) {
            JSONObject s = settings();
            if (patch != null) {
                for (String k : new String[]{"autoNext", "followText", "useRuby", "detailedActing", "useStoryMemory"})
                    if (patch.has(k)) Json.put(s, k, patch.optBoolean(k));
                if (patch.has("dialogueMode") || patch.has("animeActing")) DialogueMode.fromSettings(patch).storeIn(s);
                if (patch.has("engine")) {
                    String engine = patch.optString("engine");
                    if (!"offline".equals(engine) && !"device".equals(engine))
                        throw new IllegalArgumentException("オフライン音声または端末音声を選んでください。");
                    Json.put(s, "engine", engine);
                }
                if (patch.has("analysisProvider")) {
                    String provider = patch.optString("analysisProvider");
                    if (!"chatgpt".equals(provider) && !"api_key".equals(provider)) throw new IllegalArgumentException("GPT解析の利用方法を選んでください。");
                    Json.put(s, "analysisProvider", provider);
                }
                if (patch.has("speed")) Json.put(s, "speed", number(patch.optDouble("speed", 1), .5, 2, 1));
                if (patch.has("sleepMinutes")) Json.put(s, "sleepMinutes", Math.max(0, Math.min(180, patch.optInt("sleepMinutes"))));
                if (patch.has("defaultVoice")) Json.put(s, "defaultVoice", voice(patch.optString("defaultVoice")));
                if (patch.has("offlineDefaultVoice")) {
                    String name = patch.optString("offlineDefaultVoice");
                    if (!OfflineVoices.valid(name)) throw new IllegalArgumentException("オフライン音声を一覧から選んでください。");
                    Json.put(s, "offlineDefaultVoice", name);
                }
                if (patch.has("deviceVoice")) Json.put(s, "deviceVoice", Json.limit(patch.optString("deviceVoice"), 300));
                if (patch.has("analysisModel")) {
                    String name = patch.optString("analysisModel").trim();
                    if (!name.matches("[A-Za-z0-9._:-]{1,100}")) throw new IllegalArgumentException("モデル名を確認してください。");
                    Json.put(s, "analysisModel", name);
                }
            }
            Json.put(s, "speechProvider", "voicevox");
            removeObsoleteSettings(s);
            prefs.edit().putString("settings", s.toString()).apply();
            return settings();
        }
    }
    public void setApiKey(String value) { setCredential("api_key", OPENAI_ALIAS, value, "OpenAI"); }
    private void setCredential(String slot, String alias, String value, String provider) {
        synchronized (LOCK) {
            String key = value == null ? "" : value.trim();
            boolean valid = key.length() >= 16 && key.length() <= 1024 && key.matches("[A-Za-z0-9_\\-]+");
            if (!valid)
                throw new IllegalArgumentException(provider + " APIキーの形式を確認してください。");
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(alias));
                String payload = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
                    Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
                if (!prefs.edit().putString(slot, payload).commit()) throw new Exception("save");
            } catch (Exception e) { throw new IllegalStateException("APIキーを安全に保存できませんでした。端末のロック設定を確認してください。"); }
        }
    }
    public void deleteApiKey() { synchronized (LOCK) { prefs.edit().remove("api_key").commit(); } }
    public String apiKey() { return credential("api_key", OPENAI_ALIAS); }
    private String credential(String slot, String alias) {
        synchronized (LOCK) {
            String encoded = prefs.getString(slot, "");
            if (encoded.isEmpty()) return "";
            try {
                String[] parts = encoded.split("\\.");
                if (parts.length != 2) throw new Exception("format");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, encryptionKey(alias), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
            } catch (Exception e) { throw new IllegalStateException("保存済みAPIキーを復元できません。読み上げ設定で登録し直してください。"); }
        }
    }
    private SecretKey encryptionKey(String alias) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (!keys.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        return (SecretKey) keys.getKey(alias, null);
    }
    public JSONObject plan(String bookId, String chapterUrl) {
        synchronized (LOCK) { return read(planFile(bookId, chapterUrl)); }
    }
    /** Latest historical snapshot strictly before the requested chapter, with live user overrides. */
    public JSONObject storyMemoryBefore(String bookId, int chapterIndex) {
        synchronized (LOCK) {
            requireBook(bookId);
            return memoryOverrides(bookId, memorySnapshot(bookId, chapterIndex).optJSONObject("memory"), chapterIndex, true);
        }
    }
    public JSONArray storyMemorySources(String bookId, int chapterIndex) {
        synchronized (LOCK) {
            requireBook(bookId);
            JSONArray source = Json.arr(memorySnapshot(bookId, chapterIndex), "sources"), copy = new JSONArray();
            for (int i = 0; i < source.length(); i++) copy.put(Json.copy(source.optJSONObject(i)));
            return copy;
        }
    }
    /** Derived snapshots at/after a changed source are invalidated; manual notes are independent. */
    public void invalidateStoryMemoryFrom(String bookId, int chapterIndex) {
        synchronized (LOCK) {
            requireBook(bookId);
            // Pin an old unscoped manual file before the snapshots used to infer its boundary disappear.
            manualMemoryHistory(bookId);
            File[] files = memoryDir(bookId).listFiles();
            if (files == null) return;
            for (File file : files) {
                int index = memoryFileIndex(file);
                if (index >= chapterIndex) new AtomicFile(memoryBaseFile(file)).delete();
            }
        }
    }
    private File memoryDir(String bookId) { return new File(bookDir(bookId), "story-memory"); }
    private static int memoryFileIndex(File file) {
        String name = file.getName();
        if (!name.matches("s_[0-9]{10}\\.json(?:\\.bak)?")) return -1;
        try { long n = Long.parseLong(name.substring(2, 12)); return n <= Integer.MAX_VALUE ? (int)n : -1; }
        catch (NumberFormatException invalid) { return -1; }
    }
    private static File memoryBaseFile(File file) {
        String path = file.getPath(); return path.endsWith(".bak") ? new File(path.substring(0, path.length() - 4)) : file;
    }
    private JSONObject memorySnapshot(String bookId, int beforeIndex) {
        File[] files = memoryDir(bookId).listFiles(); File selected = null; int latest = -1;
        if (files != null) for (File file : files) {
            int index = memoryFileIndex(file);
            if (index >= 0 && index < beforeIndex && index > latest) { latest = index; selected = memoryBaseFile(file); }
        }
        if (selected == null) return Json.obj("memory", StoryMemory.empty(), "sources", new JSONArray());
        JSONObject value = read(selected); JSONObject memory = StoryMemory.normalize(value.optJSONObject("memory"));
        if (memory.optInt("throughChapterIndex", -1) != latest || !bookId.equals(value.optString("bookId")))
            throw new IllegalStateException("作品メモの保存位置が一致しません。");
        Json.put(value, "memory", memory); return value;
    }
    /** Local chapter/chunk merges already include the manual revision; reapply only applicable facts/deletions. */
    JSONObject reapplyStoryMemory(String bookId, JSONObject original, int beforeIndex) {
        synchronized (LOCK) {
            requireBook(bookId); return memoryOverrides(bookId, original, beforeIndex, false);
        }
    }
    private JSONObject memoryOverrides(String bookId, JSONObject original, int beforeIndex, boolean addRevision) {
        if (beforeIndex < 0) throw new IllegalArgumentException("メモの対象話が不正です。");
        JSONObject memory = automaticMemory(original), history = manualMemoryHistory(bookId);
        LinkedHashMap<String, JSONObject> facts = new LinkedHashMap<>();
        JSONArray source = Json.arr(memory, "facts");
        for (int i = 0; i < source.length(); i++) {
            JSONObject fact = source.optJSONObject(i); facts.put(fact.optString("id"), fact);
        }
        long manualRevision = 0; JSONArray entries = Json.arr(history, "entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry.optInt("fromIndex") <= beforeIndex) manualRevision = Math.max(manualRevision, entry.optLong("revision"));
        }
        for (JSONObject entry : manualMemoryAt(history, beforeIndex).values()) {
            String id = entry.optString("id"); facts.remove(id);
            if (!entry.optBoolean("removed")) facts.put(id, Json.copy(entry.optJSONObject("fact")));
        }
        while (facts.size() > StoryMemory.MAX_FACTS) {
            String remove = null;
            for (JSONObject fact : facts.values()) if (!fact.optBoolean("manual")) { remove = fact.optString("id"); break; }
            if (remove == null) throw new IllegalArgumentException("固定メモが多すぎます。不要な項目を整理してください。");
            facts.remove(remove); Json.put(memory, "droppedFacts", memory.optInt("droppedFacts") + 1);
        }
        JSONArray result = new JSONArray(); for (JSONObject fact : facts.values()) result.put(fact);
        Json.put(memory, "facts", result);
        // A later edit must not change the contents or revision observed at an earlier chapter.
        if (addRevision) Json.put(memory, "revision", memory.optLong("revision") + manualRevision);
        return StoryMemory.normalize(memory);
    }
    /** Snapshots contain only derived facts. Live manual history is applied separately at each chapter. */
    private static JSONObject automaticMemory(JSONObject original) {
        JSONObject memory = StoryMemory.normalize(original); JSONArray clean = new JSONArray(), facts = Json.arr(memory, "facts");
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.optJSONObject(i); if (!fact.optBoolean("manual")) clean.put(fact);
        }
        Json.put(memory, "facts", clean); return memory;
    }
    private static int manualIndex(Object value) {
        if (!(value instanceof Number)) throw new IllegalStateException("固定メモの適用開始話が不正です。");
        double number = ((Number)value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > Integer.MAX_VALUE)
            throw new IllegalStateException("固定メモの適用開始話が不正です。");
        return ((Number)value).intValue();
    }
    private static JSONObject validateManualHistory(JSONObject history) {
        if (history.optInt("version") != 2 || !(history.opt("entries") instanceof JSONArray)
                || !(history.opt("revision") instanceof Number) || history.optLong("revision") < 0)
            throw new IllegalStateException("固定メモの保存形式が不正です。");
        JSONArray entries = Json.arr(history, "entries"); Set<String> positions = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !(entry.opt("id") instanceof String) || !entry.optString("id").matches("[A-Za-z0-9_-]{1,100}")
                    || !(entry.opt("removed") instanceof Boolean) || !(entry.opt("revision") instanceof Number)
                    || entry.optLong("revision") <= 0 || entry.optLong("revision") > history.optLong("revision"))
                throw new IllegalStateException("固定メモの履歴が不正です。");
            int from = manualIndex(entry.opt("fromIndex"));
            if (!positions.add(entry.optString("id") + "\n" + from)) throw new IllegalStateException("固定メモの履歴が重複しています。");
            if (!entry.optBoolean("removed")) {
                JSONObject fact = entry.optJSONObject("fact"), check = StoryMemory.empty();
                if (fact == null || !entry.optString("id").equals(fact.optString("id")) || !fact.optBoolean("manual"))
                    throw new IllegalStateException("固定メモの項目が不正です。");
                Json.put(check, "facts", new JSONArray().put(fact)); StoryMemory.normalize(check);
            }
        }
        return history;
    }
    private static LinkedHashMap<String, JSONObject> manualMemoryAt(JSONObject history, int beforeIndex) {
        LinkedHashMap<String, JSONObject> result = new LinkedHashMap<>(); JSONArray entries = Json.arr(history, "entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i); int from = entry.optInt("fromIndex");
            if (from > beforeIndex) continue;
            String id = entry.optString("id"); JSONObject old = result.get(id);
            if (old == null || old.optInt("fromIndex") < from) result.put(id, entry);
        }
        return result;
    }
    private static void validateManualCapacity(JSONObject history) {
        java.util.TreeMap<Integer, JSONArray> boundaries = new java.util.TreeMap<>(); JSONArray entries = Json.arr(history, "entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i); int from = entry.optInt("fromIndex");
            JSONArray changes = boundaries.get(from); if (changes == null) { changes = new JSONArray(); boundaries.put(from, changes); }
            changes.put(entry);
        }
        Set<String> active = new HashSet<>();
        for (JSONArray changes : boundaries.values()) {
            for (int i = 0; i < changes.length(); i++) {
                JSONObject entry = changes.optJSONObject(i);
                if (entry.optBoolean("removed")) active.remove(entry.optString("id")); else active.add(entry.optString("id"));
            }
            if (active.size() > StoryMemory.MAX_FACTS) throw new IllegalArgumentException("固定メモが多すぎます。不要な項目を整理してください。");
        }
    }
    private JSONObject manualMemoryHistory(String bookId) {
        File file = new File(memoryDir(bookId), "manual.json"); JSONObject saved = read(file);
        if (saved.length() == 0) return Json.obj("version", 2, "entries", new JSONArray(), "revision", 0);
        if (saved.optInt("version") == 2) return validateManualHistory(saved);
        if (saved.has("version") || !(saved.opt("facts") instanceof JSONArray) || !(saved.opt("removedIds") instanceof JSONArray))
            throw new IllegalStateException("固定メモの保存形式が不正です。");
        // Development-era files had no temporal scope. Infer a conservative boundary once, then retain it.
        int from = -1; File[] files = memoryDir(bookId).listFiles();
        if (files != null) for (File candidate : files) from = Math.max(from, memoryFileIndex(candidate));
        from = from < 0 ? Integer.MAX_VALUE : from == Integer.MAX_VALUE ? from : from + 1;
        long revision = Math.max(1, saved.optLong("revision")); JSONArray entries = new JSONArray();
        Set<String> removed = new HashSet<>(); JSONArray deleted = Json.arr(saved, "removedIds"), facts = Json.arr(saved, "facts");
        for (int i = 0; i < deleted.length(); i++) removed.add(deleted.optString(i));
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.optJSONObject(i); if (fact == null) throw new IllegalStateException("固定メモの項目が不正です。");
            if (!removed.contains(fact.optString("id"))) entries.put(Json.obj("id", fact.optString("id"), "fromIndex", from,
                    "revision", revision, "removed", false, "fact", fact));
        }
        for (String id : removed) entries.put(Json.obj("id", id, "fromIndex", from, "revision", revision, "removed", true));
        JSONObject migrated = validateManualHistory(Json.obj("version", 2, "entries", entries, "revision", revision));
        validateManualCapacity(migrated); write(file, migrated); return migrated;
    }
    public JSONObject editStoryMemory(String bookId, int beforeIndex, JSONArray changes, JSONArray removedIds) {
        synchronized (LOCK) {
            requireBook(bookId);
            JSONObject current = storyMemoryBefore(bookId, beforeIndex);
            JSONObject history = manualMemoryHistory(bookId); long revision = history.optLong("revision") + 1;
            LinkedHashMap<String, JSONObject> all = new LinkedHashMap<>(); JSONArray additions = new JSONArray();
            JSONArray facts = Json.arr(current, "facts");
            for (int i = 0; i < facts.length(); i++) all.put(facts.optJSONObject(i).optString("id"), facts.optJSONObject(i));
            Set<String> touched = new HashSet<>();
            if (changes != null) for (int i = 0; i < changes.length(); i++) {
                JSONObject patch = changes.optJSONObject(i); if (patch == null) throw new IllegalArgumentException("メモの項目が不正です。");
                String id = patch.optString("id"); JSONObject fact;
                if (id.isEmpty()) {
                    id = "m_" + UUID.randomUUID().toString().replace("-", "");
                    fact = Json.obj("id", id, "type", patch.optString("type", "state"), "subjectId", "", "objectId", "",
                            "text", "", "uncertain", false, "citations", new JSONArray(), "manual", true);
                } else {
                    if (!all.containsKey(id)) throw new IllegalArgumentException("メモが更新されています。開き直してください。");
                    fact = Json.copy(all.get(id));
                }
                if (!touched.add(id)) throw new IllegalArgumentException("同じメモが重複しています。");
                if (!(patch.opt("text") instanceof String) || patch.optString("text").trim().isEmpty()
                        || patch.optString("text").length() > StoryMemory.MAX_TEXT_CHARS)
                    throw new IllegalArgumentException("メモは1〜" + StoryMemory.MAX_TEXT_CHARS + "文字で入力してください。");
                Json.put(fact, "text", patch.optString("text")); Json.put(fact, "manual", true);
                if (patch.has("uncertain")) {
                    if (!(patch.opt("uncertain") instanceof Boolean)) throw new IllegalArgumentException("未確定の設定が不正です。");
                    Json.put(fact, "uncertain", patch.optBoolean("uncertain"));
                }
                additions.put(Json.obj("id", id, "fromIndex", beforeIndex, "revision", revision, "removed", false, "fact", fact));
            }
            if (removedIds != null) for (int i = 0; i < removedIds.length(); i++) {
                Object raw = removedIds.opt(i); if (!(raw instanceof String)) throw new IllegalArgumentException("削除対象が不正です。");
                String id = (String)raw;
                if (!all.containsKey(id) || !touched.add(id)) throw new IllegalArgumentException("削除対象のメモが変更されています。");
                additions.put(Json.obj("id", id, "fromIndex", beforeIndex, "revision", revision, "removed", true));
            }
            if (touched.isEmpty()) return current;
            JSONArray out = new JSONArray(), previous = Json.arr(history, "entries");
            for (int i = 0; i < previous.length(); i++) {
                JSONObject entry = previous.optJSONObject(i);
                if (entry.optInt("fromIndex") != beforeIndex || !touched.contains(entry.optString("id"))) out.put(entry);
            }
            for (int i = 0; i < additions.length(); i++) out.put(additions.optJSONObject(i));
            JSONObject checked = validateManualHistory(Json.obj("version", 2, "entries", out, "revision", revision));
            validateManualCapacity(checked); write(new File(memoryDir(bookId), "manual.json"), checked);
            return storyMemoryBefore(bookId, beforeIndex);
        }
    }
    /** Called only after successful metadata validation; repeat commits of the same sources are idempotent. */
    public JSONObject commitStoryMemory(String bookId, JSONObject range, JSONArray sourcePlans,
                                         JSONArray proposedCast, JSONObject delta, String model) {
        synchronized (LOCK) {
            requireCurrentRange(bookId, range);
            if (sourcePlans == null || sourcePlans.length() == 0) throw new IllegalArgumentException("メモの対象話がありません。");
            JSONObject first = sourcePlans.optJSONObject(0), last = sourcePlans.optJSONObject(sourcePlans.length() - 1);
            int firstIndex = first.optInt("chapterIndex", -1), lastIndex = last.optInt("chapterIndex", -1);
            if (firstIndex < 0 || lastIndex < firstIndex) throw new IllegalArgumentException("メモの話数が不正です。");
            StringBuilder identity = new StringBuilder(model == null ? "" : model);
            int previousIndex = firstIndex - 1;
            for (int i = 0; i < sourcePlans.length(); i++) {
                JSONObject source = sourcePlans.optJSONObject(i);
                if (!bookId.equals(source.optString("bookId")) || source.optInt("chapterIndex", -1) != previousIndex + 1
                        || source.optString("chapterUrl").isEmpty() || source.optString("inputHash").isEmpty())
                    throw new IllegalArgumentException("メモの本文と話数が一致しません。");
                previousIndex = source.optInt("chapterIndex");
                identity.append('\n').append(source.optString("chapterUrl")).append('\n').append(source.optString("inputHash"));
            }
            File file = new File(memoryDir(bookId), String.format(java.util.Locale.ROOT, "s_%010d.json", lastIndex));
            String commitKey = key(identity.toString()); JSONObject existing = read(file);
            int afterIndex = lastIndex == Integer.MAX_VALUE ? lastIndex : lastIndex + 1;
            if (commitKey.equals(existing.optString("commitKey"))) return memoryOverrides(bookId, existing.optJSONObject("memory"), afterIndex, true);
            JSONObject base = memorySnapshot(bookId, firstIndex);
            if (base.optJSONObject("memory").optInt("throughChapterIndex", -1) != firstIndex - 1)
                throw new IllegalStateException("作品メモの途中に未確認の話があります。先に履歴を準備してください。");
            JSONArray mergedCast = CastRegistry.mergeAnalysis(cast(bookId), proposedCast, settings().optString("offlineDefaultVoice"));
            JSONObject checked = StoryMemory.validateDelta(delta, sourcePlans, mergedCast);
            JSONObject merged = StoryMemory.merge(automaticMemory(base.optJSONObject("memory")), checked, sourcePlans);
            // The stored revision counts derived snapshots; the live overlay adds its own revision exactly once.
            Json.put(merged, "revision", base.optJSONObject("memory").optLong("revision") + 1);
            JSONArray sources = Json.arr(base, "sources");
            for (int i = 0; i < sourcePlans.length(); i++) {
                JSONObject source = sourcePlans.optJSONObject(i);
                sources.put(Json.obj("url", source.optString("chapterUrl"), "index", source.optInt("chapterIndex"),
                        "hash", source.optString("inputHash"), "useRuby", source.optBoolean("useRuby", true)));
            }
            write(castFile(bookId), Json.obj("cast", mergedCast));
            write(file, Json.obj("bookId", bookId, "commitKey", commitKey, "memory", merged, "sources", sources));
            if (lastIndex < Integer.MAX_VALUE) invalidateStoryMemoryFrom(bookId, lastIndex + 1);
            return memoryOverrides(bookId, merged, afterIndex, true);
        }
    }
    public JSONObject prepare(String bookId, JSONObject chapter) {
        synchronized (LOCK) {
            String url = chapter.optString("url");
            JSONObject p = plan(bookId, url), s = settings();
            if (!SpeechPlan.compatible(p, chapter) || p.optBoolean("useRuby", true) != s.optBoolean("useRuby", true)) {
                p = SpeechPlan.create(bookId, chapter, s); savePlan(bookId, url, p);
                JSONObject resume = position(bookId);
                if (url.equals(resume.optString("chapterUrl")))
                    write(new File(bookDir(bookId), "position.json"), new JSONObject());
            } else if (SpeechPlan.refreshRubyMetadata(p, chapter)) {
                savePlan(bookId, url, p);
            }
            return p;
        }
    }
    /** Background analysis can rebuild metadata without moving or clearing the playback position. */
    public JSONObject prepareForAnalysis(String bookId, JSONObject chapter) {
        synchronized (LOCK) {
            requireBook(bookId);
            if (chapter == null || !bookId.equals(chapter.optString("bookId")))
                throw new IllegalArgumentException("解析する本文の作品が一致しません。");
            String url = chapter.optString("url"); JSONObject plan = plan(bookId, url), settings = settings();
            if (!SpeechPlan.compatible(plan, chapter) || plan.optBoolean("useRuby", true) != settings.optBoolean("useRuby", true)) {
                plan = SpeechPlan.create(bookId, chapter, settings); savePlan(bookId, url, plan);
            } else if (SpeechPlan.refreshRubyMetadata(plan, chapter)) {
                savePlan(bookId, url, plan);
            }
            return plan;
        }
    }
    /** Ownership and both preparations share the deletion lock, even before the SQLite row is removed. */
    JSONObject prepareAnalysisChapter(String bookId, JSONObject chapter, JSONObject range) {
        synchronized (LOCK) {
            requireCurrentRange(bookId, range);
            JSONObject plan = prepareForAnalysis(bookId, chapter);
            return Json.obj("plan", plan, "cast", cast(bookId));
        }
    }
    public JSONObject analysisRange(String bookId) {
        synchronized (LOCK) { return read(new File(bookDir(bookId), "analysis-range.json")); }
    }
    /** Read-only event snapshot: unlike cast(), this cannot recreate a deleted book directory. */
    JSONObject analysisState(String bookId) {
        synchronized (LOCK) {
            JSONObject range = analysisRange(bookId); if (range.length() == 0) return new JSONObject();
            String url = range.optString("currentChapterUrl");
            JSONObject plan = url.isEmpty() ? new JSONObject() : plan(bookId, url);
            JSONArray cast = CastRegistry.migrate(Json.arr(read(castFile(bookId)), "cast"), settings().optString("offlineDefaultVoice"));
            return Json.obj("range", range, "plan", plan, "cast", cast);
        }
    }
    /** Replacing a range/session is explicit. Old callbacks cannot write with its previous job ID. */
    public void beginAnalysisRange(String bookId, JSONObject range) {
        synchronized (LOCK) {
            requireBook(bookId); checkRangeIdentity(bookId, range);
            write(new File(bookDir(bookId), "analysis-range.json"), range);
        }
    }
    public void saveAnalysisRange(String bookId, JSONObject range) {
        synchronized (LOCK) {
            requireCurrentRange(bookId, range);
            write(new File(bookDir(bookId), "analysis-range.json"), range);
        }
    }
    /** Update only display activity on the latest saved plan; never replace concurrent manual edits. */
    void saveAnalysisActivity(String bookId, String chapterUrl, JSONObject range) {
        synchronized (LOCK) {
            requireCurrentRange(bookId, range);
            if (!chapterUrl.equals(range.optString("currentChapterUrl")))
                throw new IllegalStateException("解析中の章が変更されています。");
            JSONObject current = plan(bookId, chapterUrl);
            if (current.length() == 0) return;
            if (!bookId.equals(current.optString("bookId")) || !chapterUrl.equals(current.optString("chapterUrl")))
                throw new IllegalStateException("解析データの作品または章が一致しません。");
            Json.put(current, "analysisActivity", Json.copy(range.optJSONObject("analysisActivity")));
            write(planFile(bookId, chapterUrl), current);
        }
    }
    /** Plan/cast commit precedes queue progress: an interrupted queue write can only repeat a check. */
    public void analysisCheckpoint(String bookId, String chapterUrl, JSONObject plan, JSONArray cast, JSONObject range) {
        synchronized (LOCK) {
            requireCurrentRange(bookId, range);
            if (!chapterUrl.equals(range.optString("currentChapterUrl")))
                throw new IllegalStateException("解析中の章が変更されています。");
            checkpoint(bookId, chapterUrl, plan, cast);
            SpeechAnalysisRange.progress(range, plan);
            write(new File(bookDir(bookId), "analysis-range.json"), range);
        }
    }
    private void requireCurrentRange(String bookId, JSONObject range) {
        requireBook(bookId); checkRangeIdentity(bookId, range);
        if (!range.optString("jobId").equals(analysisRange(bookId).optString("jobId")))
            throw new IllegalStateException("解析ジョブが変更されています。保存済みの範囲を開き直してください。");
    }
    private static void checkRangeIdentity(String bookId, JSONObject range) {
        if (range == null || range.optInt("version") != SpeechAnalysisRange.VERSION || !bookId.equals(range.optString("bookId"))
                || !range.optString("jobId").matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("解析範囲の作品またはジョブIDが不正です。");
    }
    private void requireBook(String bookId) {
        ReaderStore books = new ReaderStore(context);
        try { if (books.rawBook(bookId) == null) throw new IllegalStateException("作品が削除されています。"); }
        finally { books.close(); }
    }
    public void savePlan(String bookId, String chapterUrl, JSONObject plan) {
        synchronized (LOCK) {
            if (!bookId.equals(plan.optString("bookId")) || !chapterUrl.equals(plan.optString("chapterUrl")))
                throw new IllegalArgumentException("読み上げデータの作品または章が一致しません。");
            write(planFile(bookId, chapterUrl), plan);
        }
    }
    /** Checkpoint merges live manual corrections into the analyzer's working copy. */
    public void checkpoint(String bookId, String chapterUrl, JSONObject plan, JSONArray cast) {
        mergeCheckpoint(bookId, chapterUrl, plan, cast, true);
    }
    /** Stage-only progress refreshes manual edits without rewriting the entire chapter. */
    public void refreshAnalysis(String bookId, String chapterUrl, JSONObject plan, JSONArray cast) {
        mergeCheckpoint(bookId, chapterUrl, plan, cast, false);
    }
    private void mergeCheckpoint(String bookId, String chapterUrl, JSONObject plan, JSONArray cast, boolean commit) {
        synchronized (LOCK) {
            JSONObject current = plan(bookId, chapterUrl);
            if (!current.optString("inputHash").equals(plan.optString("inputHash")))
                throw new IllegalStateException("本文が更新されました。章を開き直して解析を再開してください。");
            Map<String,JSONObject> locked = new HashMap<>(), latestUnits = new HashMap<>();
            JSONArray old = Json.arr(current, "units"), units = Json.arr(plan, "units");
            for (int i = 0; i < old.length(); i++) {
                JSONObject u = old.optJSONObject(i);
                if (u != null) {
                    latestUnits.put(u.optString("unitId"), u);
                    if (u.optBoolean("locked")) locked.put(u.optString("unitId"), u);
                }
            }
            for (int i = 0; i < units.length(); i++) {
                JSONObject u = units.optJSONObject(i), override = u == null ? null : locked.get(u.optString("unitId"));
                if (override != null) {
                    for (String k : new String[]{"kind", "speakerId", "emotion", "intensity", "rate", "pitch", "confidence", "analyzed", "locked"})
                        if (override.has(k)) Json.put(u, k, override.opt(k));
                    // A user can also deliberately clear fine acting while a review is in flight.
                    if (override.has("performance")) Json.put(u, "performance", Json.copy(override.optJSONObject("performance")));
                    else u.remove("performance");
                }
                JSONObject readingOverride = u == null ? null : latestUnits.get(u.optString("unitId"));
                if (readingOverride != null && (readingOverride.optBoolean("pronunciationLocked")
                        || !readingOverride.optString("pronunciationEditRevision").equals(u.optString("pronunciationEditRevision")))) {
                    if (readingOverride.has("pronunciation")) Json.put(u, "pronunciation", Json.copy(readingOverride.optJSONObject("pronunciation")));
                    else u.remove("pronunciation");
                    Json.put(u, "pronunciationLocked", readingOverride.optBoolean("pronunciationLocked"));
                    Json.put(u, "pronunciationEditRevision", readingOverride.optString("pronunciationEditRevision"));
                }
            }
            Json.put(plan, "analyzedUnits", SpeechPronunciation.completedCount(plan));
            JSONArray merged = CastRegistry.mergeAnalysis(cast(bookId), cast, settings().optString("offlineDefaultVoice"));
            if (commit) write(castFile(bookId), Json.obj("cast", merged));
            // Keep the caller's cast in sync without dropping characters created in an earlier batch.
            while (cast.length() > 0) cast.remove(cast.length() - 1);
            for (int i = 0; i < merged.length(); i++) cast.put(merged.opt(i));
            if (commit) savePlan(bookId, chapterUrl, plan);
        }
    }
    public JSONArray cast(String bookId) {
        synchronized (LOCK) {
            JSONArray saved = Json.arr(read(castFile(bookId)), "cast");
            JSONArray migrated = CastRegistry.migrate(saved, settings().optString("offlineDefaultVoice"));
            // Assign a voice once. A later change to the global default never recasts this book.
            if (!saved.toString().equals(migrated.toString())) write(castFile(bookId), Json.obj("cast", migrated));
            return migrated;
        }
    }
    public JSONArray saveCast(String bookId, JSONArray patch) {
        synchronized (LOCK) {
            JSONArray merged = CastRegistry.patchUser(cast(bookId), patch, settings().optString("offlineDefaultVoice"));
            write(castFile(bookId), Json.obj("cast", merged)); return merged;
        }
    }
    public JSONObject updateUnit(String bookId, String url, String unitId, JSONObject changes) {
        synchronized (LOCK) {
            JSONObject p = plan(bookId, url); JSONArray units = Json.arr(p, "units");
            JSONObject target = null;
            for (int i = 0; i < units.length(); i++) if (unitId.equals(units.optJSONObject(i).optString("unitId"))) { target = units.optJSONObject(i); break; }
            if (target == null) throw new IllegalArgumentException("読み上げ区間がありません。章を開き直してください。");
            if (changes == null) changes = new JSONObject();
            if (changes.has("kind")) Json.put(target, "kind", choice(changes.optString("kind"), "narration", "narration", "dialogue", "thought"));
            if (changes.has("speakerId")) {
                String speaker = changes.optString("speakerId"); boolean found = false; JSONArray cast = cast(bookId);
                for (int i = 0; i < cast.length(); i++) if (speaker.equals(cast.optJSONObject(i).optString("id"))) found = true;
                if (!found) throw new IllegalArgumentException("配役を先に登録してください。");
                Json.put(target, "speakerId", speaker);
            }
            if (changes.has("emotion")) Json.put(target, "emotion", choice(changes.optString("emotion"), "neutral", "neutral", "joy", "sadness", "anger", "fear", "surprise", "calm", "tension", "whisper"));
            if (changes.has("intensity")) Json.put(target, "intensity", number(changes.optDouble("intensity", .4), 0, 1, .4));
            if (changes.has("rate")) Json.put(target, "rate", number(changes.optDouble("rate", 1), .5, 1.5, 1));
            if (changes.has("pitch")) Json.put(target, "pitch", number(changes.optDouble("pitch", 1), .5, 2, 1));
            if (changes.has("performance")) {
                if (changes.isNull("performance")) target.remove("performance");
                else {
                    JSONObject incoming = changes.optJSONObject("performance");
                    if (incoming == null || incoming.optInt("version") != SpeechPerformance.VERSION
                            || !(incoming.opt("parts") instanceof JSONArray))
                        throw new IllegalArgumentException("細かい演技の形式を確認してください。");
                    JSONObject checked = SpeechPerformance.validate(target, incoming.optJSONArray("parts"), true);
                    if (!checked.optString("sourceHash").equals(incoming.optString("sourceHash")))
                        throw new IllegalArgumentException("本文が変わっています。区間を開き直してから演技を編集してください。");
                    Json.put(target, "performance", checked);
                }
            } else if (changes.has("emotion") || changes.has("intensity") || changes.has("rate") || changes.has("pitch")) {
                // Old editors change the entire unit. Their explicit values take precedence over AI parts.
                target.remove("performance");
            }
            Json.put(target, "locked", true); Json.put(target, "analyzed", true); Json.put(target, "confidence", 1);
            Json.put(p, "analyzedUnits", SpeechPronunciation.completedCount(p)); Json.put(p, "updatedAt", System.currentTimeMillis()); savePlan(bookId, url, p); return p;
        }
    }
    private static JSONObject pronunciationUnit(JSONObject plan, String unitId) {
        JSONArray units = Json.arr(plan, "units");
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.optJSONObject(i);
            if (unit != null && unitId.equals(unit.optString("unitId"))) return unit;
        }
        throw new IllegalArgumentException("読み上げ区間がありません。章を開き直してください。");
    }
    public JSONObject pronunciationState(String bookId, String url, String unitId) {
        synchronized (LOCK) {
            requireBook(bookId);
            JSONObject unit = pronunciationUnit(plan(bookId, url), unitId);
            return Json.obj("pronunciation", SpeechPronunciation.template(unit),
                    "pronunciationLocked", unit.optBoolean("pronunciationLocked"),
                    "hasPronunciation", SpeechPronunciation.valid(unit));
        }
    }
    /** The UI supplies the currently cached body, so an old form cannot save against an obsolete plan. */
    public JSONObject savePronunciation(String bookId, String url, String unitId, JSONObject incoming, boolean reset, JSONObject currentChapter) {
        synchronized (LOCK) {
            requireBook(bookId);
            if (currentChapter == null || !SpeechPlan.compatible(plan(bookId, url), currentChapter))
                throw new IllegalArgumentException("本文またはルビが更新されました。読みの編集画面を開き直してください。");
            return savePronunciation(bookId, url, unitId, incoming, reset);
        }
    }
    /** A reading correction never locks, clears or reassigns the user's acting and cast. */
    JSONObject savePronunciation(String bookId, String url, String unitId, JSONObject incoming, boolean reset) {
        synchronized (LOCK) {
            requireBook(bookId);
            JSONObject plan = plan(bookId, url), unit = pronunciationUnit(plan, unitId);
            if (reset) {
                unit.remove("pronunciation"); Json.put(unit, "pronunciationLocked", false);
            } else {
                if (incoming == null || incoming.optInt("version") != SpeechPronunciation.VERSION
                        || !(incoming.opt("parts") instanceof JSONArray))
                    throw new IllegalArgumentException("読みの形式を確認してください。");
                JSONObject checked = SpeechPronunciation.validateManualEdit(unit, incoming.optJSONArray("parts"));
                if (!checked.optString("sourceHash").equals(incoming.optString("sourceHash")))
                    throw new IllegalArgumentException("本文またはルビが変わっています。読みの編集画面を開き直してください。");
                Json.put(unit, "pronunciation", checked); Json.put(unit, "pronunciationLocked", true);
            }
            Json.put(unit, "pronunciationEditRevision", UUID.randomUUID().toString());
            Json.put(plan, "analyzedUnits", SpeechPronunciation.completedCount(plan));
            Json.put(plan, "updatedAt", System.currentTimeMillis()); savePlan(bookId, url, plan); return plan;
        }
    }
    public JSONObject position(String bookId) { synchronized (LOCK) { return read(new File(bookDir(bookId), "position.json")); } }
    public void savePosition(String bookId, String chapterUrl, int unitIndex) {
        savePosition(bookId, chapterUrl, unitIndex, null);
    }
    public void savePosition(String bookId, String chapterUrl, int unitIndex, String inputHash) {
        synchronized (LOCK) {
            ReaderStore books = new ReaderStore(context); boolean exists;
            try { exists = books.rawBook(bookId) != null; } finally { books.close(); }
            if (!exists) return;
            JSONObject current = plan(bookId, chapterUrl); JSONArray units = Json.arr(current, "units");
            if (units.length() == 0 || inputHash != null && !inputHash.equals(current.optString("inputHash"))) return;
            int index = Math.max(0, Math.min(units.length() - 1, unitIndex)); JSONObject unit = units.optJSONObject(index);
            write(new File(bookDir(bookId), "position.json"), Json.obj("bookId", bookId, "chapterUrl", chapterUrl,
                "unitIndex", index, "unitId", unit.optString("unitId"), "inputHash", current.optString("inputHash"),
                "paragraph", unit.optInt("paragraph"), "start", unit.optInt("start"), "updatedAt", System.currentTimeMillis())); }
    }
    public File audioDir() { File d = new File(context.getCacheDir(), "speech-audio"); if (!d.exists()) d.mkdirs(); return d; }
    public long clearAudioCache() { synchronized (LOCK) { return deleteChildren(audioDir(), null); } }
    public void deleteBook(String bookId) {
        synchronized (LOCK) { deleteChildren(bookDir(bookId), null); bookDir(bookId).delete(); deleteChildren(audioDir(), key(bookId) + "-"); }
    }
    private static long deleteChildren(File folder, String prefix) {
        long bytes = 0; File[] all = folder.listFiles(); if (all == null) return 0;
        for (File f : all) if (prefix == null || f.getName().startsWith(prefix)) {
            if (f.isDirectory()) { bytes += deleteChildren(f, null); f.delete(); }
            else { long size = f.length(); if (f.delete()) bytes += size; }
        }
        return bytes;
    }
    private File bookDir(String bookId) { if (bookId == null || bookId.isEmpty()) throw new IllegalArgumentException("作品を選んでください。"); return new File(directory, key(bookId)); }
    private File castFile(String id) { return new File(bookDir(id), "cast.json"); }
    private File planFile(String id, String url) { if (url == null || url.isEmpty()) throw new IllegalArgumentException("章を選んでください。"); return new File(bookDir(id), key(url) + ".json"); }
    private static JSONObject read(File f) {
        AtomicFile file = new AtomicFile(f);
        if (!f.exists() && !new File(f.getPath() + ".bak").exists()) return new JSONObject();
        try (FileInputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("読み上げデータを読み込めません。空き容量と保存状態を確認してください。"); }
    }
    private static void write(File f, JSONObject value) {
        File parent = f.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("読み上げデータの保存先を作成できません。");
        AtomicFile file = new AtomicFile(f); FileOutputStream out = null;
        try { out = file.startWrite(); out.write(value.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw new IllegalStateException("読み上げデータを保存できません。空き容量を確認してください。"); }
    }
    public static String key(String value) {
        try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder s = new StringBuilder(); for (byte b : hash) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString(); }
        catch (Exception e) { throw new IllegalStateException("識別子を作成できません。"); }
    }
    static double number(double value, double min, double max, double fallback) { return Double.isNaN(value) || Double.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value)); }
    static String voice(String value) { return choice(value, "marin", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar"); }
    static String choice(String value, String fallback, String... allowed) { for (String a : allowed) if (a.equals(value)) return a; return fallback; }
}
