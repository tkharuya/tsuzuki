package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Per-book identities and pinned voice profiles. All operations return independent copies. */
public final class CastRegistry {
    private CastRegistry() { }
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "unknown", "未特定", "不明", "男", "女", "少年", "少女", "彼", "彼女", "衛兵", "兵士", "店員",
            "男の声", "女の声", "man", "woman", "boy", "girl", "guard", "soldier", "narrator", "地の文"));

    /** Assign offline voices once while preserving all legacy identity and speaking metadata. */
    public static JSONArray migrate(JSONArray existing, String defaultOfflineVoice) {
        String fallback = fallback(defaultOfflineVoice);
        LinkedHashMap<String, JSONObject> entries = new LinkedHashMap<>();
        if (existing != null) for (int i = 0; i < existing.length(); i++) {
            JSONObject source = existing.optJSONObject(i);
            if (source == null) throw invalid("配役の形式が不正です。");
            String id = id(source);
            if (entries.containsKey(id)) throw invalid("配役IDが重複しています。");
            JSONObject person = Json.copy(source);
            if (person.optString("name").trim().isEmpty()) Json.put(person, "name", defaultName(id));
            Json.put(person, "aliases", aliases(Json.arr(person, "aliases")));
            if (!person.has("gender")) Json.put(person, "gender", "neutral");
            if (!person.has("tier")) Json.put(person, "tier", "narrator".equals(id) ? "narrator" : "support");
            if (!person.has("voice")) Json.put(person, "voice", "marin"); // Legacy OpenAI setting only.
            if (!person.has("deviceVoice")) Json.put(person, "deviceVoice", "");
            if (!person.has("style")) Json.put(person, "style", "");
            if (!person.has("rate")) Json.put(person, "rate", 1.0);
            if (!person.has("pitch")) Json.put(person, "pitch", 1.0);
            Json.put(person, "manual", person.optBoolean("manual"));
            Json.put(person, "profileLocked", person.optBoolean("profileLocked") || person.optBoolean("manual"));
            String voice = person.optString("offlineVoice");
            Json.put(person, "offlineVoice", OfflineVoices.valid(voice) ? voice : OfflineVoices.initialVoice(person, fallback));
            person.remove("geminiVoice");
            Json.put(person, "voiceLocked", true);
            entries.put(id, person);
        }
        for (String reserved : new String[]{"narrator", "unknown"})
            if (!entries.containsKey(reserved)) entries.put(reserved, base(reserved, fallback));
        // Older versions allowed overlapping aliases. Preserve them so the cast page can
        // repair them; resolution treats ambiguity as unknown instead of merging identities.
        return array(entries);
    }

    /**
     * A model may introduce identities and add evidence, but cannot replace an assigned voice,
     * display name, style or speaking baseline. Manual profiles are entirely protected.
     * Incoming IDs must already have been canonicalized by the analyzer.
     */
    public static JSONArray mergeAnalysis(JSONArray existing, JSONArray incoming, String defaultOfflineVoice) {
        JSONArray baseline = migrate(existing, defaultOfflineVoice);
        LinkedHashMap<String, JSONObject> entries = index(baseline);
        Set<String> ids = new HashSet<>();
        if (incoming != null) for (int i = 0; i < incoming.length(); i++) {
            JSONObject source = incoming.optJSONObject(i);
            if (source == null) throw invalid("配役の形式が不正です。");
            String id = id(source);
            if (!ids.add(id)) throw invalid("解析結果の配役IDが重複しています。");
            JSONObject old = entries.get(id);
            if (old == null) {
                entries.put(id, cleanAnalysis(source, defaultOfflineVoice));
                continue;
            }
            if (reserved(id) || old.optBoolean("profileLocked") || old.optBoolean("manual")) continue;
            JSONObject next = Json.copy(old);
            JSONArray proposed = aliases(Json.arr(source, "aliases"));
            String name = source.optString("name").trim();
            if (!name.isEmpty() && !normalize(name).equals(normalize(old.optString("name"))) && !generic(name)) proposed.put(name);
            Json.put(next, "aliases", union(Json.arr(old, "aliases"), proposed));
            if ("neutral".equals(old.optString("gender", "neutral")))
                Json.put(next, "gender", SpeechPlan.allowed(source.optString("gender"), "neutral", "neutral", "female", "male"));
            String proposedTier = SpeechPlan.allowed(source.optString("tier"), "extra", "main", "support", "extra");
            if (tier(proposedTier) > tier(old.optString("tier"))) Json.put(next, "tier", proposedTier);
            entries.put(id, next);
        }
        JSONArray result = array(entries);
        validateNewAliasConflicts(baseline, result);
        return result;
    }

    /** Apply a partial user edit atomically. Only this path may change an assigned offline voice. */
    public static JSONArray patchUser(JSONArray existing, JSONArray patch, String defaultOfflineVoice) {
        JSONArray baseline = migrate(existing, defaultOfflineVoice);
        LinkedHashMap<String, JSONObject> entries = index(baseline);
        Set<String> ids = new HashSet<>();
        if (patch != null) for (int i = 0; i < patch.length(); i++) {
            JSONObject value = patch.optJSONObject(i);
            if (value == null) throw invalid("配役の形式が不正です。");
            String id = id(value);
            if (!ids.add(id)) throw invalid("編集する配役IDが重複しています。");
            JSONObject old = entries.get(id), next = old == null ? base(id, fallback(defaultOfflineVoice)) : Json.copy(old);
            if (value.has("name")) {
                String name = text(value, "name", 100, false);
                if (!value.has("aliases") && old != null && !reserved(id) && !normalize(name).equals(normalize(old.optString("name"))))
                    Json.put(next, "aliases", union(Json.arr(next, "aliases"), new JSONArray().put(old.optString("name"))));
                Json.put(next, "name", name);
            }
            if (value.has("aliases")) {
                if (!(value.opt("aliases") instanceof JSONArray)) throw invalid("別名は文字列の一覧で指定してください。");
                Json.put(next, "aliases", aliases(value.optJSONArray("aliases")));
            }
            if (value.has("gender")) Json.put(next, "gender", choice(value, "gender", "neutral", "female", "male"));
            if (value.has("tier")) {
                String t = choice(value, "tier", "narrator", "main", "support", "extra");
                if ("narrator".equals(id)) t = "narrator";
                else if ("narrator".equals(t)) throw invalid("地の文以外には人物の役割を指定してください。");
                Json.put(next, "tier", t);
            }
            if (value.has("offlineVoice")) {
                String voice = text(value, "offlineVoice", 100, false);
                if (!OfflineVoices.valid(voice)) throw invalid("オフラインの音声名が不正です。");
                Json.put(next, "offlineVoice", voice);
            }
            if (value.has("voice")) {
                String voice = text(value, "voice", 100, false);
                if (!voice.equals(SpeechPlan.voice(voice))) throw invalid("旧音声設定の名前が不正です。");
                Json.put(next, "voice", voice);
            }
            if (value.has("deviceVoice")) Json.put(next, "deviceVoice", text(value, "deviceVoice", 200, true));
            if (value.has("style")) Json.put(next, "style", text(value, "style", 240, true));
            for (String field : new String[]{"rate", "pitch"}) if (value.has(field)) {
                Object raw = value.opt(field);
                if (!(raw instanceof Number)) throw invalid("話速と高さは数値で指定してください。");
                double number = ((Number) raw).doubleValue();
                double max = "rate".equals(field) ? 1.5 : 2.0;
                if (Double.isNaN(number) || Double.isInfinite(number) || number < .5 || number > max)
                    throw invalid("話速は0.5〜1.5、高さは0.5〜2.0で指定してください。");
                Json.put(next, field, number);
            }
            Json.put(next, "manual", true);
            Json.put(next, "profileLocked", true);
            Json.put(next, "voiceLocked", true);
            entries.put(id, next);
        }
        JSONArray result = array(entries);
        validateNewAliasConflicts(baseline, result);
        return result;
    }

    /**
     * Exact stable IDs are authoritative. Only an existing explicit alias can recover an
     * accidental new ID; a shared display name alone is ambiguous and never merges people.
     * Returns unknown for an ambiguous display name, or the new ID if no identity is known.
     */
    static String analysisIdentity(JSONObject person, Map<String, JSONObject> known) {
        String id = id(person);
        if (known.containsKey(id)) return id;
        Set<String> labels = labels(person), matched = new LinkedHashSet<>();
        for (Map.Entry<String, JSONObject> entry : known.entrySet()) {
            if (reserved(entry.getKey())) continue;
            for (String alias : aliasLabels(entry.getValue())) if (labels.contains(alias)) {
                matched.add(entry.getKey());
                // A legacy alias may also be another character's display name.
                for (Map.Entry<String, JSONObject> other : known.entrySet())
                    if (!reserved(other.getKey()) && alias.equals(normalize(other.getValue().optString("name")))) matched.add(other.getKey());
            }
        }
        if (matched.size() > 1) return "unknown";
        if (matched.size() == 1) return matched.iterator().next();
        String name = normalize(person.optString("name"));
        for (JSONObject existing : known.values())
            if (!name.isEmpty() && name.equals(normalize(existing.optString("name")))) return "unknown";
        return generic(person.optString("name")) ? "unknown" : id;
    }

    static JSONObject cleanAnalysis(JSONObject source, String defaultOfflineVoice) {
        String id = id(source);
        JSONObject result = base(id, fallback(defaultOfflineVoice));
        if (reserved(id)) return result;
        String name = source.optString("name", id).trim();
        Json.put(result, "name", Json.limit(name.isEmpty() ? id : name, 100));
        Json.put(result, "aliases", aliases(Json.arr(source, "aliases")));
        Json.put(result, "gender", SpeechPlan.allowed(source.optString("gender"), "neutral", "neutral", "female", "male"));
        Json.put(result, "tier", SpeechPlan.allowed(source.optString("tier"), "support", "main", "support", "extra"));
        Json.put(result, "voice", SpeechPlan.voice(source.optString("voice")));
        Json.put(result, "offlineVoice", OfflineVoices.initialVoice(source, fallback(defaultOfflineVoice)));
        Json.put(result, "style", Json.limit(source.optString("style"), 240));
        Json.put(result, "rate", SpeechPlan.clamp(source.optDouble("rate", 1), .7, 1.4));
        Json.put(result, "pitch", SpeechPlan.clamp(source.optDouble("pitch", 1), .7, 1.4));
        return result;
    }

    private static JSONObject base(String id, String voice) {
        return Json.obj("id", id, "name", defaultName(id), "aliases", new JSONArray(), "gender", "neutral",
                "tier", "narrator".equals(id) ? "narrator" : "unknown".equals(id) ? "extra" : "support",
                "voice", "marin", "deviceVoice", "", "style", "", "rate", 1.0, "pitch", 1.0,
                "manual", false, "offlineVoice", voice, "voiceLocked", true, "profileLocked", false);
    }
    private static String defaultName(String id) { return "narrator".equals(id) ? "地の文" : "unknown".equals(id) ? "未特定" : id; }
    private static boolean reserved(String id) { return "narrator".equals(id) || "unknown".equals(id); }
    private static String fallback(String voice) { return OfflineVoices.valid(voice) ? voice : OfflineVoices.defaultVoice(); }
    private static int tier(String value) { return "main".equals(value) ? 3 : "support".equals(value) ? 2 : 1; }
    private static String normalize(String value) { return SpeechPlan.normalizedName(value); }
    private static boolean generic(String value) { return GENERIC.contains(normalize(value)); }
    private static String id(JSONObject person) {
        String id = text(person, "id", 100, false);
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw invalid("配役IDの形式が不正です。");
        return id;
    }
    private static String text(JSONObject value, String key, int max, boolean empty) {
        Object raw = value.opt(key);
        if (!(raw instanceof String)) throw invalid("配役の文字列が不正です。");
        String text = ((String) raw).trim();
        if (text.length() > max || (!empty && text.isEmpty())) throw invalid("配役の文字列の長さが不正です。");
        return text;
    }
    private static String choice(JSONObject object, String key, String... values) {
        String value = text(object, key, 100, false);
        for (String allowed : values) if (allowed.equals(value)) return value;
        throw invalid("配役の選択値が不正です。");
    }
    private static JSONArray aliases(JSONArray input) {
        JSONArray result = new JSONArray(); Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            Object raw = input.opt(i);
            if (!(raw instanceof String) || ((String) raw).length() > 100) throw invalid("別名は100文字以内の文字列で指定してください。");
            String alias = ((String) raw).trim();
            if (!alias.isEmpty() && seen.add(normalize(alias))) result.put(alias);
        }
        return result;
    }
    private static JSONArray union(JSONArray a, JSONArray b) {
        JSONArray input = new JSONArray();
        for (JSONArray group : new JSONArray[]{a, b}) for (int i = 0; i < group.length(); i++) input.put(group.opt(i));
        return aliases(input);
    }
    private static Set<String> aliasLabels(JSONObject person) {
        Set<String> result = new HashSet<>(); JSONArray aliases = Json.arr(person, "aliases");
        for (int i = 0; i < aliases.length(); i++) {
            String name = normalize(aliases.optString(i));
            if (!name.isEmpty() && !generic(name)) result.add(name);
        }
        return result;
    }
    private static Set<String> labels(JSONObject person) {
        Set<String> result = aliasLabels(person); String name = normalize(person.optString("name"));
        if (!name.isEmpty() && !generic(name)) result.add(name);
        return result;
    }
    private static LinkedHashMap<String, JSONObject> index(JSONArray cast) {
        LinkedHashMap<String, JSONObject> result = new LinkedHashMap<>();
        for (int i = 0; i < cast.length(); i++) { JSONObject person = cast.optJSONObject(i); result.put(person.optString("id"), person); }
        return result;
    }
    private static JSONArray array(Map<String, JSONObject> entries) {
        JSONArray result = new JSONArray(); for (JSONObject person : entries.values()) result.put(person); return result;
    }
    private static void validateNewAliasConflicts(JSONArray baseline, JSONArray result) {
        Map<String, String> old = aliasConflicts(baseline);
        for (Map.Entry<String, String> conflict : aliasConflicts(result).entrySet())
            if (!old.containsKey(conflict.getKey()))
                throw invalid("別名「" + conflict.getValue() + "」が別の人物と重複しています。区別できる別名にしてください。");
    }
    private static Map<String, String> aliasConflicts(JSONArray cast) {
        Map<String, Set<String>> names = new LinkedHashMap<>();
        for (int i = 0; i < cast.length(); i++) {
            JSONObject person = cast.optJSONObject(i); String name = normalize(person.optString("name"));
            if (!names.containsKey(name)) names.put(name, new HashSet<String>());
            names.get(name).add(person.optString("id"));
        }
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        Map<String, String> conflicts = new LinkedHashMap<>();
        for (int i = 0; i < cast.length(); i++) {
            JSONObject person = cast.optJSONObject(i); String id = person.optString("id"); JSONArray aliases = Json.arr(person, "aliases");
            for (int j = 0; j < aliases.length(); j++) {
                String label = aliases.optString(j), key = normalize(label);
                Set<String> named = names.get(key);
                if (named != null) for (String other : named) addConflict(conflicts, key, label, id, other);
                if (owners.containsKey(key)) for (String other : owners.get(key)) addConflict(conflicts, key, label, id, other);
                else owners.put(key, new HashSet<String>());
                owners.get(key).add(id);
            }
        }
        return conflicts;
    }
    private static void addConflict(Map<String, String> conflicts, String key, String label, String id, String other) {
        if (id.equals(other)) return;
        String a = id.compareTo(other) < 0 ? id : other, b = id.compareTo(other) < 0 ? other : id;
        conflicts.put(key + "\u0000" + a + "\u0000" + b, label);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
