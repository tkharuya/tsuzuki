package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stage callbacks follow real authorization, send, validation and adoption boundaries. */
public final class SpeechActivityTest {
    private static int checks;
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object actual, Object expected) { yes(expected.equals(actual), "expected " + expected + " got " + actual); }
    private static final class ActivityLog implements SpeechAnalyzer.Activity {
        final List<JSONObject> values = new ArrayList<>();
        @Override public void changed(JSONObject value) {
            eq(value.length(), 4); yes(value.opt("stage") instanceof String, "stage is a local label");
            for (String field : new String[]{"requestNumber", "targetUnits", "targetChapters"}) {
                yes(value.opt(field) instanceof Number, "activity count is numeric"); yes(value.optInt(field) >= 0, "activity count is nonnegative");
            }
            values.add(Json.copy(value));
        }
        JSONObject last() { return values.get(values.size() - 1); }
        long count(String stage) { return values.stream().filter(value -> stage.equals(value.optString("stage"))).count(); }
    }
    public static void main(String[] args) throws Exception {
        chapterAndReuse(); history(); failuresAndCancellation(); legacyModes();
        System.out.println("SpeechActivityTest: PASS (" + checks + " stage/order/count/cancellation/legacy checks; no inferred token progress or live provider calls)");
    }
    private static void chapterAndReuse() throws Exception {
        ActivityLog events = new ActivityLog(); final int[] sends = {0}, auths = {0};
        SpeechAnalyzer analyzer = new SpeechAnalyzer((request, token, cancel) -> {
            sends[0]++; eq(events.last().optString("stage"), "waiting"); eq(events.last().optInt("requestNumber"), sends[0]);
            return SpeechChapterAnalysisTest.envelope(SpeechMemoryIdentityTest.metadata(request));
        }, "zundamon", () -> { auths[0]++; eq(events.last().optString("stage"), "authorizing"); return "fixture-token"; })
                .withDetailedActing(true).withChapterWorkflow(true).withActivity(events);
        JSONObject plan = analyzer.analyze(SpeechChapterAnalysisTest.plan(3, 0), SpeechMemoryIdentityTest.known(), "gpt-6-luna", () -> false, null).optJSONObject("plan");
        eq(sends[0], 1); eq(auths[0], 1); eq(events.values.size(), 6);
        String[] expected = {"preparing", "preparing", "authorizing", "waiting", "validating", "saving"};
        for (int i = 0; i < expected.length; i++) { eq(events.values.get(i).optString("stage"), expected[i]); eq(events.values.get(i).optInt("requestNumber"), i == 0 ? 0 : 1); }
        eq(events.last().optInt("targetUnits"), 3); eq(events.last().optInt("targetChapters"), 1);
        events.values.clear(); analyzer.analyze(plan, SpeechMemoryIdentityTest.known(), "gpt-6-luna", () -> false, null);
        eq(sends[0], 1); eq(auths[0], 1); eq(events.values.size(), 2);
        eq(events.values.get(0).optString("stage"), "preparing"); eq(events.last().optString("stage"), "saving");
        eq(events.last().optInt("requestNumber"), 0); eq(events.last().optInt("targetUnits"), 0);
    }
    private static void history() throws Exception {
        ActivityLog events = new ActivityLog();
        SpeechAnalyzer analyzer = new SpeechAnalyzer((request, key, cancel) -> {
            eq(events.last().optString("stage"), "waiting");
            return SpeechChapterAnalysisTest.envelope(SpeechMemoryIdentityTest.metadata(request));
        }).withActivity(events);
        analyzer.summarizeHistory(new JSONArray().put(SpeechChapterAnalysisTest.plan(2, 0)).put(SpeechChapterAnalysisTest.plan(2, 1)),
                SpeechMemoryIdentityTest.known(), "fixture-key", "gpt-6-luna", () -> false);
        eq(events.count("waiting"), 1L); eq(events.count("validating"), 1L); eq(events.count("saving"), 1L);
        eq(events.last().optInt("targetUnits"), 0); eq(events.last().optInt("targetChapters"), 2); eq(events.last().optInt("requestNumber"), 1);
    }
    private static void failuresAndCancellation() throws Exception {
        ActivityLog invalid = new ActivityLog(); final JSONObject[] saved = {null};
        SpeechAnalyzer analyzer = new SpeechAnalyzer((request, key, cancel) -> {
            JSONObject metadata = SpeechMemoryIdentityTest.metadata(request);
            Json.put(Json.arr(metadata.optJSONObject("memoryDelta"), "facts").optJSONObject(0), "subjectId", "c_missing");
            return SpeechChapterAnalysisTest.envelope(metadata);
        }).withDetailedActing(true).withChapterWorkflow(true).withActivity(invalid);
        try { analyzer.analyze(SpeechChapterAnalysisTest.plan(2, 0), SpeechMemoryIdentityTest.known(), "fixture-key", "gpt-6-luna", () -> false, (p,c) -> saved[0] = Json.copy(p)); throw new AssertionError("bad memory accepted"); }
        catch (java.io.IOException expected) { yes(expected.getCause() instanceof StoryMemory.ValidationFailure, "memory failure is typed"); }
        eq(invalid.last().optString("stage"), "validating"); eq(invalid.count("saving"), 0L); eq(saved[0].optInt("analyzedUnits"), 0);
        ActivityLog cancelled = new ActivityLog(); AtomicBoolean stop = new AtomicBoolean(); final int[] calls = {0};
        SpeechAnalyzer paused = new SpeechAnalyzer((request, key, cancel) -> { calls[0]++; return SpeechChapterAnalysisTest.envelope(SpeechMemoryIdentityTest.metadata(request)); })
                .withDetailedActing(true).withChapterWorkflow(true).withActivity(value -> { cancelled.changed(value); if ("waiting".equals(value.optString("stage"))) stop.set(true); });
        JSONObject result = paused.analyze(SpeechChapterAnalysisTest.plan(2, 0), SpeechMemoryIdentityTest.known(), "fixture-key", "gpt-6-luna", stop::get, null).optJSONObject("plan");
        eq(calls[0], 0); eq(result.optString("analysisStatus"), "paused"); eq(result.optJSONObject("analysisMetrics").optInt("requestCount"), 0);
        eq(cancelled.count("validating"), 0L); eq(cancelled.count("saving"), 0L);
    }
    private static JSONObject legacyMetadata(JSONObject request) {
        JSONArray targets = Json.arr(SpeechChapterAnalysisTest.data(request), "targetUnits"), assignments = new JSONArray();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i), assignment = Json.obj("unitId", target.optString("unitId"), "speakerId", "unknown",
                    "kind", "dialogue", "emotion", "calm", "intensity", .3, "rate", 1, "pitch", 1, "confidence", .4);
            if (target.has("parts")) {
                JSONArray parts = new JSONArray(); for (int p = 0; p < Json.arr(target, "parts").length(); p++)
                    parts.put(SpeechPerformanceTest.acting(Json.arr(target, "parts").optJSONObject(p).optString("partId")));
                Json.put(assignment, "parts", parts);
            }
            assignments.put(assignment);
        }
        return Json.obj("cast", new JSONArray(), "assignments", assignments);
    }
    private static void legacyModes() throws Exception {
        for (boolean detailed : new boolean[]{false, true}) {
            ActivityLog events = new ActivityLog(); final int[] calls = {0};
            SpeechAnalyzer analyzer = new SpeechAnalyzer((request, key, cancel) -> {
                calls[0]++; eq(events.last().optString("stage"), "waiting"); eq(events.last().optInt("requestNumber"), calls[0]);
                return SpeechChapterAnalysisTest.envelope(legacyMetadata(request));
            }).withDetailedActing(detailed).withActivity(events);
            analyzer.analyze(SpeechChapterAnalysisTest.plan(2, 0), new JSONArray(), "fixture-key", "gpt-6-luna", () -> false, null);
            eq(calls[0], detailed ? 2 : 1); eq(events.count("validating"), detailed ? 2L : 1L); eq(events.count("saving"), 1L);
            eq(events.last().optString("stage"), "saving"); eq(events.last().optInt("requestNumber"), detailed ? 2 : 1);
        }
    }
}
