package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;

/**
 * Production HTTP/SSE failure classification with synthetic transports only.
 * No API connection, real bearer token, browser, or inference charge is used.
 */
public final class ChatGptTransportFailureTest {
    private static final String TOKEN = "FIXTURE_ONLY_TRANSPORT_BEARER_NOT_A_CREDENTIAL";
    private static final String PRIVATE = "FIXTURE_PRIVATE_PROVIDER_DIAGNOSTIC";
    private static final String REQUEST_ID = "req_fixture_transport";
    private static int checks;

    private interface Run { void run() throws Exception; }
    private interface ErrorFactory { IOException create(); }
    private static void yes(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static void eq(Object actual, Object expected) {
        checks++; if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject request() {
        return Json.obj("model", "fixture-model", "store", false, "stream", true,
                "input", new JSONArray().put(Json.obj("role", "user", "content", "自作の通信試験。")));
    }
    private static String event(String type, JSONObject body) {
        return "event: " + type + "\ndata: " + Json.merge(Json.obj("type", type), body) + "\n\n";
    }
    private static String unfinishedPrefix() {
        return event("response.created", Json.obj("sequence_number", 0,
                "response", Json.obj("id", "resp_fixture", "status", "in_progress", "output", new JSONArray())))
                + event("response.output_text.delta", Json.obj("sequence_number", 1,
                "delta", "{\"cast\":[],\"assignments\":[]}"));
    }
    private static void publicOnly(ChatGptResponses.Failure failure) {
        StringWriter stack = new StringWriter(); failure.printStackTrace(new PrintWriter(stack));
        for (String value : new String[]{failure.getMessage(), failure.toString(), failure.diagnostics().toString(), stack.toString()}) {
            yes(!value.contains(TOKEN), "published failure never exposes bearer");
            yes(!value.contains(PRIVATE), "published failure never exposes raw provider/transport text");
            yes(!value.contains("\"assignments\""), "published failure never exposes partial model output");
        }
        JSONObject diagnostics = failure.diagnostics();
        eq(diagnostics.optString("provider"), "chatgpt");
        eq(diagnostics.optString("code"), failure.code);
        eq(diagnostics.optInt("httpStatus"), failure.httpStatus);
        eq(diagnostics.optString("param"), failure.param);
        eq(diagnostics.optString("requestId"), failure.requestId);
        eq(diagnostics.optString("responseShape"), failure.responseShape);
        eq(diagnostics.optString("mediaType"), failure.mediaType);
        eq(diagnostics.optString("bodyFormat"), failure.bodyFormat);
        yes(failure.mediaType.isEmpty() || failure.mediaType.matches("text/event-stream|application/json|application/problem\\+json|application/x-ndjson|application/json-seq|text/plain|text/html|application/xhtml\\+xml|application/octet-stream|missing|other"), "media type is a fixed category, never an arbitrary header");
        yes(failure.bodyFormat.isEmpty() || failure.bodyFormat.matches("sse|json|html|empty|unknown"), "payload format is a fixed category");
        yes(diagnostics.has("stage"), "diagnostics include the bounded stage identifier");
        String stage = diagnostics.optString("stage");
        yes(stage.isEmpty() || stage.matches("[a-z][a-z0-9_]{0,63}"), "stage remains a bounded local identifier");
    }
    private static ChatGptResponses.Failure fails(Run action) throws Exception {
        try { action.run(); throw new AssertionError("an unfinished response was accepted"); }
        catch (ChatGptResponses.Failure expected) { checks++; publicOnly(expected); return expected; }
    }

    private static class FakeConnection extends HttpsURLConnection {
        final ByteArrayOutputStream posted = new ByteArrayOutputStream();
        final InputStream input;
        volatile boolean disconnected;
        String requestId = REQUEST_ID;
        String mediaType = "text/event-stream; charset=utf-8";
        FakeConnection(InputStream input) throws Exception { super(new URL(ChatGptResponses.ENDPOINT)); this.input = input; }
        @Override public OutputStream getOutputStream() throws IOException { return posted; }
        @Override public InputStream getInputStream() throws IOException { return input; }
        @Override public InputStream getErrorStream() { return null; }
        @Override public int getResponseCode() throws IOException { return 200; }
        @Override public String getContentType() { return mediaType; }
        @Override public String getHeaderField(String name) { return "x-request-id".equalsIgnoreCase(name) ? requestId : null; }
        @Override public long getContentLengthLong() { return -1; }
        @Override public void disconnect() { disconnected = true; try { input.close(); } catch (IOException ignored) { } }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "fixture"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }
    private static final class FailsAfterPrefix extends InputStream {
        private final byte[] prefix;
        private final ErrorFactory failure;
        private int position;
        boolean closed;
        FailsAfterPrefix(byte[] prefix, ErrorFactory failure) { this.prefix = prefix; this.failure = failure; }
        @Override public int read() throws IOException {
            if (position < prefix.length) return prefix[position++] & 255;
            throw failure.create();
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (position == prefix.length) throw failure.create();
            int count = Math.min(length, prefix.length - position);
            System.arraycopy(prefix, position, buffer, offset, count); position += count; return count;
        }
        @Override public void close() { closed = true; }
    }
    private static ChatGptResponses.Failure sendFailure(FakeConnection connection, String code, int status) throws Exception {
        AtomicInteger opened = new AtomicInteger();
        ChatGptResponses sender = new ChatGptResponses(url -> { opened.incrementAndGet(); return connection; });
        ChatGptResponses.Failure result = fails(() -> sender.send(request(), TOKEN, () -> false));
        eq(result.code, code); eq(result.httpStatus, status);
        eq(opened.get(), 1); yes(connection.disconnected, "failure closes the original connection");
        eq(connection.getRequestMethod(), "POST");
        eq(connection.getRequestProperty("Authorization"), "Bearer " + TOKEN);
        eq(connection.getRequestProperty("Accept"), "text/event-stream");
        yes(!connection.getInstanceFollowRedirects(), "transport never follows an auth redirect");
        yes(!new String(connection.posted.toByteArray(), StandardCharsets.UTF_8).contains(TOKEN), "bearer stays outside JSON request");
        return result;
    }

    public static void main(String[] args) throws Exception {
        timeoutAndReset(); malformedUtf8(); eofIsNotSuccess(); preConnectionFailures(); preHeaderFailures();
        closeFailureSanitization(); completionSurvivesCloseFailure(); payloadWithoutEof(); requestIdSanitization(); cancellation();
        System.out.println("ChatGptTransportFailure production checks passed: " + checks);
        System.out.println("Scope: real ChatGptResponses.send/readCompleted; synthetic HTTPS connection and byte/exception/latch streams.");
        System.out.println("Timeout, reset, UTF-8, partial-output EOF and cancellation remain distinct; no automatic retry and no raw exception or bearer exposure.");
        System.out.println("No Android device, browser, live account/API, network request or inference charge was used.");
    }
    private static void timeoutAndReset() throws Exception {
        for (String prefix : new String[]{"", unfinishedPrefix(), "{\"type\":\"response.completed\",\"response\":"}) {
            FailsAfterPrefix timeout = new FailsAfterPrefix(utf8(prefix), () -> new SocketTimeoutException(PRIVATE + TOKEN));
            FakeConnection timeoutConnection = new FakeConnection(timeout); timeoutConnection.mediaType = "text/plain; ignored=" + TOKEN;
            ChatGptResponses.Failure timeoutResult = sendFailure(timeoutConnection, "timeout", 200);
            eq(timeoutResult.responseShape, "transport"); eq(timeoutResult.requestId, REQUEST_ID);
            eq(timeoutResult.diagnostics().optString("stage"), "read_timeout");
            eq(timeoutResult.mediaType, "text/plain");
            eq(timeoutResult.bodyFormat, prefix.isEmpty() ? "unknown" : prefix.startsWith("{") ? "json" : "sse");
            yes(timeoutResult.getMessage().contains("タイムアウト"), "timeout has an actionable timeout message");
            yes(timeout.closed, "timed-out stream is closed");

            FailsAfterPrefix reset = new FailsAfterPrefix(utf8(prefix), () -> new IOException(PRIVATE + TOKEN));
            FakeConnection resetConnection = new FakeConnection(reset); resetConnection.mediaType = null;
            ChatGptResponses.Failure resetResult = sendFailure(resetConnection, "network_error", 200);
            eq(resetResult.responseShape, "transport"); eq(resetResult.requestId, REQUEST_ID);
            eq(resetResult.diagnostics().optString("stage"), "io");
            eq(resetResult.mediaType, "missing");
            eq(resetResult.bodyFormat, prefix.isEmpty() ? "unknown" : prefix.startsWith("{") ? "json" : "sse");
            yes(!resetResult.getMessage().contains("応答形式"), "TCP failure is not a format failure");
            yes(reset.closed, "reset stream is closed");
        }
    }
    private static void malformedUtf8() throws Exception {
        for (String prefix : new String[]{"", unfinishedPrefix()}) {
            byte[] before = utf8(prefix), content = new byte[before.length + 2];
            System.arraycopy(before, 0, content, 0, before.length);
            content[before.length] = (byte)0xc3; content[before.length + 1] = (byte)0x28;
            ChatGptResponses.Failure failure = sendFailure(new FakeConnection(new ByteArrayInputStream(content)), "invalid_stream", 200);
            eq(failure.requestId, REQUEST_ID); eq(failure.diagnostics().optString("stage"), "utf8");
        }
    }
    private static void eofIsNotSuccess() throws Exception {
        for (String input : new String[]{unfinishedPrefix(), unfinishedPrefix() + "data: [DONE]\n\n"}) {
            ChatGptResponses.Failure failure = sendFailure(new FakeConnection(new ByteArrayInputStream(utf8(input))), "stream_interrupted", 200);
            eq(failure.requestId, REQUEST_ID);
        }
    }
    private static void preConnectionFailures() throws Exception {
        for (boolean timeout : new boolean[]{true, false}) {
            AtomicInteger opened = new AtomicInteger();
            ChatGptResponses sender = new ChatGptResponses(url -> {
                opened.incrementAndGet();
                if (timeout) throw new SocketTimeoutException(PRIVATE + TOKEN);
                throw new IOException(PRIVATE + TOKEN);
            });
            ChatGptResponses.Failure failure = fails(() -> sender.send(request(), TOKEN, () -> false));
            eq(failure.code, "network_error"); eq(failure.httpStatus, 0);
            eq(failure.responseShape, "transport"); eq(failure.requestId, ""); eq(opened.get(), 1);
            eq(failure.diagnostics().optString("stage"), "connect");
        }
    }
    private static void preHeaderFailures() throws Exception {
        for (boolean timeout : new boolean[]{true, false}) {
            FakeConnection connection = new FakeConnection(new ByteArrayInputStream(new byte[0])) {
                @Override public int getResponseCode() throws IOException {
                    if (timeout) throw new SocketTimeoutException(PRIVATE + TOKEN);
                    throw new IOException(PRIVATE + TOKEN);
                }
            };
            ChatGptResponses.Failure failure = sendFailure(connection, timeout ? "timeout" : "network_error", 0);
            eq(failure.responseShape, "transport"); eq(failure.requestId, "");
        }
    }
    private static void requestIdSanitization() throws Exception {
        FakeConnection connection = new FakeConnection(new FailsAfterPrefix(utf8(unfinishedPrefix()), () -> new IOException(PRIVATE + TOKEN)));
        connection.requestId = "req_not_safe " + TOKEN + PRIVATE;
        ChatGptResponses.Failure failure = sendFailure(connection, "network_error", 200);
        eq(failure.requestId, "");
    }
    private static void closeFailureSanitization() throws Exception {
        for (boolean invalidUtf8 : new boolean[]{false, true}) {
            byte[] content = invalidUtf8 ? new byte[]{(byte)0xc3, (byte)0x28} : utf8(unfinishedPrefix());
            InputStream stream = new ByteArrayInputStream(content) {
                @Override public void close() throws IOException { throw new IOException(PRIVATE + TOKEN); }
            };
            ChatGptResponses.Failure failure = sendFailure(new FakeConnection(stream),
                    invalidUtf8 ? "invalid_stream" : "stream_interrupted", 200);
            eq(failure.requestId, REQUEST_ID);
            eq(failure.diagnostics().optString("stage"), invalidUtf8 ? "utf8" : "eof");
            yes(failure.getCause() == null, "raw close exception is not the public failure cause");
            eq(failure.getSuppressed().length, 0);
        }
    }
    private static void completionSurvivesCloseFailure() throws Exception {
        String finalText = "{\"cast\":[],\"assignments\":[]}";
        JSONObject response = Json.obj("id", "resp_fixture", "status", "completed", "output",
                new JSONArray().put(Json.obj("id", "msg_fixture", "type", "message", "role", "assistant",
                        "status", "completed", "content", new JSONArray().put(Json.obj("type", "output_text", "text", finalText)))));
        AtomicInteger closes = new AtomicInteger();
        InputStream stream = new ByteArrayInputStream(utf8(event("response.completed", Json.obj("response", response)))) {
            @Override public void close() throws IOException { closes.incrementAndGet(); throw new IOException(PRIVATE + TOKEN); }
        };
        FakeConnection connection = new FakeConnection(stream); AtomicInteger opened = new AtomicInteger();
        ChatGptResponses sender = new ChatGptResponses(url -> { opened.incrementAndGet(); return connection; });
        JSONObject result = sender.send(request(), TOKEN, () -> false);
        eq(result.optString("id"), "resp_fixture"); eq(result.optString("status"), "completed");
        eq(result.getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"), finalText);
        yes(closes.get() > 0, "completed-response stream cleanup was attempted");
        yes(connection.disconnected, "completed request disconnects even if close fails"); eq(opened.get(), 1);
    }
    private static void payloadWithoutEof() throws Exception {
        JSONObject full = Json.obj("id", "resp_fixture", "object", "response", "status", "completed", "output",
                new JSONArray().put(Json.obj("id", "msg_fixture", "type", "message", "status", "completed", "content",
                        new JSONArray().put(Json.obj("type", "output_text", "text", "{\"cast\":[],\"assignments\":[]}")))));
        FailsAfterPrefix completed = new FailsAfterPrefix(utf8(event("response.completed", Json.obj("response", full))),
                () -> new SocketTimeoutException(PRIVATE + TOKEN));
        FakeConnection sse = new FakeConnection(completed); sse.mediaType = "application/json";
        JSONObject accepted = new ChatGptResponses(url -> sse).send(request(), TOKEN, () -> false);
        eq(accepted.optString("id"), "resp_fixture");
        yes(sse.disconnected && completed.closed, "SSE completion does not wait for EOF despite a JSON MIME header");

        // A JSON body must reach EOF so an appended second response or failure cannot be hidden.
        FailsAfterPrefix json = new FailsAfterPrefix(utf8(full.toString()), () -> new SocketTimeoutException(PRIVATE + TOKEN));
        FakeConnection unfinishedJson = new FakeConnection(json); unfinishedJson.mediaType = "application/json";
        ChatGptResponses.Failure timeout = sendFailure(unfinishedJson, "timeout", 200);
        eq(timeout.bodyFormat, "json"); eq(timeout.stage, "read_timeout");
        yes(json.closed, "unterminated JSON transport is closed without adopting the model output");
    }
    private static void cancellation() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        ChatGptResponses before = new ChatGptResponses(url -> { opened.incrementAndGet(); throw new IOException("should not connect"); });
        eq(fails(() -> before.send(request(), TOKEN, () -> true)).code, "cancelled"); eq(opened.get(), 0);
        for (String prefix : new String[]{"", unfinishedPrefix(), "{\"type\":\"response.completed\",\"response\":"}) {
            AtomicBoolean cancel = new AtomicBoolean();
            CountDownLatch waiting = new CountDownLatch(1), released = new CountDownLatch(1);
            InputStream stream = new InputStream() {
                final byte[] bytes = utf8(prefix); int position;
                @Override public int read() throws IOException {
                    if (position < bytes.length) return bytes[position++] & 255;
                    waiting.countDown();
                    try { if (!released.await(4, TimeUnit.SECONDS)) throw new IOException("fixture wait expired"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new SocketTimeoutException(PRIVATE + TOKEN);
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    if (position == bytes.length) return read();
                    int count = Math.min(length, bytes.length - position);
                    System.arraycopy(bytes, position, buffer, offset, count); position += count; return count;
                }
                @Override public void close() { released.countDown(); }
            };
            FakeConnection connection = new FakeConnection(stream); AtomicInteger requests = new AtomicInteger();
            ChatGptResponses sender = new ChatGptResponses(url -> { requests.incrementAndGet(); return connection; });
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { sender.send(request(), TOKEN, cancel::get); result.set(new AssertionError("cancelled partial output accepted")); }
                catch (Throwable error) { result.set(error); }
            }, "fixture-cancel-transport");
            worker.start();
            try {
                yes(waiting.await(2, TimeUnit.SECONDS), "body read has reached the cancellation latch");
                cancel.set(true); worker.join(3000);
                yes(!worker.isAlive(), "cancel disconnects a blocked read promptly");
                yes(result.get() instanceof ChatGptResponses.Failure, "cancellation is a public classified failure");
                ChatGptResponses.Failure failure = (ChatGptResponses.Failure)result.get();
                eq(failure.code, "cancelled"); publicOnly(failure);
                eq(requests.get(), 1); yes(connection.disconnected, "cancel closes exactly the original request");
            } finally {
                cancel.set(true); stream.close(); worker.interrupt(); worker.join(1500);
            }
        }
    }
}
