package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;

/** Source validation and bounded merge only; neither summaries nor factual accuracy are inferred here. */
public final class StoryMemoryTest {
    private static int checks;
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object actual, Object expected) { yes(expected.equals(actual), "expected " + expected + " got " + actual); }
    private interface Run { void run(); }
    private static void rejected(Run run) { try { run.run(); throw new AssertionError("bad memory accepted"); } catch (IllegalArgumentException expected) { checks++; } }
    private static JSONObject delta(JSONObject fact) { return Json.obj("facts", new JSONArray().put(fact)); }
    private static JSONObject fact(String id, JSONObject plan, int unit) {
        return SpeechChapterAnalysisTest.fact(id, plan.optString("chapterUrl"), Json.arr(plan, "units").optJSONObject(unit).optString("unitId"));
    }
    public static void main(String[] args) throws Exception {
        JSONObject plan = SpeechChapterAnalysisTest.plan(3, 4), memory = StoryMemory.empty(); JSONArray sources = new JSONArray().put(plan), cast = new JSONArray();
        eq(StoryMemory.normalize(null).toString(), memory.toString());
        JSONObject valid = StoryMemory.validateDelta(delta(fact("m_scene", plan, 1)), sources, cast);
        String original = memory.toString(); JSONObject merged = StoryMemory.merge(memory, valid, sources);
        eq(memory.toString(), original); eq(merged.optLong("revision"), 1L); eq(merged.optInt("throughChapterIndex"), 4);
        eq(merged.optString("throughChapterUrl"), plan.optString("chapterUrl"));
        JSONObject stored = Json.arr(merged, "facts").optJSONObject(0); eq(stored.optBoolean("manual"), false);
        eq(StoryMemory.forPrompt(merged).toString(), StoryMemory.normalize(merged).toString());

        JSONObject wrongSource = fact("m_bad", plan, 0); Json.put(Json.arr(wrongSource, "citations").optJSONObject(0), "unitId", "u0");
        rejected(() -> StoryMemory.validateDelta(delta(wrongSource), sources, cast));
        JSONObject wrongChapter = fact("m_bad", plan, 0); Json.put(Json.arr(wrongChapter, "citations").optJSONObject(0), "chapterUrl", "local://unread");
        rejected(() -> StoryMemory.validateDelta(delta(wrongChapter), sources, cast));
        JSONObject wrongPerson = fact("m_bad", plan, 0); Json.put(wrongPerson, "subjectId", "c_invented");
        rejected(() -> StoryMemory.validateDelta(delta(wrongPerson), sources, cast));
        JSONObject noCitations = fact("m_bad", plan, 0); Json.put(noCitations, "citations", new JSONArray());
        rejected(() -> StoryMemory.validateDelta(delta(noCitations), sources, cast));
        JSONObject manualSpoof = fact("m_bad", plan, 0); Json.put(manualSpoof, "manual", true);
        rejected(() -> StoryMemory.validateDelta(delta(manualSpoof), sources, cast));
        JSONObject text = fact("m_bad", plan, 0); Json.put(text, "text", new String(new char[321]).replace('\0', 'a'));
        rejected(() -> StoryMemory.validateDelta(delta(text), sources, cast));
        JSONObject duplicate = Json.obj("facts", new JSONArray().put(fact("same", plan, 0)).put(fact("same", plan, 1)));
        rejected(() -> StoryMemory.validateDelta(duplicate, sources, cast));

        JSONObject manual = Json.copy(stored); Json.put(manual, "manual", true); Json.put(manual, "text", "利用者が固定した作品メモ"); Json.put(manual, "citations", new JSONArray());
        Json.put(merged, "facts", new JSONArray().put(manual));
        yes(StoryMemory.normalize(merged).optJSONArray("facts").optJSONObject(0).optBoolean("manual"), "uncited user note is explicitly manual");
        JSONObject replacement = fact("m_scene", plan, 2); Json.put(replacement, "text", "AIが提案する別の情報");
        JSONObject preserved = StoryMemory.merge(merged, StoryMemory.validateDelta(delta(replacement), sources, cast), sources);
        eq(Json.arr(preserved, "facts").optJSONObject(0).optString("text"), "利用者が固定した作品メモ");
        JSONObject uncertain = fact("m_maybe", plan, 0); Json.put(uncertain, "uncertain", true);
        JSONObject combined = StoryMemory.combineDeltas(valid, StoryMemory.validateDelta(delta(uncertain), sources, cast));
        eq(Json.arr(combined, "facts").length(), 2); yes(Json.arr(combined, "facts").optJSONObject(1).optBoolean("uncertain"), "hypothesis remains labelled uncertain");

        JSONArray many = new JSONArray().put(manual);
        for (int i = 1; i < StoryMemory.MAX_FACTS; i++) { JSONObject f = fact("old_" + i, plan, 0); Json.put(f, "type", "event"); Json.put(f, "manual", false); many.put(f); }
        JSONObject full = StoryMemory.empty(); Json.put(full, "facts", many);
        JSONObject bounded = StoryMemory.merge(full, delta(fact("new_scene", plan, 1)), sources);
        eq(Json.arr(bounded, "facts").length(), StoryMemory.MAX_FACTS); eq(bounded.optInt("droppedFacts"), 1);
        SetIds ids = new SetIds(Json.arr(bounded, "facts")); yes(ids.has("m_scene"), "manual fact survives capacity pruning");
        yes(!ids.has("old_1") && ids.has("new_scene"), "oldest automatic event is replaced before manual or new facts");
        Json.put(full, "revision", -1); rejected(() -> StoryMemory.normalize(full));
        JSONObject badBool = Json.copy(memory); JSONObject badFact = fact("bad_bool", plan, 0); Json.put(badFact, "uncertain", "true"); Json.put(badBool, "facts", new JSONArray().put(badFact));
        rejected(() -> StoryMemory.normalize(badBool));
        System.out.println("StoryMemoryTest: PASS (" + checks + " provenance/manual-note/capacity checks; no model factual-accuracy claim)");
    }
    private static final class SetIds {
        final java.util.Set<String> values = new java.util.HashSet<>();
        SetIds(JSONArray facts) { for (int i = 0; i < facts.length(); i++) values.add(facts.optJSONObject(i).optString("id")); }
        boolean has(String id) { return values.contains(id); }
    }
}
