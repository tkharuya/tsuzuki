package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the real analyzer against synthetic Responses envelopes. No API key is used. */
public final class SpeechAnalyzerTest {
    private static int checks;
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual); }
    private static void yes(boolean actual, String message) { checks++; if (!actual) throw new AssertionError(message); }
    private static JSONObject plan(int count) {
        JSONArray p = new JSONArray(); for (int i = 0; i < count; i++) p.put(Json.obj("text", "「これは自作の検査台詞" + i + "です。」"));
        return SpeechPlan.create("fixture", Json.obj("url", "local://fixture/1", "paragraphs", p, "title", "検査"), null);
    }
    private static JSONObject requestData(JSONObject request) { return Json.parse(request.optJSONArray("input").optJSONObject(0).optJSONArray("content").optJSONObject(0).optString("text")); }
    private static JSONObject annotation(String unitId) {
        return Json.obj("unitId", unitId, "speakerId", "unknown", "kind", "dialogue", "emotion", "neutral", "intensity", 0.0, "rate", 1.0, "pitch", 1.0, "confidence", .2);
    }
    private static JSONObject metadata(JSONObject request) {
        JSONArray targets = Json.arr(requestData(request), "targetUnits"), a = new JSONArray();
        for (int i = 0; i < targets.length(); i++) a.put(annotation(targets.optJSONObject(i).optString("unitId")));
        return Json.obj("cast", new JSONArray(), "assignments", a);
    }
    private static JSONObject envelope(JSONObject metadata) {
        return Json.obj("status", "completed", "output", new JSONArray().put(Json.obj("type", "message", "status", "completed", "content", new JSONArray().put(Json.obj("type", "output_text", "text", metadata.toString())))), "usage", Json.obj("input_tokens", 11, "output_tokens", 7));
    }
    private static JSONObject person(String id, String name) {
        return Json.obj("id", id, "name", name, "aliases", new JSONArray(), "gender", "female", "tier", "main", "offlineVoice", "miko", "style", "落ち着いた声", "rate", 1.0, "pitch", 1.0);
    }
    private static JSONObject speaker(JSONArray cast, String id) {
        for (int i = 0; i < cast.length(); i++) if (id.equals(cast.optJSONObject(i).optString("id"))) return cast.optJSONObject(i);
        throw new AssertionError("missing speaker " + id);
    }
    private static void assignAll(JSONObject metadata, String speaker) {
        JSONArray annotations = Json.arr(metadata, "assignments");
        for (int i = 0; i < annotations.length(); i++) {
            Json.put(annotations.optJSONObject(i), "speakerId", speaker); Json.put(annotations.optJSONObject(i), "confidence", .95);
            Json.put(annotations.optJSONObject(i), "emotion", "joy"); Json.put(annotations.optJSONObject(i), "intensity", .6);
        }
    }
    private static void replace(JSONArray target, JSONArray current) {
        while (target.length() > 0) target.remove(target.length() - 1);
        for (int i = 0; i < current.length(); i++) target.put(current.optJSONObject(i));
    }
    private static void failure(SpeechAnalyzer.Transport transport, String label) throws Exception {
        final JSONObject[] saved = {null};
        try { new SpeechAnalyzer(transport).analyze(plan(3), null, "fixture-only-not-a-key", "gpt-6-luna", () -> false, (p, c) -> saved[0] = Json.copy(p)); throw new AssertionError("accepted " + label); }
        catch (IOException expected) { checks++; }
        eq(saved[0].optString("analysisStatus"), "error"); eq(saved[0].optInt("analyzedUnits"), 0);
        for (int i = 0; i < Json.arr(saved[0], "units").length(); i++) eq(Json.arr(saved[0], "units").optJSONObject(i).optBoolean("analyzed"), false);
    }
    public static void main(String[] args) throws Exception {
        Set<String> seen = new HashSet<>(); final int[] requests = {0}, snapshots = {0};
        JSONObject original = plan(1003);
        SpeechAnalyzer analysis = new SpeechAnalyzer((request, key, cancel) -> {
            requests[0]++; eq(request.optBoolean("store", true), false);
            eq(request.optString("model"), "gpt-6-luna");
            JSONObject format = request.optJSONObject("text").optJSONObject("format");
            eq(format.optString("type"), "json_schema"); eq(format.optBoolean("strict"), true);
            eq(format.optJSONObject("schema").optBoolean("additionalProperties", true), false);
            JSONObject personProperties = format.optJSONObject("schema").optJSONObject("properties").optJSONObject("cast").optJSONObject("items").optJSONObject("properties");
            yes(personProperties.has("offlineVoice") && !personProperties.has("voice"), "GPT suggests only the offline provider's voice IDs");
            eq(Json.arr(requestData(request), "offlineVoices").length(), OfflineVoices.catalog().length());
            yes(request.optString("instructions").contains("UNTRUSTED DATA"), "novel text must be untrusted input");
            JSONArray targets = Json.arr(requestData(request), "targetUnits");
            yes(targets.length() <= SpeechAnalyzer.BATCH_UNITS, "bounded API request");
            for (int i = 0; i < targets.length(); i++) yes(seen.add(targets.optJSONObject(i).optString("unitId")), "each ID exactly once");
            return envelope(metadata(request));
        });
        JSONObject result = analysis.analyze(original, null, "fixture-only-not-a-key", "gpt-6-luna", () -> false, (p, c) -> snapshots[0]++);
        JSONObject completed = result.optJSONObject("plan");
        eq(completed.optString("analysisStatus"), "complete"); eq(completed.optInt("analyzedUnits"), 1003);
        eq(seen.size(), 1003); eq(requests[0], 26); yes(snapshots[0] >= 27, "every batch must checkpoint");
        eq(completed.optLong("inputTokens"), 286L); eq(completed.optLong("outputTokens"), 182L);
        eq(original.optInt("analyzedUnits"), 0); // No unsaved mutation of input objects.
        for (int i = 0; i < 1003; i++) {
            JSONObject before = Json.arr(original, "units").optJSONObject(i), after = Json.arr(completed, "units").optJSONObject(i);
            for (String field : new String[]{"text", "spokenText", "paragraph", "start", "end", "unitId"}) eq(after.opt(field), before.opt(field));
        }
        int oldRequests = requests[0];
        analysis.analyze(completed, result.optJSONArray("cast"), "fixture-only-not-a-key", "gpt-6-luna", () -> false, null);
        eq(requests[0], oldRequests); // Completed cache costs no new call.

        AtomicBoolean cancel = new AtomicBoolean(false); final int[] cancelCalls = {0}; final JSONObject[] checkpoint = {null}; final JSONArray[] savedCast = {null};
        SpeechAnalyzer interrupted = new SpeechAnalyzer((r, k, c) -> { cancelCalls[0]++; return envelope(metadata(r)); });
        JSONObject paused = interrupted.analyze(plan(99), null, "fixture-only-not-a-key", "gpt-6-luna", cancel::get, (p, c) -> {
            checkpoint[0] = Json.copy(p); savedCast[0] = c;
            if (p.optInt("analyzedUnits") == 40) cancel.set(true);
        }).optJSONObject("plan");
        eq(paused.optString("analysisStatus"), "paused"); eq(paused.optInt("analyzedUnits"), 40); eq(cancelCalls[0], 1);
        cancel.set(false);
        JSONObject resumed = interrupted.analyze(checkpoint[0], savedCast[0], "fixture-only-not-a-key", "gpt-6-luna", cancel::get, null).optJSONObject("plan");
        eq(resumed.optString("analysisStatus"), "complete"); eq(resumed.optInt("analyzedUnits"), 99); eq(cancelCalls[0], 3);
        JSONObject neverStarted = interrupted.analyze(plan(2), null, "", "gpt-6-luna", () -> true, null).optJSONObject("plan");
        eq(neverStarted.optString("analysisStatus"), "paused"); eq(cancelCalls[0], 3);

        final JSONObject[] partial = {null}; final int[] failingCalls = {0};
        SpeechAnalyzer flaky = new SpeechAnalyzer((r, k, c) -> { if (++failingCalls[0] == 2) throw new IOException("人工的な通信切断"); return envelope(metadata(r)); });
        try { flaky.analyze(plan(90), null, "fixture-only-not-a-key", "gpt-6-luna", () -> false, (p, c) -> partial[0] = Json.copy(p)); throw new AssertionError("missing network error"); } catch (IOException expected) { checks++; }
        eq(partial[0].optString("analysisStatus"), "error"); eq(partial[0].optInt("analyzedUnits"), 40);
        JSONObject recovered = flaky.analyze(partial[0], null, "fixture-only-not-a-key", "gpt-6-luna", () -> false, null).optJSONObject("plan");
        eq(recovered.optInt("analyzedUnits"), 90); eq(failingCalls[0], 4);

        failure((r, k, c) -> { JSONObject m = metadata(r); Json.arr(m, "assignments").remove(0); return envelope(m); }, "missing unit");
        failure((r, k, c) -> { JSONObject m = metadata(r); Json.put(Json.arr(m, "assignments").optJSONObject(1), "unitId", Json.arr(m, "assignments").optJSONObject(0).optString("unitId")); return envelope(m); }, "duplicate ID");
        failure((r, k, c) -> { JSONObject m = metadata(r); Json.put(Json.arr(m, "assignments").optJSONObject(0), "unitId", "invented"); return envelope(m); }, "foreign ID");
        failure((r, k, c) -> { JSONObject m = metadata(r); Json.put(Json.arr(m, "assignments").optJSONObject(0), "speakerId", "never_defined"); return envelope(m); }, "undefined speaker");
        failure((r, k, c) -> { JSONObject m = metadata(r); Json.put(Json.arr(m, "assignments").optJSONObject(0), "text", "改ざん"); return envelope(m); }, "rewritten text");
        failure((r, k, c) -> { JSONObject m = metadata(r); Json.put(Json.arr(m, "assignments").optJSONObject(0), "confidence", "1"); return envelope(m); }, "numeric string");
        failure((r, k, c) -> { JSONObject m = metadata(r); Json.put(Json.arr(m, "assignments").optJSONObject(0), "rate", 50); return envelope(m); }, "unbounded prosody");
        failure((r, k, c) -> Json.obj("status", "incomplete", "incomplete_details", Json.obj("reason", "max_output_tokens"), "output", new JSONArray()), "truncated response");
        failure((r, k, c) -> Json.obj("status", "completed", "output", new JSONArray().put(Json.obj("type", "message", "content", new JSONArray().put(Json.obj("type", "refusal", "refusal", "fixture refusal"))))), "refusal");
        failure((r, k, c) -> Json.obj("status", "completed", "output", new JSONArray().put(Json.obj("type", "message", "content", new JSONArray().put(Json.obj("type", "output_text", "text", "{not-json"))))), "malformed JSON");
        failure((r, k, c) -> Json.obj("status", "completed", "output", new JSONArray()), "empty response");
        failure((r, k, c) -> { JSONObject e = envelope(metadata(r)); JSONObject part = Json.arr(Json.arr(e, "output").optJSONObject(0), "content").optJSONObject(0); Json.put(part, "text", part.optString("text") + " trailing"); return e; }, "trailing content");
        failure((r, k, c) -> { JSONObject m = metadata(r), p = person("c_invalid", "検査人物"); Json.put(p, "offlineVoice", "shimmer"); Json.arr(m, "cast").put(p); return envelope(m); }, "foreign provider's voice name");
        failure((r, k, c) -> { JSONObject m = metadata(r); Json.arr(m, "cast").put(person("c_duplicate", "一人目")).put(person("c_duplicate", "二人目")); return envelope(m); }, "duplicate cast ID");
        failure((r, k, c) -> {
            JSONObject m = metadata(r), a = person("c_alias_a", "一人目"), b = person("c_alias_b", "二人目");
            Json.put(a, "aliases", new JSONArray().put("衝突別名")); Json.put(b, "aliases", new JSONArray().put("衝突別名"));
            Json.arr(m, "cast").put(a).put(b); return envelope(m);
        }, "conflicting aliases across distinct characters");

        JSONObject custom = plan(3), locked = Json.arr(custom, "units").optJSONObject(0);
        Json.put(locked, "speakerId", "c_manual"); Json.put(locked, "emotion", "whisper"); Json.put(locked, "locked", true);
        JSONObject manual = person("c_manual", "青葉（利用者設定）"); Json.put(manual, "aliases", new JSONArray().put("青葉"));
        Json.put(manual, "manual", true); Json.put(manual, "voice", "cedar"); Json.put(manual, "deviceVoice", "test-engine-voice");
        JSONObject manualResult = new SpeechAnalyzer((r, k, c) -> {
            JSONObject known = speaker(Json.arr(requestData(r), "knownCast"), "c_manual");
            eq(known.optString("name"), "青葉（利用者設定）"); eq(known.optJSONArray("aliases").optString(0), "青葉");
            eq(known.optString("offlineVoice"), "miko"); eq(known.optBoolean("voiceLocked"), true); eq(known.optBoolean("profileLocked"), true);
            eq(known.has("voice"), false); // Legacy OpenAI configuration is not a offline instruction.
            JSONObject m = metadata(r); JSONArray targets = Json.arr(requestData(r), "targetUnits"); eq(targets.length(), 2);
            Json.arr(m, "cast").put(person("c_ai_aoba", "青葉"));
            JSONObject a = Json.arr(m, "assignments").optJSONObject(0);
            Json.put(a, "speakerId", "c_ai_aoba"); Json.put(a, "confidence", .95); Json.put(a, "emotion", "joy"); Json.put(a, "intensity", .4);
            JSONObject uncertain = Json.arr(m, "assignments").optJSONObject(1);
            Json.put(uncertain, "speakerId", "c_ai_aoba"); Json.put(uncertain, "confidence", .4); Json.put(uncertain, "emotion", "anger"); Json.put(uncertain, "intensity", .9);
            return envelope(m);
        }).analyze(custom, new JSONArray().put(manual), "fixture-only-not-a-key", "gpt-6-luna", () -> false, null);
        JSONArray customUnits = Json.arr(manualResult.optJSONObject("plan"), "units");
        eq(customUnits.optJSONObject(0).toString(), locked.toString());
        eq(customUnits.optJSONObject(1).optString("speakerId"), "c_manual");
        eq(customUnits.optJSONObject(2).optString("speakerId"), "unknown");
        eq(customUnits.optJSONObject(2).optString("emotion"), "neutral"); eq(customUnits.optJSONObject(2).optDouble("intensity"), 0.0);
        eq(manualResult.optJSONArray("cast").optJSONObject(0).toString(), CastRegistry.migrate(new JSONArray().put(manual), "metan").optJSONObject(0).toString());
        eq(manualResult.optJSONArray("cast").length(), 3); // No duplicate AI identity.

        final JSONArray manualCast = manualResult.optJSONArray("cast");
        JSONObject switchedManual = new SpeechAnalyzer((r, k, c) -> {
            JSONObject m = metadata(r), attempt = person("c_manual", "AIの変更候補");
            Json.put(attempt, "offlineVoice", "zundamon"); Json.put(attempt, "style", "別の話し方");
            Json.arr(m, "cast").put(attempt); assignAll(m, "c_manual"); return envelope(m);
        }, "ryusei").analyze(manualResult.optJSONObject("plan"), manualCast, "fixture-only-not-a-key", "second-fixture-model", () -> false, null);
        eq(speaker(switchedManual.optJSONArray("cast"), "c_manual").toString(), speaker(manualCast, "c_manual").toString());
        eq(Json.arr(switchedManual.optJSONObject("plan"), "units").optJSONObject(0).toString(), locked.toString());

        final JSONArray[] persisted = {CastRegistry.migrate(new JSONArray().put(person("c_live", "編集中の旧名")), "metan")};
        final int[] liveRequests = {0};
        JSONObject liveEdits = new SpeechAnalyzer((r, k, c) -> {
            JSONObject m = metadata(r);
            if (++liveRequests[0] == 1) {
                persisted[0] = CastRegistry.patchUser(persisted[0], new JSONArray().put(Json.obj("id", "c_live", "name", "編集後の名前",
                        "aliases", new JSONArray().put("編集中の旧名"), "offlineVoice", "zundamon", "style", "利用者が指定した話し方")), "metan");
                Json.arr(m, "cast").put(person("c_accidental_new_id", "編集中の旧名"));
                assignAll(m, "c_accidental_new_id");
            } else {
                JSONObject known = speaker(Json.arr(requestData(r), "knownCast"), "c_live");
                eq(known.optString("name"), "編集後の名前"); eq(known.optString("offlineVoice"), "zundamon");
                eq(known.optBoolean("profileLocked"), true); assignAll(m, "c_live");
            }
            return envelope(m);
        }).analyze(plan(41), persisted[0], "fixture-only-not-a-key", "gpt-6-luna", () -> false, (p, cast) -> {
            persisted[0] = CastRegistry.mergeAnalysis(persisted[0], cast, "metan");
            replace(cast, persisted[0]); // Same contract as the production SpeechStore checkpoint.
        });
        eq(liveRequests[0], 2); eq(liveEdits.optJSONArray("cast").length(), 3);
        JSONObject liveSpeaker = speaker(liveEdits.optJSONArray("cast"), "c_live");
        eq(liveSpeaker.optString("offlineVoice"), "zundamon"); eq(liveSpeaker.optString("style"), "利用者が指定した話し方");
        for (int i = 0; i < 41; i++) eq(Json.arr(liveEdits.optJSONObject("plan"), "units").optJSONObject(i).optString("speakerId"), "c_live");

        final JSONArray twins = CastRegistry.migrate(new JSONArray().put(person("c_east", "同名人物")).put(person("c_west", "同名人物")), "metan");
        Json.put(speaker(twins, "c_east"), "offlineVoice", "zundamon"); Json.put(speaker(twins, "c_west"), "offlineVoice", "ryusei");
        JSONObject ambiguous = new SpeechAnalyzer((r, k, c) -> {
            JSONObject m = metadata(r); Json.arr(m, "cast").put(person("c_ambiguous_guess", "同名人物"));
            assignAll(m, "c_ambiguous_guess"); return envelope(m);
        }).analyze(plan(2), twins, "fixture-only-not-a-key", "gpt-6-luna", () -> false, null);
        eq(ambiguous.optJSONArray("cast").length(), 4);
        for (int i = 0; i < 2; i++) {
            JSONObject unit = Json.arr(ambiguous.optJSONObject("plan"), "units").optJSONObject(i);
            eq(unit.optString("speakerId"), "unknown"); eq(unit.optString("emotion"), "neutral"); eq(unit.optDouble("intensity"), 0.0);
        }
        eq(speaker(ambiguous.optJSONArray("cast"), "c_east").optString("offlineVoice"), "zundamon");
        eq(speaker(ambiguous.optJSONArray("cast"), "c_west").optString("offlineVoice"), "ryusei");
        JSONObject bothNew = new SpeechAnalyzer((r, k, c) -> {
            JSONObject m = metadata(r); Json.arr(m, "cast").put(person("c_new_a", "新しい同名人物")).put(person("c_new_b", "新しい同名人物"));
            Json.put(Json.arr(m, "assignments").optJSONObject(0), "speakerId", "c_new_a");
            Json.put(Json.arr(m, "assignments").optJSONObject(1), "speakerId", "c_new_b");
            for (int i = 0; i < 2; i++) Json.put(Json.arr(m, "assignments").optJSONObject(i), "confidence", .99);
            return envelope(m);
        }).analyze(plan(2), null, "fixture-only-not-a-key", "gpt-6-luna", () -> false, null);
        eq(bothNew.optJSONArray("cast").length(), 2);
        eq(Json.arr(bothNew.optJSONObject("plan"), "units").optJSONObject(0).optString("speakerId"), "unknown");
        eq(Json.arr(bothNew.optJSONObject("plan"), "units").optJSONObject(1).optString("speakerId"), "unknown");

        JSONObject customDefault = new SpeechAnalyzer((r, k, c) -> {
            JSONObject m = metadata(r), p = person("c_default", "既定音声人物"); Json.put(p, "offlineVoice", "");
            Json.arr(m, "cast").put(p); assignAll(m, "c_default"); return envelope(m);
        }, "zundamon").analyze(plan(1), null, "fixture-only-not-a-key", "gpt-6-luna", () -> false, null);
        eq(speaker(customDefault.optJSONArray("cast"), "c_default").optString("offlineVoice"), OfflineVoices.initialVoice(person("c_default", "既定音声人物").put("offlineVoice", ""), "zundamon"));
        eq(speaker(customDefault.optJSONArray("cast"), "narrator").optString("offlineVoice"), "zundamon");

        final JSONObject[] live = {null};
        JSONObject concurrent = new SpeechAnalyzer((r, k, c) -> {
            JSONObject unit = Json.arr(live[0], "units").optJSONObject(0); Json.put(unit, "locked", true); Json.put(unit, "emotion", "calm");
            return envelope(metadata(r));
        }).analyze(plan(3), null, "fixture-only-not-a-key", "gpt-6-luna", () -> false, (p, c) -> live[0] = p).optJSONObject("plan");
        eq(Json.arr(concurrent, "units").optJSONObject(0).optString("emotion"), "calm"); eq(concurrent.optInt("analyzedUnits"), 3);
        final int[] switchedCalls = {0};
        new SpeechAnalyzer((r, k, c) -> { switchedCalls[0]++; return envelope(metadata(r)); }).analyze(completed, null, "fixture-only-not-a-key", "alternate-test-model", () -> false, null);
        eq(switchedCalls[0], 26); // Changing model invalidates automatic analysis but preserves original text.
        accountCredentialsAndCheckpoints();
        accountModelCatalogCompatibility();
        finalAnswerPhases();
        System.out.println("SpeechAnalyzer production checks passed: " + checks);
    }

    private static void accountCredentialsAndCheckpoints() throws Exception {
        final int[] tokens = {0}, sent = {0}; final List<String> seen = new ArrayList<>();
        SpeechAnalyzer account = new SpeechAnalyzer((request, token, cancel) -> {
            sent[0]++; seen.add(token);
            eq(token, "fixture-account-token-" + sent[0]);
            eq(request.optBoolean("stream"), true); eq(request.optBoolean("store", true), false);
            yes(!request.has("max_output_tokens") && !request.has("previous_response_id") && !request.has("background"), "account request omits unsupported options");
            eq(request.optString("model"), "fixture-account-model");
            return envelope(metadata(request));
        }, "metan", () -> "fixture-account-token-" + (++tokens[0]));
        JSONObject result = account.analyze(plan(1003), null, "fixture-account-model", () -> false, null);
        eq(result.optJSONObject("plan").optInt("analyzedUnits"), 1003); eq(tokens[0], 26); eq(sent[0], 26);
        eq(result.optJSONObject("plan").optString("analysisProvider"), "chatgpt");
        yes(!result.toString().contains("fixture-account-token-"), "access tokens never enter plan or cast");
        account.analyze(result.optJSONObject("plan"), result.optJSONArray("cast"), "fixture-account-model", () -> false, null);
        eq(tokens[0], 26); eq(sent[0], 26); // Fully checkpointed work does not refresh or invoke inference again.
        try { account.analyze(plan(1), null, "", () -> false, null); throw new AssertionError("missing model fallback"); }
        catch (IllegalArgumentException expected) { checks++; }
        eq(tokens[0], 26);
        final int[] calls = {0}; final JSONObject[] checkpoint = {null};
        SpeechAnalyzer limited = new SpeechAnalyzer((request, token, cancel) -> {
            if (++calls[0] == 2) throw new ChatGptResponses.Failure("subscription_sharing_usage_limit_exceeded", 200, "", "req_fixture", "sse_failed");
            return envelope(metadata(request));
        }, "metan", () -> "fixture-account-access");
        try { limited.analyze(plan(90), null, "ignored-legacy-api-key", "fixture-account-model", () -> false, (p, c) -> checkpoint[0] = Json.copy(p)); throw new AssertionError("usage failure missing"); }
        catch (IOException expected) { yes(expected.getMessage().contains("利用量を管理"), "quota exposes useful recovery"); }
        eq(calls[0], 2); eq(checkpoint[0].optInt("analyzedUnits"), 40);
        eq(checkpoint[0].optJSONObject("analysisError").optString("code"), "subscription_sharing_usage_limit_exceeded");
        eq(checkpoint[0].optJSONObject("analysisError").optString("requestId"), "req_fixture");
        yes(!checkpoint[0].toString().contains("fixture-account-access"), "failed checkpoint contains no token");
        JSONObject resumed = limited.analyze(checkpoint[0], null, "fixture-account-model", () -> false, null).optJSONObject("plan");
        eq(resumed.optInt("analyzedUnits"), 90); eq(calls[0], 4); yes(!resumed.has("analysisError"), "successful retry clears stale account error");
        final int[] credentialCalls = {0}, cancelledSends = {0}; AtomicBoolean cancelled = new AtomicBoolean(false);
        SpeechAnalyzer cancelledDuringRefresh = new SpeechAnalyzer((r, token, c) -> { cancelledSends[0]++; return envelope(metadata(r)); }, "metan", () -> {
            credentialCalls[0]++; cancelled.set(true); return "fixture-cancelled-token";
        });
        JSONObject paused = cancelledDuringRefresh.analyze(plan(1), null, "fixture-account-model", cancelled::get, null).optJSONObject("plan");
        eq(paused.optString("analysisStatus"), "paused"); eq(credentialCalls[0], 1); eq(cancelledSends[0], 0);
    }

    private static void accountModelCatalogCompatibility() throws Exception {
        char[] padding = new char[128]; java.util.Arrays.fill(padding, 'a'); String maximum = new String(padding);
        for (String slug : new String[]{"fixture/family:model-v1", maximum}) {
            final int[] tokens = {0}, sends = {0};
            SpeechAnalyzer analyzer = new SpeechAnalyzer((request, token, cancel) -> {
                sends[0]++; eq(request.optString("model"), slug); return envelope(metadata(request));
            }, "metan", () -> { tokens[0]++; return "fixture-account-token"; });
            JSONObject analyzed = analyzer.analyze(plan(1), null, slug, () -> false, null).optJSONObject("plan");
            eq(analyzed.optString("analysisModel"), slug); eq(analyzed.optString("analysisStatus"), "complete");
            eq(tokens[0], 1); eq(sends[0], 1);
        }
        final int[] accountSends = {0}, accountTokens = {0};
        SpeechAnalyzer account = new SpeechAnalyzer((request, token, cancel) -> {
            accountSends[0]++; return envelope(metadata(request));
        }, "metan", () -> { accountTokens[0]++; return "fixture-account-token"; });
        for (String invalid : new String[]{maximum + "a", "/fixture", "_fixture", "fixture model", "fixture?query", "モデル"}) {
            try { account.analyze(plan(1), null, invalid, () -> false, null); throw new AssertionError("invalid account model accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        eq(accountSends[0], 0); eq(accountTokens[0], 0);
        final int[] legacySends = {0};
        SpeechAnalyzer legacy = new SpeechAnalyzer((request, key, cancel) -> { legacySends[0]++; return envelope(metadata(request)); });
        for (String stillInvalid : new String[]{"fixture/family:model-v1", maximum}) {
            try { legacy.analyze(plan(1), null, "fixture-only-not-a-key", stillInvalid, () -> false, null); throw new AssertionError("legacy API model constraints changed"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        eq(legacySends[0], 0);
    }
    private static void finalAnswerPhases() throws Exception {
        for (Object phase : new Object[]{"final_answer", JSONObject.NULL}) {
            SpeechAnalyzer analyzer = new SpeechAnalyzer((request, token, cancel) -> {
                JSONObject result = envelope(metadata(request)), finalItem = Json.arr(result, "output").optJSONObject(0);
                Json.put(finalItem, "phase", phase);
                JSONObject commentary = Json.obj("type", "message", "status", "completed", "phase", "commentary", "content",
                        new JSONArray().put(Json.obj("type", "output_text", "text", "これから解析します。JSONではない自作の進捗文。")));
                JSONObject future = Json.copy(commentary); Json.put(future, "phase", "future_nonfinal_phase");
                Json.put(result, "output", new JSONArray().put(commentary).put(future).put(finalItem)); return result;
            }, "metan", () -> "fixture-account-access");
            JSONObject result = analyzer.analyze(plan(3), null, "fixture-account-model", () -> false, null).optJSONObject("plan");
            eq(result.optString("analysisStatus"), "complete"); eq(result.optInt("analyzedUnits"), 3);
        }
        failure((r,k,c) -> {
            JSONObject result = envelope(metadata(r)); Json.put(Json.arr(result, "output").optJSONObject(0), "phase", "commentary"); return result;
        }, "commentary JSON without a final answer");
        failure((r,k,c) -> {
            JSONObject result = envelope(metadata(r)); JSONObject text = Json.arr(Json.arr(result, "output").optJSONObject(0), "content").optJSONObject(0);
            Json.put(text, "text", text.optString("text") + '\0' + "trailing text"); return result;
        }, "literal NUL concealing trailing metadata");
    }
}
