package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real state machine with synthetic inference: correctness gates, not an AI accuracy benchmark. */
public final class SpeechDetailedAnalysisTest {
    private static int checks;
    private static void yes(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void eq(Object actual, Object expected) { yes(expected.equals(actual), "expected " + expected + " but got " + actual); }
    private static JSONObject plan(int count) {
        JSONArray paragraphs = new JSONArray();
        for (int i = 0; i < count; i++) paragraphs.put(Json.obj("text", "「これは自作の検査台詞" + i + "です。」"));
        return SpeechPlan.create("detailed-fixture", Json.obj("url", "local://detailed-fixture/1", "paragraphs", paragraphs), null);
    }
    private static JSONObject data(JSONObject request) {
        return Json.parse(Json.arr(Json.arr(request, "input").optJSONObject(0), "content").optJSONObject(0).optString("text"));
    }
    private static JSONObject metadata(JSONObject request) {
        JSONArray targets = Json.arr(data(request), "targetUnits"), assignments = new JSONArray();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i); JSONArray parts = new JSONArray(), supplied = Json.arr(target, "parts");
            for (int j = 0; j < supplied.length(); j++) parts.put(SpeechPerformanceTest.acting(supplied.optJSONObject(j).optString("partId")));
            assignments.put(Json.obj("unitId", target.optString("unitId"), "speakerId", "unknown", "kind", "dialogue",
                    "emotion", "anger", "intensity", .8, "rate", 1.1, "pitch", 1.05, "confidence", .4, "parts", parts));
        }
        return Json.obj("cast", new JSONArray(), "assignments", assignments);
    }
    private static JSONObject envelope(JSONObject metadata) {
        return Json.obj("status", "completed", "output", new JSONArray().put(Json.obj("type", "message", "status", "completed",
                "phase", "final_answer", "content", new JSONArray().put(Json.obj("type", "output_text", "text", metadata.toString())))),
                "usage", Json.obj("input_tokens", 11, "output_tokens", 7));
    }
    private static JSONObject person(String id, String name) {
        return Json.obj("id", id, "name", name, "aliases", new JSONArray(), "gender", "neutral", "tier", "main",
                "offlineVoice", "metan", "style", "落ち着いた口調", "rate", 1.0, "pitch", 1.0);
    }
    private static JSONObject find(JSONArray cast, String id) {
        for (int i = 0; i < cast.length(); i++) if (id.equals(cast.optJSONObject(i).optString("id"))) return cast.optJSONObject(i);
        return null;
    }
    private static void assign(JSONObject metadata, String id) {
        JSONArray assignments = Json.arr(metadata, "assignments");
        for (int i = 0; i < assignments.length(); i++) {
            Json.put(assignments.optJSONObject(i), "speakerId", id); Json.put(assignments.optJSONObject(i), "confidence", .95);
        }
    }
    private static SpeechAnalyzer detailed(SpeechAnalyzer.Transport transport) { return new SpeechAnalyzer(transport).withDetailedActing(true); }
    private static JSONObject analyze(SpeechAnalyzer analyzer, JSONObject source, SpeechAnalyzer.Cancel cancel, SpeechAnalyzer.Progress progress) throws Exception {
        return analyzer.analyze(source, null, "fixture-only-not-a-key", "gpt-6-luna", cancel, progress);
    }
    private static void addReviewed(JSONObject unit) {
        Json.put(unit, "performance", SpeechPerformance.validate(unit, SpeechPerformanceTest.annotations(unit), true));
        Json.put(unit, "analyzed", true);
    }
    private static void noPerformance(JSONObject plan) {
        for (int i = 0; i < Json.arr(plan, "units").length(); i++) yes(!Json.arr(plan, "units").optJSONObject(i).has("performance"), "draft performance was not committed");
    }
    public static void main(String[] args) throws Exception {
        reviewedActingAndReuse();
        onlyReviewedCastIsCommitted();
        cancellationsAreAtomic();
        failedReviewRetainsCompletedBatches();
        malformedPartsAreAtomic();
        chronologicalContextIncludesGaps();
        concurrentManualEditsWin();
        accountTokensAndUserModel();
        detailedWorkHasNoChapterCap();
        System.out.println("SpeechDetailedAnalysisTest: PASS (" + checks + " two-stage, cancellation, identity, manual edit, context and reuse checks; no AI accuracy benchmark)");
    }
    private static void reviewedActingAndReuse() throws Exception {
        JSONObject source = SpeechPlan.create("two-parts", Json.obj("url", "local://two-parts", "paragraphs",
                new JSONArray().put(Json.obj("text", "「待って、もう迷わない。」"))), null);
        JSONObject old = Json.arr(source, "units").optJSONObject(0); Json.put(old, "analyzed", true);
        Json.put(old, "emotion", "calm"); Json.put(source, "analyzedUnits", 1);
        String unchanged = source.toString(); final int[] calls = {0}; final List<String> phases = new ArrayList<>();
        SpeechAnalyzer analyzer = detailed((request, token, cancel) -> {
            int call = ++calls[0]; JSONObject input = data(request);
            eq(request.optString("model"), "gpt-6-luna"); eq(request.optJSONObject("reasoning").optString("effort"), "medium");
            yes(request.optString("instructions").contains("relative multipliers applied on top of the pinned character rate and pitch baseline"), "numeric controls do not duplicate a pinned baseline");
            yes(request.optString("instructions").contains("An emotion label alone does not change acting when the voice has no matching native style"), "prompt describes actual engine capability");
            eq(input.optString("analysisStage"), call == 1 ? "planning" : "reviewing");
            eq(input.has("candidateDraft"), call == 2);
            JSONArray parts = Json.arr(Json.arr(input, "targetUnits").optJSONObject(0), "parts");
            eq(parts.length(), 2); eq(parts.optJSONObject(0).optString("text"), "「待って、");
            JSONObject schema = request.optJSONObject("text").optJSONObject("format").optJSONObject("schema")
                    .optJSONObject("properties").optJSONObject("assignments").optJSONObject("items").optJSONObject("properties")
                    .optJSONObject("parts").optJSONObject("items");
            eq(schema.optBoolean("additionalProperties", true), false);
            JSONObject properties = schema.optJSONObject("properties");
            yes(properties.has("confidence") && !properties.has("text") && !properties.has("start") && !properties.has("end"), "model returns acting IDs without source ownership");
            JSONObject m = metadata(request), a = Json.arr(m, "assignments").optJSONObject(0);
            Json.put(Json.arr(a, "parts").optJSONObject(0), "emotion", call == 1 ? "fear" : "tension");
            Json.put(Json.arr(a, "parts").optJSONObject(1), "emotion", call == 1 ? "joy" : "determination");
            Json.put(Json.arr(a, "parts").optJSONObject(1), "confidence", .91);
            return envelope(m);
        });
        yes(analyzer.needsAnalysis(old), "legacy analyzed=true still requires detailed acting");
        JSONObject result = analyze(analyzer, source, () -> false, (p, c) -> {
            String phase = p.optString("analysisPhase"); phases.add(phase);
            if (!phase.isEmpty()) {
                eq(Json.arr(p, "units").optJSONObject(0).optString("emotion"), "calm");
                noPerformance(p); eq(p.optInt("analyzedUnits"), 0);
            }
        });
        JSONObject completed = result.optJSONObject("plan"), unit = Json.arr(completed, "units").optJSONObject(0);
        eq(calls[0], 2); eq(completed.optString("analysisDetail"), "detailed"); eq(completed.optString("analysisPhase"), "");
        eq(completed.optString("analysisStatus"), "complete"); eq(completed.optInt("analyzedUnits"), 1);
        yes(phases.contains("planning") && phases.contains("reviewing"), "both reviewable phases are reported");
        yes(SpeechPerformance.valid(unit), "only reviewed complete acting is saved");
        JSONArray parts = unit.optJSONObject("performance").optJSONArray("parts");
        eq(parts.optJSONObject(0).optString("emotion"), "tension"); eq(parts.optJSONObject(1).optString("emotion"), "determination");
        eq(parts.optJSONObject(1).optDouble("confidence"), .91);
        eq(unit.optString("speakerId"), "unknown"); eq(unit.optDouble("confidence"), .4);
        eq(unit.optString("emotion"), "anger"); eq(unit.optDouble("intensity"), .8);
        eq(unit.optDouble("rate"), 1.1); // Uncertain identity cannot flatten independently supported acting.
        for (String field : new String[]{"unitId", "text", "spokenText", "paragraph", "start", "end", "index"}) eq(unit.opt(field), old.opt(field));
        eq(source.toString(), unchanged); eq(completed.optLong("inputTokens"), 22L); eq(completed.optLong("outputTokens"), 14L);
        yes(!analyzer.needsAnalysis(unit), "reviewed unit can be reused");
        analyze(analyzer, completed, () -> false, null); eq(calls[0], 2);
        JSONObject standard = analyze(analyzer.withDetailedActing(false), completed, () -> false, null).optJSONObject("plan");
        eq(calls[0], 2); yes(SpeechPerformance.valid(Json.arr(standard, "units").optJSONObject(0)), "switching modes alone cannot erase saved performance");
    }
    private static void onlyReviewedCastIsCommitted() throws Exception {
        final int[] calls = {0};
        JSONObject result = analyze(detailed((request, token, cancel) -> {
            boolean review = ++calls[0] == 2; JSONObject m = metadata(request);
            String id = review ? "c_reviewed" : "c_draft";
            if (review) {
                yes(find(Json.arr(data(request), "knownCast"), "c_draft") == null, "draft identity never becomes known truth");
                yes(find(Json.arr(data(request).optJSONObject("candidateDraft"), "cast"), "c_draft") != null, "review receives the separate candidate");
            }
            Json.arr(m, "cast").put(person(id, review ? "確認した人物" : "誤った人物")); assign(m, id); return envelope(m);
        }), plan(1), () -> false, (p, c) -> yes(find(c, "c_draft") == null, "draft cast never reaches a checkpoint"));
        eq(calls[0], 2); yes(find(result.optJSONArray("cast"), "c_reviewed") != null, "reviewed identity committed");
        yes(find(result.optJSONArray("cast"), "c_draft") == null, "rejected proposed cast is absent");
        eq(Json.arr(result.optJSONObject("plan"), "units").optJSONObject(0).optString("speakerId"), "c_reviewed");
    }
    private static void cancellationsAreAtomic() throws Exception {
        for (boolean duringReview : new boolean[]{false, true}) {
            AtomicBoolean cancel = new AtomicBoolean(false); final int[] calls = {0};
            JSONObject paused = analyze(detailed((request, key, c) -> {
                if (++calls[0] == 2) cancel.set(true);
                JSONObject m = metadata(request); Json.arr(m, "cast").put(person("c_uncommitted", "未確定人物")); assign(m, "c_uncommitted"); return envelope(m);
            }), plan(2), cancel::get, (p, c) -> {
                if (!duringReview && "reviewing".equals(p.optString("analysisPhase"))) cancel.set(true);
                yes(find(c, "c_uncommitted") == null, "cancel never adopts proposed cast");
            }).optJSONObject("plan");
            eq(calls[0], duringReview ? 2 : 1); eq(paused.optString("analysisStatus"), "paused");
            eq(paused.optString("analysisPhase"), ""); eq(paused.optInt("analyzedUnits"), 0); noPerformance(paused);
        }
    }
    private static void failedReviewRetainsCompletedBatches() throws Exception {
        final int[] calls = {0}; final JSONObject[] saved = {null}; final JSONArray[] savedCast = {null};
        SpeechAnalyzer analyzer = detailed((request, key, cancel) -> {
            JSONObject m = metadata(request);
            if (++calls[0] == 4) Json.arr(m, "assignments").remove(0); // A complete envelope containing incomplete review metadata.
            return envelope(m);
        });
        try { analyze(analyzer, plan(9), () -> false, (p, c) -> { saved[0] = Json.copy(p); savedCast[0] = c; }); throw new AssertionError("partial review accepted"); }
        catch (IOException expected) { checks++; }
        eq(calls[0], 4); eq(saved[0].optString("analysisStatus"), "error"); eq(saved[0].optString("analysisPhase"), "");
        eq(saved[0].optInt("analyzedUnits"), 8);
        for (int i = 0; i < 8; i++) yes(SpeechPerformance.valid(Json.arr(saved[0], "units").optJSONObject(i)), "completed earlier batch retained");
        yes(!Json.arr(saved[0], "units").optJSONObject(8).has("performance"), "partial later batch not adopted");
        JSONObject recovered = analyzer.analyze(saved[0], savedCast[0], "fixture-only-not-a-key", "gpt-6-luna", () -> false, null).optJSONObject("plan");
        eq(calls[0], 6); eq(recovered.optInt("analyzedUnits"), 9); eq(recovered.optString("analysisStatus"), "complete");
    }
    private static void malformedPartsAreAtomic() throws Exception {
        for (String failure : new String[]{"missing", "foreign", "offset", "text", "field", "incomplete"}) {
            final int[] calls = {0}; final JSONObject[] saved = {null};
            try {
                analyze(detailed((request, key, cancel) -> {
                    JSONObject m = metadata(request), response;
                    if (++calls[0] == 2) {
                        JSONArray parts = Json.arr(Json.arr(m, "assignments").optJSONObject(0), "parts"); JSONObject p = parts.optJSONObject(0);
                        if ("missing".equals(failure)) parts.remove(0);
                        if ("foreign".equals(failure)) Json.put(p, "partId", "p_foreign");
                        if ("offset".equals(failure)) { Json.put(p, "start", 0); Json.put(p, "end", 1); }
                        if ("text".equals(failure)) Json.put(p, "text", "置き換え本文");
                        if ("field".equals(failure)) p.remove("confidence");
                    }
                    response = envelope(m);
                    if (calls[0] == 2 && "incomplete".equals(failure)) Json.put(response, "status", "incomplete");
                    return response;
                }), plan(2), () -> false, (p, c) -> saved[0] = Json.copy(p));
                throw new AssertionError("accepted invalid acting " + failure);
            } catch (IOException expected) { checks++; }
            eq(calls[0], 2); eq(saved[0].optString("analysisStatus"), "error"); eq(saved[0].optInt("analyzedUnits"), 0); noPerformance(saved[0]);
        }
    }
    private static void chronologicalContextIncludesGaps() throws Exception {
        JSONObject source = plan(20); JSONArray units = Json.arr(source, "units");
        for (int i = 0; i < units.length(); i++) addReviewed(units.optJSONObject(i));
        units.optJSONObject(9).remove("performance"); units.optJSONObject(11).remove("performance");
        Json.put(units.optJSONObject(10), "locked", true); final int[] calls = {0};
        JSONObject result = analyze(detailed((request, key, cancel) -> {
            calls[0]++; JSONObject input = data(request); JSONArray target = Json.arr(input, "targetUnits"), context = Json.arr(input, "adjacentContext");
            eq(target.length(), 2); eq(target.optJSONObject(0).optInt("index"), 9); eq(target.optJSONObject(1).optInt("index"), 11);
            Set<Integer> seen = new HashSet<>(); int previous = -1;
            for (int i = 0; i < context.length(); i++) {
                JSONObject c = context.optJSONObject(i); int index = c.optInt("index");
                yes(index > previous, "context remains chronological"); previous = index; seen.add(index);
                eq(c.optString("unitId"), units.optJSONObject(index).optString("unitId"));
            }
            for (int index = 1; index <= 19; index++) if (index != 9 && index != 11) yes(seen.contains(index), "context includes neighbors and skipped fixed index " + index);
            eq(context.length(), 17); yes(context.optJSONObject(8).optBoolean("locked"), "manual gap remains a context anchor");
            return envelope(metadata(request));
        }), source, () -> false, null).optJSONObject("plan");
        eq(calls[0], 2); eq(result.optInt("analyzedUnits"), 20);
        eq(Json.arr(result, "units").optJSONObject(10).toString(), units.optJSONObject(10).toString());
    }
    private static void replace(JSONArray target, JSONArray source) {
        while (target.length() > 0) target.remove(target.length() - 1);
        for (int i = 0; i < source.length(); i++) target.put(source.optJSONObject(i));
    }
    private static void concurrentManualEditsWin() throws Exception {
        final JSONObject[] live = {null}, fixedUnit = {null}; final int[] calls = {0};
        final JSONArray[] persistedCast = {CastRegistry.migrate(new JSONArray().put(person("c_live", "既存人物")), "metan")};
        SpeechAnalyzer analyzer = detailed((request, key, cancel) -> {
            int call = ++calls[0]; JSONObject m = metadata(request);
            if (call == 1) persistedCast[0] = CastRegistry.patchUser(persistedCast[0], new JSONArray().put(Json.obj("id", "c_live", "name", "手動の名前",
                    "offlineVoice", "zundamon", "style", "利用者の話し方", "rate", .9, "pitch", 1.02)), "metan");
            if (call == 2) {
                JSONObject known = find(Json.arr(data(request), "knownCast"), "c_live");
                eq(known.optString("name"), "手動の名前"); eq(known.optString("offlineVoice"), "zundamon");
                JSONObject unit = Json.arr(live[0], "units").optJSONObject(0); addReviewed(unit);
                Json.put(unit, "locked", true); Json.put(unit, "speakerId", "c_live"); Json.put(unit, "emotion", "calm");
                Json.put(unit.optJSONObject("performance").optJSONArray("parts").optJSONObject(0), "emotion", "relief");
                fixedUnit[0] = Json.copy(unit);
            }
            JSONObject attempt = person("c_live", "GPTの別名候補"); Json.put(attempt, "offlineVoice", "ryusei");
            Json.arr(m, "cast").put(attempt); assign(m, "c_live"); return envelope(m);
        });
        JSONObject result = analyzer.analyze(plan(2), persistedCast[0], "fixture-only-not-a-key", "gpt-6-luna", () -> false, (p, c) -> {
            live[0] = p; persistedCast[0] = CastRegistry.mergeAnalysis(persistedCast[0], c, "metan"); replace(c, persistedCast[0]);
        });
        eq(calls[0], 2); eq(Json.arr(result.optJSONObject("plan"), "units").optJSONObject(0).toString(), fixedUnit[0].toString());
        JSONObject fixed = find(result.optJSONArray("cast"), "c_live"); eq(fixed.optString("name"), "手動の名前");
        eq(fixed.optString("offlineVoice"), "zundamon"); eq(fixed.optString("style"), "利用者の話し方"); eq(fixed.optDouble("rate"), .9);
        eq(result.optJSONObject("plan").optInt("analyzedUnits"), 2);
    }
    private static void accountTokensAndUserModel() throws Exception {
        final int[] tokens = {0}, calls = {0};
        SpeechAnalyzer account = new SpeechAnalyzer((request, token, cancel) -> {
            eq(token, "account-fixture-token-" + (++calls[0])); eq(request.optString("model"), "fixture/family:custom");
            yes(!request.has("reasoning"), "unknown user model must not receive a guessed reasoning option");
            yes(request.optBoolean("stream") && !request.has("max_output_tokens"), "account transport contract preserved");
            return envelope(metadata(request));
        }, "metan", () -> "account-fixture-token-" + (++tokens[0])).withDetailedActing(true);
        JSONObject result = account.analyze(plan(1), null, "fixture/family:custom", () -> false, null);
        eq(tokens[0], 2); eq(calls[0], 2); yes(!result.toString().contains("account-fixture-token-"), "both temporary tokens excluded from persisted metadata");
        account.analyze(result.optJSONObject("plan"), result.optJSONArray("cast"), "fixture/family:custom", () -> false, null);
        eq(tokens[0], 2); eq(calls[0], 2);
        AtomicBoolean cancel = new AtomicBoolean(false); final int[] refreshes = {0}, sends = {0};
        SpeechAnalyzer cancelledRefresh = new SpeechAnalyzer((request, token, c) -> { sends[0]++; return envelope(metadata(request)); },
                "metan", () -> { if (++refreshes[0] == 2) cancel.set(true); return "cancel-refresh-fixture"; }).withDetailedActing(true);
        JSONObject paused = cancelledRefresh.analyze(plan(1), null, "fixture-account", cancel::get, null).optJSONObject("plan");
        eq(refreshes[0], 2); eq(sends[0], 1); eq(paused.optString("analysisStatus"), "paused"); noPerformance(paused);
        JSONObject switched = result.optJSONObject("plan"); final int[] switchedCalls = {0};
        JSONObject rebuilt = detailed((request, token, c) -> {
            switchedCalls[0]++; eq(request.optString("model"), "user-selected-other"); yes(!request.has("reasoning"), "chosen model is not replaced"); return envelope(metadata(request));
        }).analyze(switched, result.optJSONArray("cast"), "fixture-only-not-a-key", "user-selected-other", () -> false, null).optJSONObject("plan");
        eq(switchedCalls[0], 2); eq(rebuilt.optString("analysisModel"), "user-selected-other");
    }
    private static void detailedWorkHasNoChapterCap() throws Exception {
        final int[] calls = {0}; final Set<String> reviewed = new HashSet<>();
        JSONObject result = analyze(detailed((request, key, cancel) -> {
            calls[0]++; JSONObject input = data(request); JSONArray targets = Json.arr(input, "targetUnits");
            yes(targets.length() <= SpeechAnalyzer.DETAILED_BATCH_UNITS, "small request without chapter truncation");
            if ("reviewing".equals(input.optString("analysisStage")))
                for (int i = 0; i < targets.length(); i++) yes(reviewed.add(targets.optJSONObject(i).optString("unitId")), "each completed target reviewed once");
            return envelope(metadata(request));
        }), plan(103), () -> false, null).optJSONObject("plan");
        eq(calls[0], 26); eq(reviewed.size(), 103); eq(result.optInt("analyzedUnits"), 103);
    }
}
