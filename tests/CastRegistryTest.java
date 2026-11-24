package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exercises persisted identity/voice invariants on synthetic fictional characters. */
public final class CastRegistryTest {
    private static int checks;
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual); }
    private static void yes(boolean actual, String message) { checks++; if (!actual) throw new AssertionError(message); }
    private interface Action { void run(); }
    private static void rejected(Action action) { try { action.run(); throw new AssertionError("invalid cast was accepted"); } catch (IllegalArgumentException expected) { checks++; } }
    private static JSONObject person(String id, String name, String voice) {
        return Json.obj("id", id, "name", name, "aliases", new JSONArray(), "gender", "neutral", "tier", "support",
                "offlineVoice", voice, "voice", "shimmer", "deviceVoice", "device-fixture", "style", "静かに明瞭に", "rate", 1.05, "pitch", .95, "manual", false);
    }
    private static JSONObject find(JSONArray cast, String id) {
        for (int i = 0; i < cast.length(); i++) if (id.equals(cast.optJSONObject(i).optString("id"))) return cast.optJSONObject(i);
        throw new AssertionError("missing ID " + id);
    }
    private static Map<String, JSONObject> index(JSONArray cast) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (int i = 0; i < cast.length(); i++) result.put(cast.optJSONObject(i).optString("id"), cast.optJSONObject(i));
        return result;
    }
    public static void main(String[] args) {
        JSONObject legacy = person("c_legacy", "青葉", ""); legacy.remove("offlineVoice"); Json.put(legacy, "manual", true);
        Json.put(legacy, "aliases", new JSONArray().put("碧葉"));
        String legacyBefore = legacy.toString();
        JSONArray migrated = CastRegistry.migrate(new JSONArray().put(legacy), "zundamon");
        JSONObject migratedPerson = find(migrated, "c_legacy");
        for (Iterator<String> keys = legacy.keys(); keys.hasNext();) {
            String key = keys.next(); eq(String.valueOf(migratedPerson.opt(key)), String.valueOf(legacy.opt(key)));
        }
        yes(OfflineVoices.valid(migratedPerson.optString("offlineVoice")), "legacy role receives a stable supported voice");
        eq(migratedPerson.optBoolean("voiceLocked"), true); eq(migratedPerson.optBoolean("profileLocked"), true);
        eq(legacy.toString(), legacyBefore);
        JSONArray again = CastRegistry.migrate(migrated, "ryusei");
        eq(again.toString(), migrated.toString());
        eq(find(again, "narrator").optString("offlineVoice"), "zundamon");
        Json.put(find(again, "c_legacy"), "name", "copy-only");
        eq(find(migrated, "c_legacy").optString("name"), "青葉");

        JSONObject auto = person("c_auto", "陽", "metan");
        JSONArray stable = CastRegistry.migrate(new JSONArray().put(auto), "zundamon");
        String stableBefore = stable.toString();
        for (int chapter = 0; chapter < 30; chapter++) {
            JSONObject model = person("c_auto", "陽の別呼称" + chapter, OfflineVoices.catalog().optJSONObject(chapter % 10).optString("id"));
            Json.put(model, "gender", "female"); Json.put(model, "tier", "main"); Json.put(model, "style", "新しい話し方");
            Json.put(model, "rate", .7); Json.put(model, "pitch", 1.4); Json.put(model, "voice", "echo");
            JSONArray next = CastRegistry.mergeAnalysis(stable, new JSONArray().put(model), "ryusei");
            JSONObject p = find(next, "c_auto");
            eq(p.optString("offlineVoice"), "metan"); eq(p.optString("voice"), "shimmer");
            eq(p.optString("deviceVoice"), "device-fixture"); eq(p.optString("name"), "陽");
            eq(p.optString("style"), "静かに明瞭に"); eq(p.optDouble("rate"), 1.05); eq(p.optDouble("pitch"), .95);
            eq(p.optBoolean("voiceLocked"), true); eq(p.optBoolean("profileLocked"), false);
            eq(p.optString("gender"), "female"); eq(p.optString("tier"), "main");
            stable = next;
        }
        yes(!stable.toString().equals(stableBefore), "supported aliases can be learned without replacing voice identity");
        JSONObject edit = Json.obj("id", "c_auto", "name", "ハル", "aliases", new JSONArray().put("陽").put("ハルさん"),
                "offlineVoice", "ryusei", "style", "短く穏やかに", "gender", "male", "tier", "extra", "rate", .8, "pitch", 1.2);
        JSONArray edited = CastRegistry.patchUser(stable, new JSONArray().put(edit), "metan");
        JSONObject user = find(edited, "c_auto");
        eq(user.optString("offlineVoice"), "ryusei"); eq(user.optString("name"), "ハル");
        eq(user.optString("voice"), "shimmer"); eq(user.optString("deviceVoice"), "device-fixture");
        eq(user.optBoolean("manual"), true); eq(user.optBoolean("voiceLocked"), true); eq(user.optBoolean("profileLocked"), true);
        JSONObject contradictory = person("c_auto", "上書き候補", "miko"); Json.put(contradictory, "gender", "female");
        JSONArray afterModel = CastRegistry.mergeAnalysis(edited, new JSONArray().put(contradictory), "tsumugi");
        eq(find(afterModel, "c_auto").toString(), user.toString());
        eq(CastRegistry.analysisIdentity(person("c_new_guess", "陽", "miko"), index(afterModel)), "c_auto");
        eq(CastRegistry.analysisIdentity(person("c_new_guess", "ハル", "miko"), index(afterModel)), "unknown");
        eq(CastRegistry.analysisIdentity(person("c_new_guess", "はる", "miko"), index(afterModel)), "c_new_guess"); // No fuzzy matching.

        JSONArray renamed = CastRegistry.patchUser(edited, new JSONArray().put(Json.obj("id", "c_auto", "name", "春野")), "metan");
        yes(find(renamed, "c_auto").optJSONArray("aliases").toString().contains("ハル"), "a name-only edit retains the prior identity label");
        eq(find(renamed, "c_auto").optString("offlineVoice"), "ryusei");
        JSONArray changedVoice = CastRegistry.patchUser(renamed, new JSONArray().put(Json.obj("id", "c_auto", "offlineVoice", "hau")), "metan");
        eq(find(changedVoice, "c_auto").optString("offlineVoice"), "hau");
        eq(find(CastRegistry.migrate(changedVoice, "zundamon"), "c_auto").optString("offlineVoice"), "hau");

        JSONArray twins = CastRegistry.migrate(new JSONArray().put(person("c_east", "葵", "metan")).put(person("c_west", "葵", "zundamon")), "ryusei");
        eq(twins.length(), 4); // Equal display names are separate identities.
        eq(CastRegistry.analysisIdentity(person("c_guess", "葵", "miko"), index(twins)), "unknown");
        JSONArray labelled = CastRegistry.patchUser(twins, new JSONArray()
                .put(Json.obj("id", "c_east", "aliases", new JSONArray().put("東の葵")))
                .put(Json.obj("id", "c_west", "aliases", new JSONArray().put("西の葵"))), "metan");
        eq(CastRegistry.analysisIdentity(person("c_guess", "東の葵", "miko"), index(labelled)), "c_east");
        String beforeConflict = labelled.toString();
        rejected(() -> CastRegistry.patchUser(labelled, new JSONArray().put(Json.obj("id", "c_west", "aliases", new JSONArray().put("東の葵"))), "metan"));
        eq(labelled.toString(), beforeConflict);
        JSONObject both = person("c_guess", "別人候補", "miko"); Json.put(both, "aliases", new JSONArray().put("東の葵").put("西の葵"));
        eq(CastRegistry.analysisIdentity(both, index(labelled)), "unknown");
        rejected(() -> CastRegistry.mergeAnalysis(labelled, new JSONArray().put(person("c_other", "別人", "miko").put("aliases", new JSONArray().put("西の葵"))), "metan"));

        JSONArray normalized = CastRegistry.patchUser(null, new JSONArray().put(Json.obj("id", "c_name", "name", "検査人物", "aliases", new JSONArray().put("ＡＯＢＡ").put("aoba"))), "zundamon");
        eq(find(normalized, "c_name").optJSONArray("aliases").length(), 1);
        eq(CastRegistry.analysisIdentity(person("c_new", "aoba", "metan"), index(normalized)), "c_name");
        JSONArray sameName = new JSONArray().put(person("c_one", "重複名", "zundamon")).put(person("c_two", "重複名", "metan"));
        eq(CastRegistry.migrate(sameName, "metan").length(), 4);
        rejected(() -> CastRegistry.migrate(new JSONArray().put(person("c_dup", "一", "metan")).put(person("c_dup", "二", "zundamon")), "metan"));
        rejected(() -> CastRegistry.mergeAnalysis(null, new JSONArray().put(person("c_dup", "一", "metan")).put(person("c_dup", "二", "zundamon")), "metan"));
        rejected(() -> CastRegistry.patchUser(null, new JSONArray().put(Json.obj("id", "c_dup", "name", "一")).put(Json.obj("id", "c_dup", "name", "二")), "metan"));
        rejected(() -> CastRegistry.patchUser(labelled, new JSONArray().put(Json.obj("id", "c_east", "offlineVoice", "shimmer")), "metan"));
        rejected(() -> CastRegistry.patchUser(labelled, new JSONArray().put(Json.obj("id", "c_east", "rate", "1")), "metan"));
        rejected(() -> CastRegistry.patchUser(labelled, new JSONArray().put(Json.obj("id", "c_east", "pitch", 5)), "metan"));
        JSONArray manualRange = CastRegistry.patchUser(labelled, new JSONArray().put(Json.obj("id", "c_east", "rate", .5, "pitch", 2.0)), "metan");
        eq(find(manualRange, "c_east").optDouble("rate"), .5); eq(find(manualRange, "c_east").optDouble("pitch"), 2.0);

        JSONObject legacyA = person("c_old_a", "旧人物A", "zundamon"), legacyB = person("c_old_b", "旧人物B", "metan");
        Json.put(legacyA, "aliases", new JSONArray().put("旧重複別名")); Json.put(legacyB, "aliases", new JSONArray().put("旧重複別名"));
        JSONArray oldConflict = CastRegistry.migrate(new JSONArray().put(legacyA).put(legacyB), "ryusei");
        eq(oldConflict.length(), 4); // An old conflict must remain editable after upgrade.
        eq(CastRegistry.analysisIdentity(person("c_old_guess", "旧重複別名", "miko"), index(oldConflict)), "unknown");
        JSONArray editedWithConflict = CastRegistry.patchUser(oldConflict, new JSONArray().put(Json.obj("id", "c_old_a", "offlineVoice", "miko")), "metan");
        eq(find(editedWithConflict, "c_old_a").optString("offlineVoice"), "miko");
        eq(find(editedWithConflict, "c_old_b").optJSONArray("aliases").optString(0), "旧重複別名");
        JSONArray fixedIncrementally = CastRegistry.patchUser(editedWithConflict, new JSONArray().put(Json.obj("id", "c_old_b", "aliases", new JSONArray().put("Bだけの別名"))), "metan");
        eq(CastRegistry.analysisIdentity(person("c_old_guess", "旧重複別名", "miko"), index(fixedIncrementally)), "c_old_a");
        rejected(() -> CastRegistry.patchUser(fixedIncrementally, new JSONArray().put(Json.obj("id", "c_old_b", "aliases", new JSONArray().put("旧重複別名"))), "metan"));
        JSONObject legacyNameA = person("c_name_a", "既存の名前A", "zundamon"), legacyNameB = person("c_name_b", "既存の名前B", "metan");
        Json.put(legacyNameA, "aliases", new JSONArray().put("既存の名前B"));
        JSONArray legacyNameConflict = CastRegistry.migrate(new JSONArray().put(legacyNameA).put(legacyNameB), "metan");
        eq(CastRegistry.analysisIdentity(person("c_guess_b", "既存の名前B", "zundamon"), index(legacyNameConflict)), "unknown");

        JSONArray reserved = CastRegistry.patchUser(null, new JSONArray().put(Json.obj("id", "narrator", "offlineVoice", "himari", "style", "一定の速度で"))
                .put(Json.obj("id", "unknown", "offlineVoice", "tsumugi")), "metan");
        JSONArray resistant = CastRegistry.mergeAnalysis(reserved, new JSONArray().put(person("narrator", "変更候補", "zundamon")).put(person("unknown", "変更候補2", "zundamon")), "miko");
        eq(resistant.toString(), reserved.toString());
        eq(find(resistant, "narrator").optString("offlineVoice"), "himari");
        eq(find(resistant, "unknown").optString("offlineVoice"), "tsumugi");

        JSONObject newAuto = person("c_new_auto", "新人物", "miko"); Json.put(newAuto, "manual", true); Json.put(newAuto, "profileLocked", true);
        JSONArray newCast = CastRegistry.mergeAnalysis(null, new JSONArray().put(newAuto), "zundamon");
        eq(find(newCast, "c_new_auto").optString("offlineVoice"), "miko");
        eq(find(newCast, "c_new_auto").optBoolean("manual"), false); eq(find(newCast, "c_new_auto").optBoolean("profileLocked"), false);
        JSONObject noSuggestion = person("c_default", "既定音声人物", "");
        eq(find(CastRegistry.mergeAnalysis(null, new JSONArray().put(noSuggestion), "zundamon"), "c_default").optString("offlineVoice"), OfflineVoices.initialVoice(noSuggestion, "zundamon"));
        System.out.println("CastRegistry production checks passed: " + checks);
    }
}
