package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;

/** Actual SSE/HTTPS protocol implementation with synthetic bytes only; no real OAuth session or API request. */
public final class ChatGptResponsesTest {
    private static int checks;
    private static final String TOKEN = "FIXTURE_ONLY_NOT_A_REAL_OAUTH_TOKEN";
    private static final String SECRET = "PROVIDER_MESSAGE_MUST_NOT_BE_DISPLAYED";
    private interface Operation { void run() throws Exception; }
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object actual, Object expected) { checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    private static ChatGptResponses.Failure denied(Operation run, String label) throws Exception {
        try { run.run(); throw new AssertionError("accepted " + label); }
        catch (ChatGptResponses.Failure expected) {
            checks++; yes(!expected.getMessage().contains(TOKEN) && !expected.getMessage().contains(SECRET), "error never reveals private diagnostic text"); return expected;
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject request() { return Json.obj("model", "fixture-account-model", "store", true, "stream", false, "max_output_tokens", 16,
            "instructions", "Only return metadata.", "input", new JSONArray().put(Json.obj("role", "user", "content", "自作の試験文。")),
            "text", Json.obj("format", Json.obj("type", "json_schema", "schema", Json.obj("type", "object")))); }
    private static JSONObject response() { return Json.obj("id", "resp_fixture", "status", "completed", "output", new JSONArray().put(Json.obj("id", "msg_fixture", "type", "message", "status", "completed", "content",
            new JSONArray().put(Json.obj("type", "output_text", "text", Json.obj("cast", new JSONArray(), "assignments", new JSONArray()).toString())))), "usage", Json.obj("input_tokens", 7, "output_tokens", 3)); }
    private static String event(String kind, JSONObject body) { return "event: " + kind + "\ndata: " + Json.merge(Json.obj("type", kind), body).toString() + "\n\n"; }
    private static String completed() { return event("response.completed", Json.obj("response", response())); }
    private static JSONObject parse(String input) throws Exception { return ChatGptResponses.readCompleted(new ByteArrayInputStream(bytes(input)), () -> false, "req_fixture"); }

    public static void main(String[] args) throws Exception {
        requestContract(); streamCompletion(); compactCompletion(); streamFailures(); transport(); payloadCompatibility(); cancellation();
        System.out.println("ChatGptResponses production checks passed: " + checks + " (synthetic HTTPS/SSE fixtures, no live API or account)");
    }
    private static void requestContract() throws Exception {
        JSONObject input = request();
        String[] unsupported = {"background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent", "prompt",
                "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation", "user", "previous_response_id"};
        for (String key : unsupported) Json.put(input, key, "must be removed");
        JSONObject actual = ChatGptResponses.accountRequest(input);
        for (String key : unsupported) yes(!actual.has(key), "unsupported plan field omitted: " + key);
        yes(!actual.optBoolean("store") && actual.optBoolean("stream"), "explicit stateless streaming");
        eq(actual.optString("model"), "fixture-account-model");
        eq(actual.optJSONArray("input").toString(), input.optJSONArray("input").toString());
        eq(actual.optJSONObject("text").toString(), input.optJSONObject("text").toString());
        yes(input.has("max_output_tokens") && input.optBoolean("store"), "original request is not mutated");
        denied(() -> ChatGptResponses.accountRequest(Json.obj("input", "not an array")), "string input");
        denied(() -> ChatGptResponses.accountRequest(Json.obj("input", new JSONArray())), "empty input");
        denied(() -> ChatGptResponses.accountRequest(Json.obj("input", new JSONArray().put(Json.obj("role", "system", "type", "message")))), "unsupported system item");
        denied(() -> ChatGptResponses.accountRequest(Json.obj("input", new JSONArray().put("untyped"))), "untyped input item");
    }
    private static void streamCompletion() throws Exception {
        eq(parse(completed()).optString("status"), "completed");
        eq(SpeechAnalyzer.responseMetadata(parse(completed())).optJSONArray("assignments").length(), 0);
        String stream = "\ufeff: comment\r\n\r\n" + event("response.created", Json.obj("sequence_number", 0, "response", Json.obj("id", "resp_fixture", "status", "in_progress")))
                + event("response.output_text.delta", Json.obj("sequence_number", 1, "delta", "部分的な未検証のJSON"))
                + "id: ignored\nretry: 1\n: keep alive\n\n"
                + event("response.completed", Json.obj("sequence_number", 2, "response", response()));
        eq(parse(stream.replace("\n", "\r\n")).optJSONObject("usage").optInt("output_tokens"), 3);
        String multiline = "event: response.completed\ndata: {\ndata: \"type\":\"response.completed\",\ndata: \"response\":" + response() + "\ndata: }\n\n";
        eq(parse(multiline).optString("id"), "resp_fixture");
        eq(parse(multiline.replace("\n", "\r")).optString("id"), "resp_fixture");
        eq(parse("data: " + Json.obj("type", "response.completed", "response", response()) + "\n\n").optString("status"), "completed");
        eq(parse(event("future.metadata.event", Json.obj("metadata", "ignored")) + completed()).optString("status"), "completed");
        // Terminal full response is authoritative: speculative deltas are never used as output.
        JSONObject result = parse(event("response.output_text.delta", Json.obj("delta", SECRET)) + completed());
        yes(!result.toString().contains(SECRET), "uncommitted deltas discarded");
    }
    private static void compactCompletion() throws Exception {
        JSONObject finalMessage = Json.arr(response(), "output").optJSONObject(0);
        JSONObject added = Json.copy(finalMessage); Json.put(added, "status", "in_progress"); Json.put(added, "content", new JSONArray());
        String created = event("response.created", Json.obj("response", Json.obj("id", "resp_fixture", "status", "in_progress", "output", new JSONArray())));
        String done = created
                + event("response.output_item.added", Json.obj("output_index", 0, "item", JSONObject.NULL))
                + event("response.output_item.done", Json.obj("output_index", 1, "item", Json.obj("id", "reason_fixture", "type", "reasoning", "summary", new JSONArray())))
                + event("response.output_item.added", Json.obj("output_index", 3, "item", added))
                + event("response.output_text.delta", Json.obj("output_index", 3, "delta", SECRET))
                + event("response.output_item.done", Json.obj("output_index", 3, "item", finalMessage));
        for (boolean explicitNull : new boolean[]{false, true}) {
            JSONObject terminal = Json.obj("id", "resp_fixture", "status", "completed", "usage", Json.obj("input_tokens", 7, "output_tokens", 3));
            if (explicitNull) Json.put(terminal, "output", JSONObject.NULL);
            JSONObject result = parse(done + event("response.completed", Json.obj("response", terminal)));
            eq(result.optString("status"), "completed"); eq(Json.arr(result, "output").length(), 2);
            eq(Json.arr(result, "output").optJSONObject(0).optString("type"), "reasoning");
            eq(Json.arr(result, "output").optJSONObject(1).optString("id"), "msg_fixture");
            eq(SpeechAnalyzer.responseMetadata(result).optJSONArray("assignments").length(), 0);
            eq(result.optJSONObject("usage").optInt("output_tokens"), 3);
            yes(!result.toString().contains(SECRET), "compact completion uses finalized items only");
        }
        JSONObject explicitEmpty = Json.obj("id", "resp_fixture", "status", "completed", "output", new JSONArray());
        eq(Json.arr(parse(done + event("response.completed", Json.obj("response", explicitEmpty))), "output").length(), 0);
        denied(() -> parse(done), "finalized item without response.completed");
        denied(() -> parse(done + event("response.incomplete", Json.obj("response", Json.obj("id", "resp_fixture", "status", "incomplete")))), "finalized item followed by incomplete");
        ChatGptResponses.Failure quota = denied(() -> parse(done + event("response.failed", Json.obj("response", Json.obj("id", "resp_fixture", "status", "failed", "error",
                Json.obj("code", "subscription_sharing_usage_limit_exceeded", "message", SECRET))))), "finalized item followed by quota failure");
        eq(quota.code, "subscription_sharing_usage_limit_exceeded");
        String unfinished = created + event("response.output_item.added", Json.obj("output_index", 7, "item", added));
        denied(() -> parse(unfinished + event("response.completed", Json.obj("response", Json.obj("id", "resp_fixture", "status", "completed")))), "unfinished item cannot be reconstructed");
        String deltaOnly = created + event("response.output_text.delta", Json.obj("delta", Json.obj("cast", new JSONArray(), "assignments", new JSONArray()).toString()));
        JSONObject result = parse(deltaOnly + event("response.completed", Json.obj("response", Json.obj("id", "resp_fixture", "status", "completed"))));
        eq(Json.arr(result, "output").length(), 0);
        try { SpeechAnalyzer.responseMetadata(result); throw new AssertionError("delta-only metadata must not be adopted"); }
        catch (IOException expected) { checks++; }
    }
    private static void streamFailures() throws Exception {
        String partial = event("response.output_text.delta", Json.obj("delta", "{\"cast\":[],\"assignments\":[]}"));
        eq(denied(() -> parse(partial), "EOF after valid-looking metadata delta").code, "stream_interrupted");
        eq(denied(() -> parse(partial + "data: [DONE]\n\n"), "DONE without response.completed").code, "stream_interrupted");
        for (String kind : new String[]{"response.incomplete", "response.cancelled"})
            eq(denied(() -> parse(partial + event(kind, Json.obj("response", Json.obj("status", "incomplete")))), kind).code, "response_incomplete");
        String[] codes = {"subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable", "subscription_sharing_user_not_eligible",
                "subscription_sharing_unsupported_capability", "subscription_sharing_invalid_user", "subscription_sharing_route_not_supported",
                "subscription_sharing_user_unavailable", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context"};
        for (String code : codes) {
            JSONObject error = Json.obj("code", code, "message", SECRET + TOKEN, "param", "text.format");
            ChatGptResponses.Failure f = denied(() -> parse(partial + event("response.failed", Json.obj("response", Json.obj("status", "failed", "error", error)))), "failure after partial text " + code);
            eq(f.code, code); eq(f.httpStatus, 200); eq(f.param, "text.format"); eq(f.requestId, "req_fixture");
            yes(!f.diagnostics().toString().contains(TOKEN) && !f.diagnostics().toString().contains(SECRET), "diagnostics contain only identifiers");
        }
        denied(() -> parse(event("error", Json.obj("code", "subscription_sharing_usage_limit_exceeded", "message", SECRET))), "top-level error event");
        JSONObject unfinished = response(); Json.put(unfinished, "status", "in_progress");
        denied(() -> parse(event("response.completed", Json.obj("response", unfinished))), "false completed envelope");
        JSONObject withError = response(); Json.put(withError, "error", Json.obj("code", "subscription_sharing_usage_limit_exceeded", "message", SECRET));
        denied(() -> parse(event("response.completed", Json.obj("response", withError))), "completed containing an error");
        JSONObject empty = parse(event("response.completed", Json.obj("response", Json.obj("status", "completed"))));
        eq(Json.arr(empty, "output").length(), 0);
        try { SpeechAnalyzer.responseMetadata(empty); throw new AssertionError("empty terminal must not become analysis metadata"); }
        catch (IOException expected) { checks++; }
        denied(() -> parse(event("response.created", Json.obj("response", Json.obj("id", "resp_other"))) + completed()), "different response identity");
        // Global sequence metadata is not used for resumption or to assemble output. Item IDs and
        // the completed event carry the integrity checks, as in the official SDK accumulator.
        for (Object value : new Object[]{-1, 1.5, "2"})
            eq(parse(event("response.output_text.delta", Json.obj("sequence_number", value)) + completed()).optString("status"), "completed");
        eq(parse(event("response.created", Json.obj("sequence_number", 3, "response", Json.obj("id", "resp_fixture")))
                + event("response.completed", Json.obj("sequence_number", 3, "response", response()))).optString("status"), "completed");
        denied(() -> parse("event: response.completed\ndata: {\"type\":\"response.failed\"}\n\n"), "conflicting SSE and JSON event types");
        for (String json : new String[]{"{", "[]", "{\"type\":\"response.completed\"} trailing", "{}"})
            denied(() -> parse("data: " + json + "\n\n"), "invalid SSE JSON");
        denied(() -> parse("data: " + Json.obj("type", "response.completed", "response", response()) + "\u0000discarded-by-lenient-parser\n\n"), "literal NUL cannot hide trailing data");
        denied(() -> ChatGptResponses.readCompleted(new ByteArrayInputStream(new byte[]{(byte)0xc3, (byte)0x28}), () -> false, "req_fixture"), "invalid UTF8");
        char[] huge = new char[ChatGptResponses.MAX_EVENT_CHARS + 1]; Arrays.fill(huge, 'a');
        eq(denied(() -> parse("data:" + new String(huge) + "\n\n"), "overlong event line").code, "response_too_large");
        // Bound the whole stream even when it consists entirely of small keep-alive lines.
        InputStream endless = new InputStream() { int n; @Override public int read() { return (n++ % 2) == 0 ? ':' : '\n'; } };
        eq(denied(() -> ChatGptResponses.readCompleted(endless, () -> false, ""), "unbounded keepalive stream").code, "response_too_large");
    }

    private static class Fake extends HttpsURLConnection {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        InputStream input = new ByteArrayInputStream(bytes(completed())), error;
        int status = 200; String type = "text/event-stream; charset=utf-8", requestId = "req_fixture";
        boolean disconnected; long length = -1;
        Fake(URL url) { super(url); }
        @Override public OutputStream getOutputStream() { return body; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return error; }
        @Override public int getResponseCode() { return status; }
        @Override public String getContentType() { return type; }
        @Override public String getHeaderField(String name) { return "x-request-id".equalsIgnoreCase(name) ? requestId : null; }
        @Override public long getContentLengthLong() { return length; }
        @Override public void disconnect() { disconnected = true; try { input.close(); } catch (IOException ignored) { } }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "fixture"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }
    private static void transport() throws Exception {
        final Fake[] connection = {null}; final int[] opens = {0};
        ChatGptResponses transport = new ChatGptResponses(url -> { opens[0]++; return connection[0] = new Fake(url); });
        JSONObject completed = transport.send(request(), TOKEN, () -> false);
        eq(completed.optString("status"), "completed"); eq(opens[0], 1);
        Fake http = connection[0];
        eq(http.getURL().toString(), "https://api.openai.com/v1/responses"); yes(!http.getURL().toString().contains(TOKEN), "credential not in URL");
        eq(http.getRequestProperty("Authorization"), "Bearer " + TOKEN); eq(http.getRequestProperty("Accept"), "text/event-stream");
        eq(http.getRequestMethod(), "POST"); yes(!http.getInstanceFollowRedirects() && !http.getUseCaches(), "no redirects/cache");
        yes(http.disconnected, "connection closed on success");
        JSONObject posted = new JSONObject(new String(http.body.toByteArray(), StandardCharsets.UTF_8));
        yes(posted.optBoolean("stream") && !posted.optBoolean("store") && !posted.has("max_output_tokens"), "actual HTTP body uses plan contract");
        yes(!posted.toString().contains(TOKEN), "credential separate from prompt");
        for (int code : new int[]{301, 302, 400, 401, 403, 429, 500, 503}) {
            final int[] requests = {0};
            ChatGptResponses unavailable = new ChatGptResponses(url -> {
                requests[0]++; Fake f = new Fake(url); f.status = code; f.error = new ByteArrayInputStream(bytes(Json.obj("detail", SECRET + TOKEN).toString())); return f;
            });
            ChatGptResponses.Failure failure = denied(() -> unavailable.send(request(), TOKEN, () -> false), "HTTP " + code);
            eq(failure.httpStatus, code); eq(failure.responseShape, "detail"); eq(requests[0], 1);
        }
        ChatGptResponses structured = new ChatGptResponses(url -> {
            Fake f = new Fake(url); f.status = 429; f.error = new ByteArrayInputStream(bytes(Json.obj("error", Json.obj("code", "subscription_sharing_usage_limit_exceeded", "message", SECRET, "param", "model")).toString())); return f;
        });
        ChatGptResponses.Failure quota = denied(() -> structured.send(request(), TOKEN, () -> false), "structured quota before stream");
        eq(quota.code, "subscription_sharing_usage_limit_exceeded"); eq(quota.param, "model"); eq(quota.responseShape, "error");
        for (String type : new String[]{"application/json", "text/html", "text/plain", "application/octet-stream", "TEXT/EVENT-STREAM ; charset=UTF-8", "application/unknown", "", null}) {
            ChatGptResponses wrongType = new ChatGptResponses(url -> { Fake f = new Fake(url); f.type = type; return f; });
            eq(wrongType.send(request(), TOKEN, () -> false).optString("status"), "completed");
        }
        ChatGptResponses oversized = new ChatGptResponses(url -> { Fake f = new Fake(url); f.length = ChatGptResponses.MAX_STREAM_BYTES + 1L; return f; });
        eq(denied(() -> oversized.send(request(), TOKEN, () -> false), "response content length").code, "response_too_large");
        for (String token : new String[]{"", "bad\nheader", "has space", "bad\u0000value"})
            denied(() -> transport.send(request(), token, () -> false), "invalid credential header");
        eq(opens[0], 1); // Invalid credentials fail before even opening a transport.
        ChatGptResponses broken = new ChatGptResponses(url -> {
            return new Fake(url) { @Override public InputStream getInputStream() { return new InputStream() { @Override public int read() throws IOException { throw new IOException(TOKEN + SECRET); } }; } };
        });
        denied(() -> broken.send(request(), TOKEN, () -> false), "exception containing secret");
    }

    private static JSONObject payload(String content, String mediaType) throws Exception {
        Fake fake = new Fake(new URL(ChatGptResponses.ENDPOINT)); fake.type = mediaType;
        fake.input = new ByteArrayInputStream(bytes(content));
        final int[] opens = {0};
        ChatGptResponses transport = new ChatGptResponses(url -> { opens[0]++; return fake; });
        try { return transport.send(request(), TOKEN, () -> false); }
        finally {
            eq(opens[0], 1); yes(fake.disconnected, "payload parsing closes exactly the original request");
            JSONObject posted = new JSONObject(new String(fake.body.toByteArray(), StandardCharsets.UTF_8));
            yes(posted.optBoolean("stream") && !posted.optBoolean("store"), "JSON compatibility never changes the SIWC request contract");
            eq(posted.optString("model"), "fixture-account-model");
        }
    }
    private static JSONObject fullResponse() {
        JSONObject value = response(); Json.put(value, "object", "response");
        Json.put(value, "error", JSONObject.NULL); Json.put(value, "incomplete_details", JSONObject.NULL);
        return value;
    }
    private static ChatGptResponses.Failure payloadDenied(String content, String mediaType, String format) throws Exception {
        ChatGptResponses.Failure failure = denied(() -> payload(content, mediaType), "unconfirmed payload");
        eq(failure.httpStatus, 200); eq(failure.bodyFormat, format); eq(failure.diagnostics().optString("bodyFormat"), format);
        yes(!failure.diagnostics().toString().contains(TOKEN) && !failure.diagnostics().toString().contains(SECRET), "body and raw headers never enter diagnostics");
        yes(failure.getCause() == null && failure.getSuppressed().length == 0, "payload diagnostics have no raw exception attached");
        return failure;
    }
    private static void payloadCompatibility() throws Exception {
        eq(payload("\ufeff\r\nunknown-field-without-colon\nfuture: ignored\n: keepalive\r\n" + completed(), null).optString("id"), "resp_fixture");
        for (String mediaType : new String[]{"application/json; charset=utf-8", "text/plain", "text/event-stream", null}) {
            JSONObject full = payload(fullResponse().toString(), mediaType);
            eq(full.optString("id"), "resp_fixture"); eq(SpeechAnalyzer.responseMetadata(full).optJSONArray("assignments").length(), 0);
            JSONObject envelope = payload(Json.obj("type", "response.completed", "response", response()).toString(), mediaType);
            eq(envelope.optJSONObject("usage").optInt("output_tokens"), 3);
        }
        eq(payload("\ufeff \n" + fullResponse().toString(2) + "\r\n", "application/json").optString("object"), "response");

        for (String mediaType : new String[]{"text/html; parameter=" + TOKEN, "text/event-stream", null, "application/" + TOKEN}) {
            ChatGptResponses.Failure html = payloadDenied(" \r\n<!doctype html><html>" + SECRET + TOKEN + "</html>\n" + completed(), mediaType, "html");
            eq(html.code, "invalid_stream"); eq(html.stage, "body_format"); eq(html.responseShape, "html");
            eq(html.mediaType, mediaType == null ? "missing" : mediaType.startsWith("text/html") ? "text/html" : mediaType.startsWith("text/event") ? "text/event-stream" : "other");
        }
        eq(payloadDenied("upstream gateway failed " + SECRET, "text/plain", "unknown").code, "invalid_stream");
        eq(payloadDenied(" \r\n\t", "application/json", "empty").code, "stream_interrupted");

        JSONObject missingOutput = fullResponse(); missingOutput.remove("output");
        JSONObject nullOutput = fullResponse(); Json.put(nullOutput, "output", JSONObject.NULL);
        JSONObject noId = fullResponse(); noId.remove("id");
        JSONObject badId = fullResponse(); Json.put(badId, "id", "not_a_response_id");
        JSONObject idOnly = Json.obj("object", "response", "id", "resp_fixture", "status", "completed");
        JSONObject conflictingType = fullResponse(); Json.put(conflictingType, "type", "response.output_text.delta");
        for (JSONObject invalid : new JSONObject[]{Json.obj("cast", new JSONArray(), "assignments", new JSONArray()),
                Json.obj("status", "completed", "output", Json.arr(response(), "output")), Json.obj("type", "response.completed"),
                Json.obj("type", "response.output_text.delta", "delta", "{}"), response(), missingOutput, nullOutput, noId, badId, idOnly, conflictingType,
                Json.obj("type", "response.completed", "response", missingOutput), Json.obj("type", "response.completed", "response", nullOutput)})
            eq(payloadDenied(invalid.toString(), "application/json", "json").code, "invalid_stream");

        JSONObject pending = fullResponse(); Json.put(pending, "status", "in_progress");
        eq(payloadDenied(pending.toString(), "application/json", "json").code, "response_incomplete");
        JSONObject incomplete = fullResponse(); Json.put(incomplete, "incomplete_details", Json.obj("reason", "max_output_tokens"));
        eq(payloadDenied(incomplete.toString(), "application/json", "json").stage, "incomplete_details");
        JSONObject quota = Json.obj("code", "subscription_sharing_usage_limit_exceeded", "message", SECRET + TOKEN, "param", "model");
        for (JSONObject error : new JSONObject[]{Json.obj("error", quota), Json.obj("type", "error", "error", quota),
                Json.obj("type", "response.failed", "response", Json.obj("status", "failed", "error", quota)),
                Json.obj("type", "response.completed", "response", Json.merge(fullResponse(), Json.obj("error", quota)))}) {
            ChatGptResponses.Failure failure = payloadDenied(error.toString(), "application/problem+json", "json");
            eq(failure.code, "subscription_sharing_usage_limit_exceeded"); eq(failure.mediaType, "application/problem+json");
        }

        JSONObject unfinishedMessage = fullResponse(); Json.put(Json.arr(unfinishedMessage, "output").optJSONObject(0), "status", "in_progress");
        JSONObject refusal = fullResponse(); Json.put(Json.arr(refusal, "output").optJSONObject(0), "content",
                new JSONArray().put(Json.obj("type", "refusal", "refusal", "fixture refusal")));
        for (JSONObject noMetadata : new JSONObject[]{unfinishedMessage, refusal, Json.merge(fullResponse(), Json.obj("output", new JSONArray()))}) {
            JSONObject received = payload(noMetadata.toString(), "application/json");
            try { SpeechAnalyzer.responseMetadata(received); throw new AssertionError("unfinished/refusal/empty JSON must not become analysis metadata"); }
            catch (IOException expected) { checks++; }
        }

        String full = fullResponse().toString();
        for (String malformed : new String[]{full + " trailing", full + full, full + "\u0000", "{'object':'response'}", "{object:\"response\"}",
                "{\"object\":\"response\",}", "{\"status\":\"failed\",\"status\":\"completed\"}",
                "{\"status\":\"failed\",\"\\u0073tatus\":\"completed\"}", "{\"x\":NaN}", "{\"x\":01}", "{\"x\":1.}",
                "{\"x\":1e}", "{\"x\":\"unescaped\ncontrol\"}", "{\"x\":\"\\x20\"}", "{\"x\":[1,]}"})
            eq(payloadDenied(malformed, "application/json", "json").stage, "json_syntax");
        String nested = "0"; for (int i = 0; i < 130; i++) nested = "[" + nested + "]";
        eq(payloadDenied("{\"extra\":" + nested + "}", "application/json", "json").stage, "json_syntax");
        JSONObject numberForms = fullResponse(); Json.put(numberForms, "fixture", new JSONObject("{\"a\":-0.125e+2,\"b\":true,\"c\":false,\"d\":null,\"e\":\"\\u3042\\n\\t\\b\\f\\r\\/\\\\\\\"\"}"));
        eq(payload(numberForms.toString(), "application/json").optJSONObject("fixture").optString("e"), "あ\n\t\b\f\r/\\\"");

        char[] prefix = new char[ChatGptResponses.MAX_SNIFF_CHARS]; Arrays.fill(prefix, 'x');
        eq(payloadDenied(new String(prefix) + "\n" + completed(), null, "unknown").stage, "body_format");
        char[] oversized = new char[ChatGptResponses.MAX_STREAM_BYTES]; Arrays.fill(oversized, 'a');
        eq(payloadDenied("{\"padding\":\"" + new String(oversized) + "\"}", "application/json", "json").code, "response_too_large");
        ChatGptResponses.Failure legacy = new ChatGptResponses.Failure("invalid_stream", 200, "", "", "sse", "json");
        eq(legacy.mediaType, ""); eq(legacy.bodyFormat, "");
        ChatGptResponses.Failure unsafe = new ChatGptResponses.Failure("invalid_stream", 200, "", "", "json", "json_syntax", TOKEN, SECRET);
        eq(unsafe.mediaType, ""); eq(unsafe.bodyFormat, "");
    }
    private static void cancellation() throws Exception {
        final int[] opens = {0}; ChatGptResponses before = new ChatGptResponses(url -> { opens[0]++; return new Fake(url); });
        eq(denied(() -> before.send(request(), TOKEN, () -> true), "cancelled before connection").code, "cancelled"); eq(opens[0], 0);
        AtomicBoolean cancel = new AtomicBoolean(); CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        InputStream waiting = new InputStream() {
            @Override public int read() throws IOException { entered.countDown(); try { released.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } throw new IOException("fixture closed"); }
            @Override public void close() { released.countDown(); }
        };
        Fake fake = new Fake(new URL(ChatGptResponses.ENDPOINT)); fake.input = waiting;
        ChatGptResponses transport = new ChatGptResponses(url -> fake);
        AtomicReference<Throwable> result = new AtomicReference<>(); Thread thread = new Thread(() -> {
            try { transport.send(request(), TOKEN, cancel::get); result.set(new AssertionError("accepted cancellation")); }
            catch (Throwable e) { result.set(e); }
        });
        thread.start(); yes(entered.await(2, TimeUnit.SECONDS), "request entered blocking read"); cancel.set(true);
        thread.join(3000); yes(!thread.isAlive() && fake.disconnected, "cancellation disconnects blocked stream");
        yes(result.get() instanceof ChatGptResponses.Failure, "safe cancellation exception");
        eq(((ChatGptResponses.Failure) result.get()).code, "cancelled");
    }
}
