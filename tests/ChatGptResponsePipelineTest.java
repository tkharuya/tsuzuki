package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Real SSE reconstruction -> analyzer adoption -> in-memory checkpoint -> resume. */
public final class ChatGptResponsePipelineTest {
    private static final String TOKEN = "FIXTURE_PIPELINE_TOKEN_NOT_A_CREDENTIAL";
    private static final String PRIVATE = "FIXTURE_PRIVATE_QUOTA_BODY";
    private static final String FIXED = "c_fixed";
    private static int checks;

    private static void eq(Object actual, Object expected) {
        checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static void yes(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static JSONObject plan() {
        JSONArray paragraphs = new JSONArray();
        for (int i = 0; i < 70; i++) paragraphs.put(Json.obj("text", "「自作の解析試験。区間" + i + "です。」"));
        return SpeechPlan.create("fixture-pipeline", Json.obj("url", "local://pipeline/chapter",
                "title", "自作の回帰試験", "paragraphs", paragraphs), null);
    }
    private static JSONArray fixedCast() {
        return CastRegistry.patchUser(null, new JSONArray().put(Json.obj("id", FIXED, "name", "固定した架空人物",
                "aliases", new JSONArray(), "gender", "neutral", "tier", "main", "offlineVoice", "zundamon",
                "style", "利用者が指定した話し方", "rate", 1.1, "pitch", .9)), "metan");
    }
    private static JSONObject speaker(JSONArray cast, String id) {
        for (int i = 0; i < cast.length(); i++) if (id.equals(cast.optJSONObject(i).optString("id"))) return cast.optJSONObject(i);
        return null;
    }
    private static String event(String type, JSONObject fields) {
        return "event: " + type + "\ndata: " + Json.merge(Json.obj("type", type), fields) + "\n\n";
    }
    private static JSONObject proposal(String id, String name) {
        return Json.obj("id", id, "name", name, "aliases", new JSONArray(), "gender", "neutral", "tier", "main",
                "offlineVoice", "miko", "style", "解析が提案した別の話し方", "rate", .8, "pitch", 1.2);
    }

    private static final class Snapshots implements SpeechAnalyzer.Progress {
        JSONObject plan, firstBatch;
        JSONArray cast, firstCast;
        @Override public void onProgress(JSONObject current, JSONArray currentCast) throws Exception {
            plan = Json.copy(current); cast = new JSONArray(currentCast.toString());
            if (firstBatch == null && current.optInt("analyzedUnits") == 40) {
                firstBatch = Json.copy(current); firstCast = new JSONArray(currentCast.toString());
            }
        }
    }

    /** Only the external transport is replaced; its byte stream uses the production SSE reader. */
    private static final class FixtureTransport implements SpeechAnalyzer.Transport {
        final JSONObject original;
        final JSONArray fixed;
        final Snapshots saved;
        final boolean failSecond;
        int nextIndex, calls, authorizations;
        FixtureTransport(JSONObject original, JSONArray fixed, Snapshots saved, int nextIndex, boolean failSecond) {
            this.original = original; this.fixed = fixed; this.saved = saved; this.nextIndex = nextIndex; this.failSecond = failSecond;
        }
        @Override public JSONObject send(JSONObject request, String credential, SpeechAnalyzer.Cancel cancel) throws Exception {
            calls++; eq(credential, TOKEN); yes(request.optBoolean("stream"), "account request streams");
            yes(!request.optBoolean("store") && !request.has("max_output_tokens"), "account request retains the supported contract");
            JSONObject data = new JSONObject(request.getJSONArray("input").getJSONObject(0)
                    .getJSONArray("content").getJSONObject(0).getString("text"));
            JSONObject known = speaker(data.getJSONArray("knownCast"), FIXED), expected = speaker(fixed, FIXED);
            for (String key : new String[]{"name", "offlineVoice", "style", "voiceLocked", "profileLocked"})
                eq(known.opt(key), expected.opt(key));
            eq(known.optDouble("rate"), expected.optDouble("rate")); eq(known.optDouble("pitch"), expected.optDouble("pitch"));
            if (nextIndex == 40) {
                eq(saved.plan.optInt("analyzedUnits"), 40); eq(saved.plan.optInt("batchIndex"), 1);
                eq(saved.cast.toString(), fixed.toString());
            }
            JSONArray targets = data.getJSONArray("targetUnits"), originalUnits = original.getJSONArray("units");
            eq(targets.length(), Math.min(SpeechAnalyzer.BATCH_UNITS, 70 - nextIndex));
            int start = nextIndex;
            for (int i = 0; i < targets.length(); i++, nextIndex++) {
                eq(targets.getJSONObject(i).getString("unitId"), originalUnits.getJSONObject(nextIndex).getString("unitId"));
                eq(targets.getJSONObject(i).getString("text"), originalUnits.getJSONObject(nextIndex).getString("text"));
            }
            boolean fail = failSecond && calls == 2;
            JSONArray assignments = new JSONArray(), cast = new JSONArray().put(proposal(FIXED, "変更してはならない候補名"));
            if (fail) cast.put(proposal("c_unadopted", "失敗応答だけに含まれる架空人物"));
            for (int i = 0; i < targets.length(); i++) assignments.put(Json.obj("unitId", targets.getJSONObject(i).getString("unitId"),
                    "speakerId", fail ? "c_unadopted" : FIXED, "kind", "dialogue", "emotion", start == 0 ? "calm" : "joy",
                    "intensity", .4, "rate", 1.0, "pitch", 1.0, "confidence", .95));
            JSONObject metadata = Json.obj("cast", cast, "assignments", assignments);
            String id = "resp_pipeline_" + start;
            JSONObject item = Json.obj("id", "msg_pipeline_" + start, "type", "message", "role", "assistant",
                    "phase", "final_answer", "status", "completed", "content",
                    new JSONArray().put(Json.obj("type", "output_text", "text", metadata.toString())));
            String stream = event("response.created", Json.obj("response", Json.obj("id", id, "status", "in_progress", "output", new JSONArray())))
                    + event("response.output_item.done", Json.obj("output_index", 0, "item", item));
            if (fail) {
                stream += event("response.failed", Json.obj("response", Json.obj("id", id, "status", "failed",
                        "usage", Json.obj("input_tokens", 999, "output_tokens", 999),
                        "error", Json.obj("code", "subscription_sharing_usage_limit_exceeded", "message", PRIVATE + TOKEN))));
            } else {
                JSONObject completed = Json.obj("id", id, "status", "completed", "usage", Json.obj("input_tokens", 11, "output_tokens", 7));
                // First batch omits output; the second/resumed batch explicitly sets it to null.
                if (start > 0) Json.put(completed, "output", JSONObject.NULL);
                stream += event("response.completed", Json.obj("response", completed));
            }
            return ChatGptResponses.readCompleted(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), cancel, "req_pipeline_" + start);
        }
    }
    private static JSONObject analyze(FixtureTransport transport, JSONObject plan, JSONArray cast) throws Exception {
        return new SpeechAnalyzer(transport, "metan", () -> { transport.authorizations++; return TOKEN; })
                .analyze(plan, cast, "fixture-account-model", () -> false, transport.saved);
    }
    private static void sameOriginalText(JSONObject actual, JSONObject original) throws Exception {
        JSONArray current = actual.getJSONArray("units"), source = original.getJSONArray("units");
        eq(current.length(), source.length()); eq(actual.optString("inputHash"), original.optString("inputHash"));
        for (int i = 0; i < source.length(); i++)
            for (String field : new String[]{"unitId", "text", "spokenText", "paragraph", "start", "end", "index"})
                eq(current.getJSONObject(i).opt(field), source.getJSONObject(i).opt(field));
    }
    private static void complete(JSONObject result, JSONObject original, JSONArray fixed) throws Exception {
        JSONObject plan = result.getJSONObject("plan");
        eq(plan.optString("analysisStatus"), "complete"); eq(plan.optString("analysisProvider"), "chatgpt");
        eq(plan.optInt("analyzedUnits"), 70); eq(plan.optInt("batchIndex"), 2);
        eq(plan.optLong("inputTokens"), 22L); eq(plan.optLong("outputTokens"), 14L);
        eq(result.getJSONArray("cast").toString(), fixed.toString()); sameOriginalText(plan, original);
        for (int i = 0; i < 70; i++) {
            JSONObject unit = plan.getJSONArray("units").getJSONObject(i);
            yes(unit.optBoolean("analyzed"), "completed metadata is adopted"); eq(unit.optString("speakerId"), FIXED);
            eq(unit.optString("emotion"), i < 40 ? "calm" : "joy");
        }
    }

    public static void main(String[] args) throws Exception {
        eq(SpeechAnalyzer.BATCH_UNITS, 40);
        JSONObject original = plan(); JSONArray fixed = fixedCast();
        String originalJson = original.toString(), fixedJson = fixed.toString(); eq(original.getJSONArray("units").length(), 70);

        Snapshots success = new Snapshots(); FixtureTransport clean = new FixtureTransport(original, fixed, success, 0, false);
        JSONObject completed = analyze(clean, original, fixed); complete(completed, original, fixed);
        eq(clean.calls, 2); eq(clean.authorizations, 2); eq(success.plan.optString("analysisStatus"), "complete");
        yes(success.firstBatch != null, "first compact batch checkpointed before the second request");

        Snapshots failed = new Snapshots(); FixtureTransport quota = new FixtureTransport(original, fixed, failed, 0, true);
        try { analyze(quota, original, fixed); throw new AssertionError("item.done followed by quota failure was adopted"); }
        catch (IOException expected) {
            yes(expected.getCause() instanceof ChatGptResponses.Failure, "SSE failure reaches the analyzer as a structured failure");
            eq(((ChatGptResponses.Failure) expected.getCause()).code, "subscription_sharing_usage_limit_exceeded");
            yes(!expected.getMessage().contains(PRIVATE) && !expected.getMessage().contains(TOKEN), "quota message exposes no remote body or credential");
        }
        eq(quota.calls, 2); eq(quota.authorizations, 2);
        eq(failed.plan.optString("analysisStatus"), "error"); eq(failed.plan.optInt("analyzedUnits"), 40);
        eq(failed.plan.optInt("batchIndex"), 1); eq(failed.plan.optLong("inputTokens"), 11L); eq(failed.plan.optLong("outputTokens"), 7L);
        eq(failed.plan.getJSONObject("analysisError").optString("code"), "subscription_sharing_usage_limit_exceeded");
        eq(failed.plan.getJSONObject("analysisError").optString("requestId"), "req_pipeline_40");
        eq(failed.cast.toString(), fixedJson); eq(failed.firstCast.toString(), fixedJson);
        yes(speaker(failed.cast, "c_unadopted") == null, "failed terminal never adds its completed item's cast");
        for (int i = 0; i < 70; i++) {
            JSONObject retained = failed.plan.getJSONArray("units").getJSONObject(i);
            JSONObject expected = (i < 40 ? failed.firstBatch : original).getJSONArray("units").getJSONObject(i);
            eq(retained.toString(), expected.toString());
        }

        JSONObject checkpoint = Json.copy(failed.plan); JSONArray castCheckpoint = new JSONArray(failed.cast.toString());
        String checkpointJson = checkpoint.toString(); Snapshots resumed = new Snapshots();
        FixtureTransport resume = new FixtureTransport(original, fixed, resumed, 40, false);
        JSONObject recovered = analyze(resume, checkpoint, castCheckpoint); complete(recovered, original, fixed);
        eq(resume.calls, 1); eq(resume.authorizations, 1); eq(resumed.plan.optString("analysisStatus"), "complete");
        yes(!recovered.getJSONObject("plan").has("analysisError"), "successful resume clears the prior failure diagnostic");
        for (int i = 0; i < 40; i++)
            eq(recovered.getJSONObject("plan").getJSONArray("units").getJSONObject(i).toString(),
                    failed.firstBatch.getJSONArray("units").getJSONObject(i).toString());
        eq(checkpoint.toString(), checkpointJson); eq(original.toString(), originalJson); eq(fixed.toString(), fixedJson);
        System.out.println("ChatGptResponsePipeline production checks passed: " + checks);
        System.out.println("70 self-authored units, compact absent/null output, 40+30 batch adoption, quota after item.done, retained checkpoint and 30-unit resume.");
        System.out.println("Uses real SpeechAnalyzer and ChatGptResponses.readCompleted with synthetic SSE and in-memory snapshots; no live API, account, Android storage or network call.");
    }
}
