package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, source-cited story facts. Memory is untrusted narrative data, never model instructions. */
public final class StoryMemory {
    public static final int VERSION = 1;
    public static final int MAX_FACTS = 120;
    public static final int MAX_DELTA_FACTS = 24;
    public static final int MAX_TEXT_CHARS = 320;
    private static final String[] TYPES = {"character", "relationship", "event", "state", "scene"};
    private StoryMemory() { }

    public enum Reason { FORMAT, ID, PERSON, CITATION, TEXT_LIMIT, DUPLICATE, LIMIT }
    /** Diagnostics identify the failed rule, never novel text, names, URLs, IDs or credentials. */
    public static final class ValidationFailure extends IllegalArgumentException {
        private final Reason reason;
        private final int factIndex, citationIndex;
        private ValidationFailure(Reason reason, int factIndex, int citationIndex) {
            super("作品メモの形式または根拠が一致しません。保存済みのメモは保持しています。");
            this.reason = reason; this.factIndex = factIndex; this.citationIndex = citationIndex;
        }
        public Reason reason() { return reason; }
        public JSONObject diagnostics() {
            JSONObject value = Json.obj("code", "invalid_memory", "stage", "memory_" + reason.name().toLowerCase(java.util.Locale.ROOT), "responseShape", "memory");
            if (factIndex >= 0) Json.put(value, "factIndex", factIndex);
            if (citationIndex >= 0) Json.put(value, "citationIndex", citationIndex);
            return value;
        }
        private ValidationFailure atFact(int index) { return new ValidationFailure(reason, index, citationIndex); }
        private ValidationFailure atCitation(int index) { return new ValidationFailure(reason, factIndex, index); }
    }

