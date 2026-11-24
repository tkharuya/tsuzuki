package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

/** Same-response identity resolution and fail-closed memory diagnostics, with no real inference. */
public final class SpeechMemoryIdentityTest {
    private static int checks;
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object actual, Object expected) { yes(expected.equals(actual), "expected " + expected + " got " + actual); }
    private interface Run { void run() throws Exception; }
    private static JSONObject person(String id, String name) {
        return Json.obj("id", id, "name", name, "aliases", new JSONArray(), "gender", "neutral", "tier", "main",
                "offlineVoice", "miko", "style", "自動で変更してはいけない候補", "rate", 1.1, "pitch", 1.1);
    }
    static JSONArray known() {
        JSONObject alice = person("c_alice", "アリス"), bob = person("c_bob", "ボブ");
        Json.put(alice, "aliases", new JSONArray().put("アリスさん")); Json.put(bob, "aliases", new JSONArray().put("ボブさん"));
        for (JSONObject value : new JSONObject[]{alice, bob}) {
            Json.put(value, "manual", true); Json.put(value, "profileLocked", true); Json.put(value, "offlineVoice", "zundamon");
            Json.put(value, "style", "利用者が固定した話し方"); Json.put(value, "rate", .9); Json.put(value, "pitch", 1.05);
        }
        return CastRegistry.migrate(new JSONArray().put(alice).put(bob), "zundamon");
    }
    static JSONObject metadata(JSONObject request) {
        JSONObject input = SpeechChapterAnalysisTest.data(request), metadata;
        boolean history = "history_memory".equals(input.optString("analysisStage"));
        if (history) {
            JSONObject chapter = Json.arr(input, "chapters").optJSONObject(0);
            metadata = Json.obj("memoryDelta", Json.obj("facts", new JSONArray().put(SpeechChapterAnalysisTest.fact("m_relation",
                    chapter.optString("chapterUrl"), Json.arr(chapter, "units").optJSONObject(0).optString("unitId")))));
        } else {
            metadata = SpeechChapterAnalysisTest.compact(request);
            JSONArray assignments = Json.arr(metadata, "assignments");
            for (int i = 0; i < assignments.length(); i++) { Json.put(assignments.optJSONObject(i), "speakerId", "c_alias_alice"); Json.put(assignments.optJSONObject(i), "confidence", .95); }
        }
        Json.put(metadata, "cast", new JSONArray().put(person("c_alias_alice", "アリスさん")).put(person("c_alias_bob", "ボブさん")));
        JSONObject fact = Json.arr(metadata.optJSONObject("memoryDelta"), "facts").optJSONObject(0);
        Json.put(fact, "type", "relationship"); Json.put(fact, "subjectId", "c_alias_alice"); Json.put(fact, "objectId", "c_alias_bob");
        return metadata;
    }
    private static SpeechAnalyzer analyzer(SpeechAnalyzer.Transport transport) {
        return new SpeechAnalyzer(transport).withDetailedActing(true).withChapterWorkflow(true);
    }
    private static JSONObject analyze(SpeechAnalyzer analyzer, JSONObject plan, JSONArray cast, SpeechAnalyzer.Progress progress) throws Exception {
        return analyzer.analyze(plan, cast, "fixture-private-key", "gpt-6-luna", () -> false, progress);
    }
    private static StoryMemory.ValidationFailure failure(Run action, StoryMemory.Reason reason) throws Exception {
        try { action.run(); throw new AssertionError("invalid memory accepted"); }
        catch (Exception error) {
            Throwable cause = error; while (!(cause instanceof StoryMemory.ValidationFailure) && cause.getCause() != null) cause = cause.getCause();
            yes(cause instanceof StoryMemory.ValidationFailure, "typed memory failure survives the analyzer boundary");
            StoryMemory.ValidationFailure result = (StoryMemory.ValidationFailure) cause; eq(result.reason(), reason);
            JSONObject diagnostics = result.diagnostics(); eq(diagnostics.optString("code"), "invalid_memory");
            eq(diagnostics.optString("stage"), "memory_" + reason.name().toLowerCase(java.util.Locale.ROOT));
            eq(diagnostics.optString("responseShape"), "memory");
            String safe = diagnostics + result.getMessage();
            for (String secret : new String[]{"fixture-private-key", "PRIVATE-NOVEL", "https://", "local://", "アリス", "c_alias", "c_missing"})
                yes(!safe.contains(secret), "memory diagnostics exclude data: " + secret);
            return result;
        }
    }
    public static void main(String[] args) throws Exception {
        sameResponseCanonicalization(); ambiguousAndUnknownRemainRejected(); checkpointAndResume(); safeReasonCodes();
        System.out.println("SpeechMemoryIdentityTest: PASS (" + checks + " same-response aliases, manual locks, resume, provenance and safe diagnostics checks; synthetic inference)");
    }
    private static void sameResponseCanonicalization() throws Exception {
        for (boolean history : new boolean[]{false, true}) {
            JSONObject original = SpeechChapterAnalysisTest.plan(3, 0); JSONArray cast = known(); String castBefore = cast.toString();
            final JSONObject[] response = {null}; final int[] calls = {0};
            SpeechAnalyzer analyzer = analyzer((request, key, cancel) -> {
                calls[0]++; response[0] = metadata(request);
                yes(request.optString("instructions").contains("1..100 ASCII"), "ID limits are stated to the model");
                yes(request.optString("instructions").contains("1..4 UNIQUE"), "citation uniqueness is stated to the model");
                return SpeechChapterAnalysisTest.envelope(response[0]);
            });
            JSONObject result = history ? analyzer.summarizeHistory(new JSONArray().put(original), cast, "fixture-private-key", "gpt-6-luna", () -> false)
                    : analyze(analyzer, original, cast, null);
            JSONObject fact = Json.arr(result.optJSONObject("memoryDelta"), "facts").optJSONObject(0);
            eq(fact.optString("subjectId"), "c_alice"); eq(fact.optString("objectId"), "c_bob");
            eq(Json.arr(response[0].optJSONObject("memoryDelta"), "facts").optJSONObject(0).optString("subjectId"), "c_alias_alice");
            eq(Json.arr(fact, "citations").optJSONObject(0).optString("unitId"), Json.arr(original, "units").optJSONObject(0).optString("unitId"));
            eq(cast.toString(), castBefore); eq(result.optJSONArray("cast").toString(), castBefore);
            if (!history) {
                JSONObject saved = result.optJSONObject("plan"); eq(Json.arr(saved, "units").optJSONObject(0).optString("speakerId"), "c_alice");
                eq(saved.optString("memoryDeltaInputHash"), original.optString("inputHash"));
                JSONObject reused = analyze(analyzer, saved, result.optJSONArray("cast"), null).optJSONObject("plan");
                eq(calls[0], 1); eq(reused.optJSONObject("memoryDelta").toString(), saved.optJSONObject("memoryDelta").toString());
            }
        }
    }
    private static void ambiguousAndUnknownRemainRejected() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            final int which = mode; JSONArray cast = known();
            if (which == 0) Json.arr(cast.optJSONObject(1), "aliases").put("アリスさん");
            final JSONObject[] checkpoint = {null};
            SpeechAnalyzer analyzer = analyzer((request, key, cancel) -> {
                JSONObject result = metadata(request), fact = Json.arr(result.optJSONObject("memoryDelta"), "facts").optJSONObject(0);
                if (which == 1) Json.put(fact, "subjectId", "c_missing_PRIVATE-NOVEL");
                if (which == 2) Json.put(Json.arr(fact, "citations").optJSONObject(0), "unitId", "u0");
                if (which == 3) Json.put(Json.arr(fact, "citations").optJSONObject(0), "chapterUrl", "https://private.invalid/PRIVATE-NOVEL");
                return SpeechChapterAnalysisTest.envelope(result);
            });
            StoryMemory.Reason expected = which < 2 ? StoryMemory.Reason.PERSON : StoryMemory.Reason.CITATION;
            StoryMemory.ValidationFailure error = failure(() -> analyze(analyzer, SpeechChapterAnalysisTest.plan(2, 0), cast,
                    (plan, people) -> checkpoint[0] = Json.copy(plan)), expected);
            eq(checkpoint[0].optInt("analyzedUnits"), 0); eq(checkpoint[0].optString("analysisStatus"), "error");
            eq(checkpoint[0].optJSONObject("analysisError").toString(), error.diagnostics().toString());
            yes(!checkpoint[0].has("memoryDelta"), "rejected memory does not partially adopt acting or memory");
            eq(error.diagnostics().optInt("factIndex", -1), 0);
            if (which >= 2) eq(error.diagnostics().optInt("citationIndex", -1), 0);
            try { analyzer.summarizeHistory(new JSONArray().put(SpeechChapterAnalysisTest.plan(2, 0)), cast, "fixture-private-key", "gpt-6-luna", () -> false); throw new AssertionError("history accepted invalid memory"); }
            catch (StoryMemory.ValidationFailure direct) { eq(direct.reason(), expected); }
        }
    }
    private static void checkpointAndResume() throws Exception {
        JSONObject original = SpeechChapterAnalysisTest.plan(321, 0); final int[] calls = {0}; final JSONObject[] saved = {null}; final JSONArray[] savedCast = {known()};
        SpeechAnalyzer partial = analyzer((request, key, cancel) -> {
            JSONObject response = metadata(request);
            if (++calls[0] == 2) Json.put(Json.arr(response.optJSONObject("memoryDelta"), "facts").optJSONObject(0), "subjectId", "c_missing");
            return SpeechChapterAnalysisTest.envelope(response);
        });
        failure(() -> analyze(partial, original, known(), (plan, cast) -> { saved[0] = Json.copy(plan); savedCast[0] = cast; }), StoryMemory.Reason.PERSON);
        eq(saved[0].optInt("analyzedUnits"), 320); eq(calls[0], 2);
        eq(Json.arr(saved[0].optJSONObject("memoryDelta"), "facts").optJSONObject(0).optString("subjectId"), "c_alice");
        SpeechAnalyzer resume = analyzer((request, key, cancel) -> {
            calls[0]++; eq(Json.arr(SpeechChapterAnalysisTest.data(request), "targetUnits").length(), 1);
            return SpeechChapterAnalysisTest.envelope(metadata(request));
        });
        JSONObject complete = analyze(resume, saved[0], savedCast[0], null).optJSONObject("plan");
        eq(complete.optInt("analyzedUnits"), 321); eq(calls[0], 3); eq(complete.optString("analysisStatus"), "complete");
        yes(!complete.has("analysisError"), "successful resume clears old diagnostics");
        eq(Json.arr(complete.optJSONObject("memoryDelta"), "facts").length(), 2);
        for (int i = 0; i < 321; i++) eq(Json.arr(complete, "units").optJSONObject(i).optString("unitId"), Json.arr(original, "units").optJSONObject(i).optString("unitId"));
    }
    private static void safeReasonCodes() throws Exception {
        JSONObject plan = SpeechChapterAnalysisTest.plan(2, 0); JSONArray sources = new JSONArray().put(plan);
        JSONObject valid = SpeechChapterAnalysisTest.fact("m_ok", plan.optString("chapterUrl"), Json.arr(plan, "units").optJSONObject(0).optString("unitId"));
        for (StoryMemory.Reason reason : new StoryMemory.Reason[]{StoryMemory.Reason.FORMAT, StoryMemory.Reason.ID, StoryMemory.Reason.TEXT_LIMIT, StoryMemory.Reason.DUPLICATE, StoryMemory.Reason.LIMIT}) {
            JSONObject value = Json.copy(valid); JSONArray facts = new JSONArray().put(value);
            if (reason == StoryMemory.Reason.FORMAT) Json.put(value, "manual", true);
            if (reason == StoryMemory.Reason.ID) Json.put(value, "id", "人物_PRIVATE-NOVEL");
            if (reason == StoryMemory.Reason.TEXT_LIMIT) Json.put(value, "text", new String(new char[321]).replace('\0', 'あ'));
            if (reason == StoryMemory.Reason.DUPLICATE) Json.arr(value, "citations").put(Json.copy(Json.arr(value, "citations").optJSONObject(0)));
            if (reason == StoryMemory.Reason.LIMIT) for (int i = 1; i < 25; i++) { JSONObject extra = Json.copy(value); Json.put(extra, "id", "m_" + i); facts.put(extra); }
            StoryMemory.ValidationFailure error = failure(() -> StoryMemory.validateModelDelta(Json.obj("facts", facts), sources, known()), reason);
            if (reason != StoryMemory.Reason.LIMIT) eq(error.diagnostics().optInt("factIndex", -1), 0);
            if (reason == StoryMemory.Reason.DUPLICATE) eq(error.diagnostics().optInt("citationIndex", -1), 1);
        }
    }
}
