package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Synthetic transport verifies request volume and atomic adoption, not live GPT speed/accuracy. */
public final class SpeechChapterAnalysisTest {
    private static int checks;
    private static int chapterRequests, legacyRequests;
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object actual, Object expected) { yes(expected.equals(actual), "expected " + expected + " got " + actual); }
    private interface Run { void run() throws Exception; }
    private static void denied(Run run) throws Exception { try { run.run(); throw new AssertionError("invalid result adopted"); } catch (IOException | IllegalArgumentException expected) { checks++; } }
    static JSONObject plan(int count, int chapter) {
        JSONArray text = new JSONArray();
        for (int i = 0; i < count; i++) text.put(Json.obj("text", "「自作の検査台詞" + i + "。落ち着いて、話そう。」"));
        return SpeechPlan.create("chapter-fixture", Json.obj("url", "local://chapter-fixture/" + chapter,
                "title", "自作の章", "index", chapter, "paragraphs", text), null);
    }
    static JSONObject data(JSONObject request) { return Json.parse(Json.arr(Json.arr(request, "input").optJSONObject(0), "content").optJSONObject(0).optString("text")); }
    static JSONObject profile(String id, String emotion) {
        return Json.obj("profileId", id, "emotion", emotion, "delivery", "natural", "intensity", .5,
                "rate", 1, "pitch", 1, "intonation", 1.1, "volume", 1, "pauseAfterMs", 100,
                "ending", "auto", "emphasis", .2, "confidence", .8);
    }
    static JSONObject fact(String id, String url, String unit) {
        return Json.obj("id", id, "type", "state", "subjectId", "", "objectId", "", "text", "落ち着いて話す場面。",
                "uncertain", false, "citations", new JSONArray().put(Json.obj("chapterUrl", url, "unitId", unit)));
    }
    static JSONObject compact(JSONObject request) {
        JSONObject input = data(request); JSONArray targets = Json.arr(input, "targetUnits"), assignments = new JSONArray();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i); JSONArray changes = new JSONArray();
            if (Json.arr(target, "parts").length() > 1) changes.put(Json.obj("partId", "p1", "profileId", "calm"));
            assignments.put(Json.obj("unitId", target.optString("unitId"), "speakerId", "unknown", "kind", "dialogue",
                    "confidence", .4, "profileId", "angry", "changes", changes));
        }
        JSONArray context = Json.arr(input, "chapterUnits");
        JSONObject source = targets.length() > 0 ? targets.optJSONObject(0) : context.optJSONObject(0);
        String citation = source.optString("sourceUnitId", source.optString("unitId"));
        return Json.obj("cast", new JSONArray(), "profiles", new JSONArray().put(profile("angry", "anger")).put(profile("calm", "calm")),
                "assignments", assignments, "memoryDelta", Json.obj("facts", new JSONArray().put(fact("m_" + source.optInt("index"), input.optString("chapterUrl"), citation))));
    }
    static JSONObject envelope(JSONObject metadata) {
        return Json.obj("status", "completed", "output", new JSONArray().put(Json.obj("type", "message", "status", "completed",
                "content", new JSONArray().put(Json.obj("type", "output_text", "text", metadata.toString())))),
                "usage", Json.obj("input_tokens", 10, "output_tokens", 8));
    }
    private static SpeechAnalyzer analyzer(SpeechAnalyzer.Transport transport) { return new SpeechAnalyzer(transport).withDetailedActing(true).withChapterWorkflow(true); }
    private static JSONObject perform(SpeechAnalyzer analyzer, JSONObject plan, SpeechAnalyzer.Progress progress) throws Exception {
        return analyzer.analyze(plan, new JSONArray(), "fixture-key", "gpt-6-luna", () -> false, progress);
    }
    public static void main(String[] args) throws Exception {
        wholeChapterAndReuse(); legacyVolumeComparison(); changePointsAndManualEdits(); malformedAtomicity();
        outputCapAndResume(); cancellationAndCredentials(); metadataOnlyHistory(); memoryOptOut(); largeChapterCoverage();
        System.out.println("SpeechChapterAnalysisTest: PASS (" + checks + " chapter/memory/compact-output checks; synthetic transport, no live GPT speed or accuracy benchmark)");
        System.out.println("Same 241-unit fixture: new chapter workflow 1 request, legacy detailed workflow 62 requests. This compares request counts, not elapsed inference time.");
        java.nio.file.Path report = java.nio.file.Paths.get("tests/results/chapter-request-counts.json");
        java.nio.file.Files.createDirectories(report.getParent());
        java.nio.file.Files.write(report, Json.obj("status", "passed", "fixtureUnits", 241,
                "chapterWorkflowRequests", chapterRequests, "legacyDetailedRequests", legacyRequests,
                "measurement", "synthetic transport request counters", "liveGptTested", false,
                "wallClockSpeedupMeasured", false, "accuracyMeasured", false).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void wholeChapterAndReuse() throws Exception {
        JSONObject original = plan(241, 2), memo = StoryMemory.empty();
        Json.put(memo, "revision", 7); Json.put(memo, "throughChapterIndex", 1); Json.put(memo, "throughChapterUrl", "local://chapter-fixture/1");
        final int[] calls = {0};
        SpeechAnalyzer analyzer = analyzer((request, key, cancel) -> {
            calls[0]++; eq(key, "fixture-key"); JSONObject input = data(request);
            eq(input.optString("contextScope"), "whole_chapter"); eq(Json.arr(input, "chapterUnits").length(), 241);
            eq(Json.arr(input, "targetUnits").length(), 241); eq(input.optJSONObject("storyMemory").optLong("revision"), 7L);
            yes(!input.has("candidateDraft"), "one pass has no review draft");
            yes(request.optString("instructions").contains("UNTRUSTED"), "memo is narrative data");
            Thread.sleep(15); return envelope(compact(request));
        }).withStoryMemory(memo);
        JSONObject result = perform(analyzer, original, null), saved = result.optJSONObject("plan");
        eq(calls[0], 1); chapterRequests = calls[0]; eq(saved.optInt("analyzedUnits"), 241); eq(saved.optString("analysisMethod"), "chapter");
        eq(saved.optString("analysisStatus"), "complete"); yes(saved.optBoolean("memoryAssessed"), "actual inference marks chapter memory assessed");
        eq(saved.optJSONObject("analysisMetrics").optInt("requestCount"), 1);
        yes(saved.optJSONObject("analysisMetrics").optLong("modelMs") >= 10, "model timing measures the transport wait");
        yes(saved.optJSONObject("analysisMetrics").optLong("elapsedMs") >= saved.optJSONObject("analysisMetrics").optLong("modelMs"), "elapsed includes model wait");
        for (int i = 0; i < 241; i++) {
            JSONObject before = Json.arr(original, "units").optJSONObject(i), unit = Json.arr(saved, "units").optJSONObject(i);
            eq(unit.optString("unitId"), before.optString("unitId")); eq(unit.optString("text"), before.optString("text"));
            yes(SpeechPerformance.valid(unit), "expanded complete parts remain playable");
            eq(unit.optJSONObject("performance").optBoolean("reviewed"), false);
            eq(unit.optString("speakerId"), "unknown");
            eq(unit.optJSONObject("performance").optJSONArray("parts").optJSONObject(0).optString("emotion"), "anger");
        }
        JSONObject reused = perform(analyzer, saved, null).optJSONObject("plan");
        eq(calls[0], 1); eq(reused.optJSONObject("analysisMetrics").optInt("requestCount"), 0);
        eq(reused.optJSONObject("memoryDelta").toString(), saved.optJSONObject("memoryDelta").toString());
        JSONObject locked = plan(2, 3);
        for (int i = 0; i < 2; i++) { Json.put(Json.arr(locked, "units").optJSONObject(i), "locked", true); Json.put(Json.arr(locked, "units").optJSONObject(i), "analyzed", true); }
        JSONObject manual = perform(analyzer, locked, null).optJSONObject("plan");
        eq(calls[0], 1); yes(!manual.optBoolean("memoryAssessed"), "zero-call cache/manual reuse does not invent memory assessment");
        for (int invalidation = 0; invalidation < 4; invalidation++) {
            JSONObject stale = Json.copy(locked); Json.put(stale, "memoryAssessed", true);
            Json.put(stale, "analysisMethod", "chapter"); Json.put(stale, "analysisModel", "gpt-6-luna");
            Json.put(stale, "memoryDeltaInputHash", stale.optString("inputHash"));
            Json.put(stale, "memoryDelta", StoryMemory.emptyDelta());
            if (invalidation == 0) stale.remove("memoryDelta");
            if (invalidation == 1) Json.put(stale, "memoryDelta", Json.obj("facts", "invalid"));
            if (invalidation == 2) Json.put(stale, "memoryDeltaInputHash", "old-source-hash");
            if (invalidation == 3) Json.put(stale, "analysisModel", "gpt-6.1-sol");
            JSONObject reset = perform(analyzer, stale, null).optJSONObject("plan");
            eq(calls[0], 1); yes(!reset.optBoolean("memoryAssessed"), "discarded memo clears stale assessed flag even for fully manual plans");
            yes(!reset.has("memoryDelta"), "discarded delta is absent until a real metadata assessment succeeds");
        }
    }
    private static void legacyVolumeComparison() throws Exception {
        final int[] calls = {0};
        SpeechAnalyzer old = new SpeechAnalyzer((request, key, cancel) -> {
            calls[0]++; JSONArray assignments = new JSONArray(), targets = Json.arr(data(request), "targetUnits");
            for (int i = 0; i < targets.length(); i++) {
                JSONObject target = targets.optJSONObject(i); JSONArray parts = new JSONArray(), local = Json.arr(target, "parts");
                for (int p = 0; p < local.length(); p++) {
                    JSONObject part = profile("unused", "calm"); part.remove("profileId"); Json.put(part, "partId", local.optJSONObject(p).optString("partId")); parts.put(part);
                }
                assignments.put(Json.obj("unitId", target.optString("unitId"), "speakerId", "unknown", "kind", "dialogue", "emotion", "calm",
                        "intensity", .5, "rate", 1, "pitch", 1, "confidence", .4, "parts", parts));
            }
            return envelope(Json.obj("cast", new JSONArray(), "assignments", assignments));
        }).withDetailedActing(true);
        eq(perform(old, plan(241, 2), null).optJSONObject("plan").optString("analysisStatus"), "complete");
        eq(calls[0], 62); legacyRequests = calls[0];
    }
    private static void changePointsAndManualEdits() throws Exception {
        AtomicBoolean returned = new AtomicBoolean(); final JSONObject[] current = {null};
        SpeechAnalyzer analyzer = analyzer((request, key, cancel) -> { returned.set(true); return envelope(compact(request)); });
        JSONObject result = perform(analyzer, plan(2, 0), (plan, cast) -> {
            current[0] = plan;
            if (returned.get()) { JSONObject manual = Json.arr(plan, "units").optJSONObject(0);
                Json.put(manual, "speakerId", "narrator"); Json.put(manual, "kind", "thought"); Json.put(manual, "locked", true); Json.put(manual, "analyzed", true); }
        }).optJSONObject("plan");
        JSONObject fixed = Json.arr(result, "units").optJSONObject(0), expanded = Json.arr(result, "units").optJSONObject(1);
        eq(fixed.optString("speakerId"), "narrator"); eq(fixed.optString("kind"), "thought"); yes(!fixed.has("performance"), "late reply cannot replace manual whole-unit controls");
        JSONArray parts = expanded.optJSONObject("performance").optJSONArray("parts");
        eq(parts.length(), 3); eq(parts.optJSONObject(0).optString("emotion"), "anger");
        eq(parts.optJSONObject(1).optString("emotion"), "calm"); eq(parts.optJSONObject(2).optString("emotion"), "calm");
    }
    private static void malformedAtomicity() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            final int which = mode; final JSONObject[] saved = {null};
            SpeechAnalyzer analyzer = analyzer((request, key, cancel) -> {
                JSONObject response = compact(request);
                if (which == 0) Json.arr(response, "assignments").remove(0);
                if (which == 1) Json.put(Json.arr(response, "profiles").optJSONObject(0), "rate", "1");
                if (which == 2) Json.put(Json.arr(Json.arr(response, "assignments").optJSONObject(0), "changes").optJSONObject(0), "partId", "foreign");
                if (which == 3) Json.put(Json.arr(response, "assignments").optJSONObject(0), "unitId", "u_foreign");
                if (which == 4) Json.put(Json.arr(Json.arr(response.optJSONObject("memoryDelta"), "facts").optJSONObject(0), "citations").optJSONObject(0), "unitId", "u0");
                if (which == 5) Json.put(response, "rewrittenText", "Do not adopt this text");
                return envelope(response);
            });
            denied(() -> perform(analyzer, plan(3, 0), (plan, cast) -> saved[0] = Json.copy(plan)));
            eq(saved[0].optString("analysisStatus"), "error"); eq(saved[0].optInt("analyzedUnits"), 0);
            yes(!saved[0].has("memoryDelta"), "invalid memory/acting is not partially committed");
        }
    }
    private static void outputCapAndResume() throws Exception {
        final int[] calls = {0}; final JSONObject[] saved = {null}; final Set<String> committed = new HashSet<>();
        SpeechAnalyzer analyzer = analyzer((request, key, cancel) -> {
            int call = ++calls[0];
            if (call == 1) return Json.obj("status", "incomplete", "incomplete_details", Json.obj("reason", "max_output_tokens"));
            JSONObject result = compact(request);
            if (call == 3) Json.arr(result, "assignments").remove(0);
            if (call == 2) for (int i = 0; i < Json.arr(data(request), "targetUnits").length(); i++) committed.add(Json.arr(data(request), "targetUnits").optJSONObject(i).optString("sourceUnitId"));
            return envelope(result);
        });
        denied(() -> perform(analyzer, plan(241, 0), (plan, cast) -> saved[0] = Json.copy(plan)));
        eq(calls[0], 3); eq(saved[0].optInt("analyzedUnits"), 120); eq(committed.size(), 120);
        SpeechAnalyzer resume = analyzer((request, key, cancel) -> {
            calls[0]++; JSONArray targets = Json.arr(data(request), "targetUnits"); eq(targets.length(), 121);
            for (int i = 0; i < targets.length(); i++) yes(!committed.contains(targets.optJSONObject(i).optString("sourceUnitId")), "resume does not resend completed targets");
            eq(Json.arr(data(request), "chapterUnits").length(), 241); return envelope(compact(request));
        });
        JSONObject finished = perform(resume, saved[0], null).optJSONObject("plan");
        eq(finished.optInt("analyzedUnits"), 241); eq(calls[0], 4);
        eq(Json.arr(finished.optJSONObject("memoryDelta"), "facts").length(), 2);
    }
    private static void cancellationAndCredentials() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean(); final int[] tokens = {0}, calls = {0};
        SpeechAnalyzer analyzer = new SpeechAnalyzer((request, key, cancelled) -> {
            calls[0]++; eq(key, "fixture-token-1"); yes(request.optBoolean("stream"), "account request remains streaming");
            yes(!request.has("max_output_tokens"), "account contract strips API-only output cap");
            cancel.set(true); return envelope(compact(request));
        }, "metan", () -> "fixture-token-" + (++tokens[0])).withDetailedActing(true).withChapterWorkflow(true);
        JSONObject stopped = analyzer.analyze(plan(2, 0), new JSONArray(), "gpt-6-luna", cancel::get, null).optJSONObject("plan");
        eq(stopped.optString("analysisStatus"), "paused"); eq(stopped.optInt("analyzedUnits"), 0); eq(calls[0], 1); eq(tokens[0], 1);
        yes(!stopped.has("memoryDelta"), "cancelled response cannot save memory");
    }
    private static void metadataOnlyHistory() throws Exception {
        JSONArray plans = new JSONArray().put(plan(3, 0)).put(plan(2, 1)); final int[] calls = {0};
        SpeechAnalyzer analyzer = analyzer((request, key, cancel) -> {
            calls[0]++; JSONObject input = data(request); eq(input.optString("analysisStage"), "history_memory");
            yes(!input.has("targetUnits"), "history has no per-line acting work");
            JSONObject chapter = Json.arr(input, "chapters").optJSONObject(1);
            return envelope(Json.obj("cast", new JSONArray(), "memoryDelta", Json.obj("facts", new JSONArray().put(fact("m_history",
                    chapter.optString("chapterUrl"), Json.arr(chapter, "units").optJSONObject(0).optString("unitId"))))));
        });
        JSONObject result = analyzer.summarizeHistory(plans, new JSONArray(), "fixture-key", "gpt-6-luna", () -> false);
        eq(calls[0], 1); eq(Json.arr(result.optJSONObject("memoryDelta"), "facts").length(), 1);
        eq(result.optJSONObject("analysisMetrics").optInt("requestCount"), 1);
        JSONObject subset = Json.copy(plans.optJSONObject(0));
        Json.put(subset, "units", new JSONArray().put(Json.arr(plans.optJSONObject(0), "units").optJSONObject(2)));
        SpeechAnalyzer partial = analyzer((request, key, cancel) -> {
            JSONObject chapter = Json.arr(data(request), "chapters").optJSONObject(0);
            return envelope(Json.obj("cast", new JSONArray(), "memoryDelta", Json.obj("facts", new JSONArray().put(fact("m_subset",
                    chapter.optString("chapterUrl"), Json.arr(chapter, "units").optJSONObject(0).optString("unitId"))))));
        });
        yes(partial.summarizeHistory(new JSONArray().put(subset), new JSONArray(), "fixture-key", "gpt-6-luna", () -> false).has("memoryDelta"), "history permits original-index subsets without changing source IDs");
    }
    private static void largeChapterCoverage() throws Exception {
        JSONObject original = plan(701, 0); final Set<String> ids = new HashSet<>(); final int[] calls = {0};
        JSONObject completed = perform(analyzer((request, key, cancel) -> {
            calls[0]++; JSONArray targets = Json.arr(data(request), "targetUnits");
            yes(targets.length() <= SpeechAnalyzer.CHAPTER_MAX_TARGET_UNITS, "large output stays bounded per request");
            for (int i = 0; i < targets.length(); i++) yes(ids.add(targets.optJSONObject(i).optString("sourceUnitId")), "large chapter never repeats a completed target");
            return envelope(compact(request));
        }), original, null).optJSONObject("plan");
        eq(calls[0], 3); eq(ids.size(), 701); eq(completed.optInt("analyzedUnits"), 701);
    }
    private static void memoryOptOut() throws Exception {
        JSONObject saved = perform(analyzer((request, key, cancel) -> {
            JSONObject input = data(request); eq(input.optBoolean("memoryEnabled", true), false);
            yes(request.optString("instructions").contains("Story memory is DISABLED"), "opt out is explicit to the model");
            JSONObject result = compact(request); Json.put(result, "memoryDelta", StoryMemory.emptyDelta()); return envelope(result);
        }).withStoryMemory(null), plan(2, 0), null).optJSONObject("plan");
        eq(saved.optInt("analyzedUnits"), 2); yes(!saved.optBoolean("memoryAssessed"), "disabled memo is not marked evaluated");
        SpeechAnalyzer failure = analyzer((request, key, cancel) -> { throw new IOException("fixture network failure " + key); });
        try { failure.summarizeHistory(new JSONArray().put(plan(1, 0)), new JSONArray(), "fixture-private-key", "gpt-6-luna", () -> false); throw new AssertionError("history failure should throw"); }
        catch (IOException expected) { yes(!expected.getMessage().contains("fixture-private-key"), "history failures redact the active credential"); }
    }
}