    public static JSONObject empty() {
        return Json.obj("version", VERSION, "revision", 0, "throughChapterUrl", "", "throughChapterIndex", -1,
                "facts", new JSONArray(), "droppedFacts", 0);
    }
    public static JSONObject normalize(JSONObject value) {
        if (value == null || value.length() == 0) return empty();
        if (!(value.opt("version") instanceof Number) || value.optDouble("version") != VERSION
                || !(value.opt("facts") instanceof JSONArray)) throw invalid();
        long revision = integer(value.opt("revision"), 0, Long.MAX_VALUE);
        int through = (int) integer(value.opt("throughChapterIndex"), -1, Integer.MAX_VALUE);
        String url = text(value, "throughChapterUrl", 4096, true);
        JSONArray facts = Json.arr(value, "facts"), clean = new JSONArray();
        if (facts.length() > MAX_FACTS) throw invalid(Reason.LIMIT);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact;
            try { fact = cleanFact(facts.optJSONObject(i), true); }
            catch (ValidationFailure failure) { throw failure.atFact(i); }
            if (!ids.add(fact.optString("id"))) throw invalid(Reason.DUPLICATE).atFact(i);
            clean.put(fact);
        }
        return Json.obj("version", VERSION, "revision", revision, "throughChapterUrl", url,
                "throughChapterIndex", through, "facts", clean, "droppedFacts", Math.max(0, value.optInt("droppedFacts")));
    }

    /** Facts may cite only actual original units in this supplied chapter/history batch. */
    public static JSONObject validateModelDelta(JSONObject raw, JSONArray sourcePlans, JSONArray knownCast) {
        JSONArray facts = raw == null ? null : raw.optJSONArray("facts");
        if (facts != null && facts.length() > MAX_DELTA_FACTS) throw invalid(Reason.LIMIT);
        return validateDelta(raw, sourcePlans, knownCast);
    }
    public static JSONObject validateDelta(JSONObject raw, JSONArray sourcePlans, JSONArray knownCast) {
        exact(raw, "facts");
        if (!(raw.opt("facts") instanceof JSONArray)) throw invalid();
        JSONArray facts = raw.optJSONArray("facts");
        if (facts.length() > MAX_FACTS) throw invalid(Reason.LIMIT);
        Map<String, Set<String>> sources = sourceIds(sourcePlans);
        Set<String> people = new HashSet<>();
        if (knownCast != null) for (int i = 0; i < knownCast.length(); i++) {
            JSONObject person = knownCast.optJSONObject(i);
            if (person != null) people.add(person.optString("id"));
        }
        JSONArray clean = new JSONArray(); Set<String> ids = new HashSet<>();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact;
            try { fact = cleanFact(facts.optJSONObject(i), false); }
            catch (ValidationFailure failure) { throw failure.atFact(i); }
            if (!ids.add(fact.optString("id"))) throw invalid(Reason.DUPLICATE).atFact(i);
            for (String key : new String[]{"subjectId", "objectId"}) {
                String id = fact.optString(key);
                if (!id.isEmpty() && !people.contains(id)) throw invalid(Reason.PERSON).atFact(i);
            }
            JSONArray citations = Json.arr(fact, "citations");
            for (int j = 0; j < citations.length(); j++) {
                JSONObject citation = citations.optJSONObject(j);
                Set<String> units = sources.get(citation.optString("chapterUrl"));
                if (units == null || !units.contains(citation.optString("unitId"))) throw invalid(Reason.CITATION).atFact(i).atCitation(j);
            }
            clean.put(fact);
        }
        return Json.obj("facts", clean);
    }

    /** Root owns transaction/idempotence; this pure merge protects manually fixed facts. */
    public static JSONObject merge(JSONObject original, JSONObject delta, JSONArray sourcePlans) {
        JSONObject memory = normalize(original);
        if (delta == null || !(delta.opt("facts") instanceof JSONArray)) throw invalid();
        JSONArray incoming = delta.optJSONArray("facts");
        if (incoming.length() > MAX_FACTS) throw invalid(Reason.LIMIT);
        LinkedHashMap<String, JSONObject> facts = new LinkedHashMap<>();
        JSONArray saved = Json.arr(memory, "facts");
        for (int i = 0; i < saved.length(); i++) {
            JSONObject fact = saved.optJSONObject(i); facts.put(fact.optString("id"), fact);
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject fact;
            try { fact = cleanFact(incoming.optJSONObject(i), false); }
            catch (ValidationFailure failure) { throw failure.atFact(i); }
            String id = fact.optString("id"); if (!ids.add(id)) throw invalid(Reason.DUPLICATE).atFact(i);
            JSONObject old = facts.get(id);
            if (old != null && old.optBoolean("manual")) continue;
            Json.put(fact, "manual", false);
            facts.remove(id); facts.put(id, fact); // Changed facts become recent without changing their identity.
        }
        int removed = 0;
        while (facts.size() > MAX_FACTS) {
            String candidate = null;
            for (JSONObject fact : facts.values()) if (!fact.optBoolean("manual")
                    && ("event".equals(fact.optString("type")) || "scene".equals(fact.optString("type")))) {
                candidate = fact.optString("id"); break;
            }
            if (candidate == null) for (JSONObject fact : facts.values()) if (!fact.optBoolean("manual")) {
                candidate = fact.optString("id"); break;
            }
            if (candidate == null) throw invalid(Reason.LIMIT);
            facts.remove(candidate); removed++;
        }
        JSONArray out = new JSONArray(); for (JSONObject fact : facts.values()) out.put(fact);
        Json.put(memory, "facts", out); Json.put(memory, "revision", memory.optLong("revision") + 1);
        Json.put(memory, "droppedFacts", memory.optInt("droppedFacts") + removed);
        if (sourcePlans != null && sourcePlans.length() > 0) {
            JSONObject last = sourcePlans.optJSONObject(sourcePlans.length() - 1);
            if (last == null) throw invalid();
            Json.put(memory, "throughChapterUrl", last.optString("chapterUrl"));
            Json.put(memory, "throughChapterIndex", last.optInt("chapterIndex", -1));
        }
        return memory;
    }

    /** Send the bounded facts with provenance and locks. Do not reinterpret them as instructions. */
    public static JSONObject forPrompt(JSONObject memory) { return normalize(memory); }
    public static JSONObject emptyDelta() { return Json.obj("facts", new JSONArray()); }

    /** Validated chunk deltas share one chapter commit; retain updates in last-write order. */
    public static JSONObject combineDeltas(JSONObject first, JSONObject next) {
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();
        for (JSONObject delta : new JSONObject[]{first, next}) {
            if (delta == null) continue;
            JSONArray facts = Json.arr(delta, "facts");
            if (facts.length() > MAX_FACTS) throw invalid(Reason.LIMIT);
            for (int i = 0; i < facts.length(); i++) {
                JSONObject fact;
                try { fact = cleanFact(facts.optJSONObject(i), false); }
                catch (ValidationFailure failure) { throw failure.atFact(i); }
                merged.remove(fact.optString("id")); merged.put(fact.optString("id"), fact);
            }
        }
        while (merged.size() > MAX_FACTS) merged.remove(merged.keySet().iterator().next());
        JSONArray facts = new JSONArray(); for (JSONObject fact : merged.values()) facts.put(fact);
        return Json.obj("facts", facts);
    }

    static JSONObject schema() {
        JSONObject citation = object(Json.obj("chapterUrl", string(), "unitId", string()));
        JSONObject fact = object(Json.obj("id", string(), "type", Json.obj("type", "string", "enum", new JSONArray(Arrays.asList(TYPES))),
                "subjectId", string(), "objectId", string(), "text", string(), "uncertain", Json.obj("type", "boolean"),
                "citations", Json.obj("type", "array", "items", citation)));
        return object(Json.obj("facts", Json.obj("type", "array", "items", fact)));
    }

    private static JSONObject cleanFact(JSONObject value, boolean stored) {
        if (value == null) throw invalid();
        if (stored && value.has("manual")) exact(value, "id", "type", "subjectId", "objectId", "text", "uncertain", "citations", "manual");
        else exact(value, "id", "type", "subjectId", "objectId", "text", "uncertain", "citations");
        String id = text(value, "id", 100, false, Reason.ID), type = text(value, "type", 24, false);
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw invalid(Reason.ID);
        if (!Arrays.asList(TYPES).contains(type)) throw invalid();
        String subject = text(value, "subjectId", 100, true, Reason.PERSON), object = text(value, "objectId", 100, true, Reason.PERSON);
        if ((!subject.isEmpty() && !subject.matches("[A-Za-z0-9_-]{1,100}"))
                || (!object.isEmpty() && !object.matches("[A-Za-z0-9_-]{1,100}"))) throw invalid(Reason.PERSON);
        if (!(value.opt("uncertain") instanceof Boolean)) throw invalid();
        if (!(value.opt("citations") instanceof JSONArray)) throw invalid(Reason.CITATION);
        JSONArray citations = value.optJSONArray("citations"), clean = new JSONArray();
        if (citations.length() < (stored && value.optBoolean("manual") ? 0 : 1)) throw invalid(Reason.CITATION);
        if (citations.length() > 4) throw invalid(Reason.LIMIT);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < citations.length(); i++) {
            JSONObject citation = citations.optJSONObject(i);
            try { exact(citation, "chapterUrl", "unitId"); }
            catch (ValidationFailure failure) { throw invalid(Reason.CITATION).atCitation(i); }
            String url, unit;
            try {
                url = text(citation, "chapterUrl", 4096, false, Reason.CITATION);
                unit = text(citation, "unitId", 180, false, Reason.CITATION);
            } catch (ValidationFailure failure) { throw failure.atCitation(i); }
            if (!seen.add(url + "\n" + unit)) throw invalid(Reason.DUPLICATE).atCitation(i);
            clean.put(Json.obj("chapterUrl", url, "unitId", unit));
        }
        JSONObject result = Json.obj("id", id, "type", type, "subjectId", subject, "objectId", object,
                "text", text(value, "text", MAX_TEXT_CHARS, false, Reason.TEXT_LIMIT), "uncertain", value.optBoolean("uncertain"), "citations", clean);
        if (stored) {
            if (value.has("manual") && !(value.opt("manual") instanceof Boolean)) throw invalid();
            Json.put(result, "manual", value.optBoolean("manual"));
        }
        return result;
    }
    private static Map<String, Set<String>> sourceIds(JSONArray plans) {
        if (plans == null || plans.length() == 0) throw invalid(Reason.CITATION);
        Map<String, Set<String>> sources = new HashMap<>();
        for (int i = 0; i < plans.length(); i++) {
            JSONObject plan = plans.optJSONObject(i);
            if (plan == null || plan.optString("chapterUrl").isEmpty() || !(plan.opt("units") instanceof JSONArray)) throw invalid(Reason.CITATION);
            String url = plan.optString("chapterUrl");
            Set<String> units = sources.computeIfAbsent(url, ignored -> new HashSet<>());
            JSONArray rows = Json.arr(plan, "units");
            for (int j = 0; j < rows.length(); j++) {
                JSONObject unit = rows.optJSONObject(j);
                if (unit == null || unit.optString("unitId").isEmpty()) throw invalid(Reason.CITATION);
                units.add(unit.optString("unitId"));
            }
        }
        return sources;
    }
    private static String text(JSONObject value, String key, int max, boolean empty) {
        return text(value, key, max, empty, Reason.FORMAT);
    }
    private static String text(JSONObject value, String key, int max, boolean empty, Reason reason) {
        Object raw = value.opt(key);
        if (!(raw instanceof String)) throw invalid(reason == Reason.TEXT_LIMIT ? Reason.FORMAT : reason);
        String text = (String) raw;
        if (text.length() > max) throw invalid(reason);
        if (text.indexOf('\0') >= 0 || !empty && text.trim().isEmpty()) throw invalid(reason == Reason.TEXT_LIMIT ? Reason.FORMAT : reason);
        return text;
    }
    private static long integer(Object raw, long min, long max) {
        if (!(raw instanceof Number)) throw invalid(); double number = ((Number)raw).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) throw invalid();
        return ((Number)raw).longValue();
    }
    private static void exact(JSONObject value, String... keys) {
        if (value == null || value.length() != keys.length) throw invalid();
        Set<String> allowed = new HashSet<>(Arrays.asList(keys));
        for (Iterator<String> it = value.keys(); it.hasNext();) if (!allowed.remove(it.next())) throw invalid();
        if (!allowed.isEmpty()) throw invalid();
    }
    private static JSONObject string() { return Json.obj("type", "string"); }
    private static JSONObject object(JSONObject properties) {
        JSONArray keys = new JSONArray(); for (Iterator<String> it = properties.keys(); it.hasNext();) keys.put(it.next());
        return Json.obj("type", "object", "properties", properties, "required", keys, "additionalProperties", false);
    }
    private static ValidationFailure invalid() { return invalid(Reason.FORMAT); }
    private static ValidationFailure invalid(Reason reason) { return new ValidationFailure(reason, -1, -1); }
}
