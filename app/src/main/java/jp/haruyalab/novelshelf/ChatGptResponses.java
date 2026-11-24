package jp.haruyalab.novelshelf;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackReader;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Official Sign in with ChatGPT Responses route. Partial deltas never become an analysis checkpoint. */
public final class ChatGptResponses implements SpeechAnalyzer.Transport {
    static final String ENDPOINT = "https://api.openai.com/v1/responses";
    static final int MAX_STREAM_BYTES = 8 * 1024 * 1024;
    static final int MAX_EVENT_CHARS = 2 * 1024 * 1024;
    static final int MAX_ERROR_BYTES = 64 * 1024;
    static final int MAX_SNIFF_CHARS = 8192;
    static final long DEADLINE_MS = 180000;
    interface ConnectionFactory { HttpsURLConnection open(URL url) throws IOException; }
    private final ConnectionFactory connections;
    public ChatGptResponses() { this(url -> (HttpsURLConnection) url.openConnection()); }
    ChatGptResponses(ConnectionFactory connections) { this.connections = connections; }

    /** Only the documented HTTP plan-usage contract is sent, without API-key-only options. */
    static JSONObject accountRequest(JSONObject original) throws IOException {
        JSONObject result = Json.copy(original);
        for (String field : new String[]{"background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation",
                "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p",
                "truncation", "user", "previous_response_id"}) result.remove(field);
        Json.put(result, "store", false); Json.put(result, "stream", true);
        JSONArray input = result.optJSONArray("input");
        if (input == null || input.length() == 0) throw failure("invalid_request", 0, "", "", "local");
        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.optJSONObject(i);
            if (item == null || "system".equals(item.optString("role"))) throw failure("invalid_request", 0, "", "", "local");
        }
        return result;
    }

    @Override public JSONObject send(JSONObject request, String accessToken, SpeechAnalyzer.Cancel cancel) throws Exception {
        check(cancel);
        if (accessToken == null || accessToken.isEmpty() || accessToken.length() > 65536
                || !accessToken.matches("[\\x21-\\x7e]+")) throw failure("invalid_credential", 0, "", "", "local");
        JSONObject body = accountRequest(request);
        final HttpsURLConnection connection;
        try { connection = connections.open(new URL(ENDPOINT)); }
        catch (Exception unavailable) { check(cancel); throw failure("network_error", 0, "", "", "transport", "connect"); }
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean timedOut = new AtomicBoolean();
        final Thread requester = Thread.currentThread();
        final long started = System.nanoTime();
        Thread watcher = new Thread(() -> {
            while (!finished.get()) {
                if (requester.isInterrupted() || cancel != null && cancel.isCancelled()) { connection.disconnect(); return; }
                if ((System.nanoTime() - started) / 1000000L >= DEADLINE_MS) {
                    timedOut.set(true); connection.disconnect(); return;
                }
                try { Thread.sleep(100); } catch (InterruptedException stopped) { return; }
            }
        }, "TsuzukiChatGptResponseCancel");
        watcher.setDaemon(true); watcher.start();
        String requestId = ""; int responseStatus = 0;
        BodyInfo bodyInfo = new BodyInfo();
        try {
            check(cancel);
            connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000); connection.setReadTimeout(120000);
            connection.setUseCaches(false); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(encoded.length);
            check(cancel);
            try (OutputStream out = connection.getOutputStream()) { out.write(encoded); }
            check(cancel);
            int status = connection.getResponseCode();
            responseStatus = status;
            requestId = safeRequestId(connection.getHeaderField("x-request-id"));
            bodyInfo.mediaType = mediaType(connection.getContentType());
            check(cancel);
            if (status != 200) throw httpFailure(connection, status, requestId, cancel, bodyInfo);
            if (connection.getContentLengthLong() > MAX_STREAM_BYTES) throw failure("response_too_large", status, "", requestId, "unknown", "content_length");
            JSONObject completed;
            InputStream in = connection.getInputStream();
            try { completed = readPayload(in, cancel, requestId, bodyInfo); }
            finally { try { in.close(); } catch (IOException ignored) { } }
            check(cancel);
            if (timedOut.get()) throw failure("timeout", status, "", requestId, "transport", "deadline");
            return completed;
        } catch (Exception error) {
            check(cancel);
            if (timedOut.get() || error instanceof SocketTimeoutException)
                throw withBody(failure("timeout", responseStatus, "", requestId, "transport", timedOut.get() ? "deadline" : "read_timeout"), bodyInfo);
            if (error instanceof Failure) {
                Failure safe = (Failure) error;
                // A cleanup failure must not attach a raw exception/body to a public diagnostic.
                throw withBody(safe, bodyInfo);
            }
            // Neither exception text nor remote bodies can become UI text or persistent diagnostics.
            throw withBody(failure("network_error", responseStatus, "", requestId, "transport", "io"), bodyInfo);
        } finally { finished.set(true); watcher.interrupt(); connection.disconnect(); }
    }

    /** Read a bounded SSE stream through its terminal event; ignore all uncommitted output deltas. */
    static JSONObject readCompleted(InputStream source, SpeechAnalyzer.Cancel cancel, String requestId) throws IOException {
        return readSse(reader(source, 1), cancel, requestId);
    }

    private static PushbackReader reader(InputStream source, int pushback) {
        InputStream bounded = new LimitedInputStream(source, MAX_STREAM_BYTES);
        return new PushbackReader(new java.io.BufferedReader(new InputStreamReader(bounded,
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))), pushback);
    }

    /** Headers are diagnostic hints. Only a validated payload can establish completed inference. */
    private static JSONObject readPayload(InputStream source, SpeechAnalyzer.Cancel cancel, String requestId,
                                          BodyInfo info) throws IOException {
        PushbackReader in = reader(source, MAX_SNIFF_CHARS);
        try {
            info.bodyFormat = sniff(in, cancel);
            if ("sse".equals(info.bodyFormat)) return readSse(in, cancel, requestId);
            if ("json".equals(info.bodyFormat)) return readJsonCompletion(in, cancel, requestId);
            if ("empty".equals(info.bodyFormat)) throw failure("stream_interrupted", 200, "", requestId, "empty", "eof");
            throw failure("invalid_stream", 200, "", requestId, info.bodyFormat, "body_format");
        } catch (CharacterCodingException malformed) {
            check(cancel); throw failure("invalid_stream", 200, "", requestId, info.bodyFormat, "utf8");
        }
    }

    /** Stop as soon as a known framing prefix is available; never wait for a full SSE buffer. */
    private static String sniff(PushbackReader in, SpeechAnalyzer.Cancel cancel) throws IOException {
        char[] prefix = new char[MAX_SNIFF_CHARS]; int length = 0;
        String field = "", format = "unknown"; boolean blankLine = true, unknownField = false, nonWhitespace = false;
        while (length < prefix.length) {
            check(cancel);
            int ch = in.read();
            if (ch == -1) { format = nonWhitespace ? "unknown" : "empty"; break; }
            prefix[length++] = (char) ch;
            if (length == 1 && ch == '\ufeff') continue;
            if (ch == '\r' || ch == '\n') { field = ""; blankLine = true; unknownField = false; continue; }
            if (blankLine && (ch == ' ' || ch == '\t')) { unknownField = true; continue; }
            nonWhitespace = true;
            if (blankLine) {
                blankLine = false;
                if (ch == '<') { format = "html"; break; }
                if (ch == '{' || ch == '[') { format = "json"; break; }
            }
            if (unknownField) continue;
            field += (char) ch;
            if (":".equals(field) || "data:".equals(field) || "event:".equals(field)
                    || "id:".equals(field) || "retry:".equals(field)) { format = "sse"; break; }
            if (!"data:".startsWith(field) && !"event:".startsWith(field)
                    && !"id:".startsWith(field) && !"retry:".startsWith(field)) unknownField = true;
        }
        if (length > 0) in.unread(prefix, 0, length);
        return format;
    }

    /**
     * Transport compatibility for a self-contained completed event or full Responses object.
     * SIWC requests remain stream:true; this does not enable synchronous requests or retry routes.
     */
    private static JSONObject readJsonCompletion(PushbackReader in, SpeechAnalyzer.Cancel cancel, String requestId) throws IOException {
        StringBuilder json = new StringBuilder(); char[] buffer = new char[4096]; int count;
        while ((count = in.read(buffer)) != -1) {
            check(cancel);
            if (count > MAX_STREAM_BYTES - json.length()) throw failure("response_too_large", 200, "", requestId, "json", "json_size");
            json.append(buffer, 0, count);
        }
        check(cancel);
        if (json.length() > 0 && json.charAt(0) == '\ufeff') json.deleteCharAt(0);
        JSONObject value;
        try {
            String text = json.toString();
            new JsonSyntax(text).check();
            value = new JSONObject(text);
        } catch (Exception malformed) { throw failure("invalid_stream", 200, "", requestId, "json", "json_syntax"); }
        if (value.has("error") && !value.isNull("error")) throw eventFailure(value, 200, requestId, "json_error");
        Object type = value.opt("type");
        JSONObject response;
        if ("response.completed".equals(type)) {
            response = value.optJSONObject("response");
            if (response == null) throw failure("invalid_stream", 200, "", requestId, "json", "json_envelope");
        } else if ("response.failed".equals(type) || "error".equals(type)) {
            throw eventFailure("response.failed".equals(type) ? value.optJSONObject("response") : value, 200, requestId, "json_error");
        } else if ("response.incomplete".equals(type) || "response.cancelled".equals(type)) {
            throw failure("response_incomplete", 200, "", requestId, "json", "terminal_status");
        } else if (!value.has("type") && "response".equals(value.opt("object"))) {
            response = value;
        } else throw failure("invalid_stream", 200, "", requestId, "json", "json_envelope");
        if (response.has("error") && !response.isNull("error")) throw eventFailure(response, 200, requestId, "json_error");
        if (!"completed".equals(response.opt("status"))) throw failure("response_incomplete", 200, "", requestId, "json", "completed_status");
        if (response.has("incomplete_details") && !response.isNull("incomplete_details"))
            throw failure("response_incomplete", 200, "", requestId, "json", "incomplete_details");
        Object id = response.opt("id");
        if (!(id instanceof String) || !((String) id).matches("resp_[A-Za-z0-9_-]+")
                || response.has("object") && !"response".equals(response.opt("object")) || response.has("type"))
            throw failure("invalid_stream", 200, "", requestId, "json", "json_response");
        if (!(response.opt("output") instanceof JSONArray)) throw failure("invalid_stream", 200, "", requestId, "json", "json_output");
        try {
            JSONObject completed = new ChatGptOutputItems().complete(response);
            check(cancel); return completed;
        } catch (ChatGptOutputItems.Invalid malformed) {
            throw failure("invalid_stream", 200, "", requestId, "json", malformed.stage);
        }
    }

    private static JSONObject readSse(PushbackReader in, SpeechAnalyzer.Cancel cancel, String requestId) throws IOException {
        StringBuilder data = new StringBuilder(); String event = "", responseId = "";
        ChatGptOutputItems outputItems = new ChatGptOutputItems();
        boolean firstLine = true;
        while (true) {
            check(cancel);
            String line;
            try { line = readLine(in, cancel); }
            catch (Failure error) { throw error; }
            catch (CharacterCodingException malformed) { check(cancel); throw failure("invalid_stream", 200, "", requestId, "sse", "utf8"); }
            // SocketTimeoutException and connection failures must retain their transport meaning.
            // The HTTP boundary classifies them without exposing exception text or response bytes.
            if (line == null) throw failure("stream_interrupted", 200, "", requestId, "sse", "eof");
            if (firstLine) { firstLine = false; if (line.startsWith("\ufeff")) line = line.substring(1); }
            if (line.isEmpty()) {
                if (data.length() == 0) { event = ""; continue; }
                String json = data.substring(0, data.length() - 1); data.setLength(0);
                if ("[DONE]".equals(json.trim())) throw failure("stream_interrupted", 200, "", requestId, "sse", "missing_completed");
                JSONObject value = jsonObject(json, requestId);
                String kind = value.optString("type");
                if (kind.isEmpty() || !event.isEmpty() && !"message".equals(event) && !event.equals(kind))
                    throw failure("invalid_stream", 200, "", requestId, "sse", "event_type");
                event = "";
                // This connection does not resume by sequence_number. Match item identities and
                // completion instead of requiring undocumented global ordering of metadata events.
                JSONObject response = value.optJSONObject("response");
                if (response != null && response.has("id")) {
                    String found = response.optString("id");
                    if (found.isEmpty() || !responseId.isEmpty() && !responseId.equals(found))
                        throw failure("response_mismatch", 200, "", requestId, "sse", "response_id");
                    responseId = found;
                }
                if ("error".equals(kind)) throw eventFailure(value, 200, requestId, "sse_error");
                if ("response.failed".equals(kind)) throw eventFailure(response, 200, requestId, "sse_failed");
                if ("response.incomplete".equals(kind) || "response.cancelled".equals(kind))
                    throw failure("response_incomplete", 200, "", requestId, "sse", "terminal_status");
                if ("response.completed".equals(kind)) {
                    if (response == null || !"completed".equals(response.optString("status")))
                        throw failure("response_incomplete", 200, "", requestId, "sse", "completed_status");
                    if (response.has("error") && !response.isNull("error")) throw eventFailure(response, 200, requestId, "sse_failed");
                    try {
                        JSONObject completed = outputItems.complete(response);
                        check(cancel); return completed;
                    } catch (ChatGptOutputItems.Invalid malformed) {
                        throw failure("invalid_stream", 200, "", requestId, "sse", malformed.stage);
                    }
                }
                try { outputItems.accept(value); }
                catch (ChatGptOutputItems.Invalid malformed) { throw failure("invalid_stream", 200, "", requestId, "sse", malformed.stage); }
                continue;
            }
            if (line.charAt(0) == ':') continue;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if ("event".equals(field)) {
                if (value.length() > 200) throw failure("invalid_stream", 200, "", requestId, "sse", "event_name");
                event = value;
            } else if ("data".equals(field)) {
                if (data.length() + value.length() + 1 > MAX_EVENT_CHARS) throw failure("response_too_large", 200, "", requestId, "sse");
                data.append(value).append('\n');
            }
            // SSE id/retry/unknown fields have no effect; this client never reconnects automatically.
        }
    }

    private static String readLine(PushbackReader in, SpeechAnalyzer.Cancel cancel) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            if ((line.length() & 1023) == 0) check(cancel);
            int ch = in.read();
            if (ch == -1) return line.length() == 0 ? null : line.toString();
            if (ch == '\n') return line.toString();
            if (ch == '\r') { int next = in.read(); if (next != -1 && next != '\n') in.unread(next); return line.toString(); }
            if (line.length() >= MAX_EVENT_CHARS) throw failure("response_too_large", 200, "", "", "sse");
            line.append((char) ch);
        }
    }
    private static JSONObject jsonObject(String json, String requestId) throws IOException {
        try {
            // JSONTokener uses zero as EOF. A literal NUL must not disguise trailing bytes.
            if (json.indexOf('\0') >= 0) throw new IOException();
            JSONTokener input = new JSONTokener(json); Object value = input.nextValue();
            if (!(value instanceof JSONObject) || input.nextClean() != 0) throw new IOException();
            return (JSONObject) value;
        } catch (Exception malformed) { throw failure("invalid_stream", 200, "", requestId, "sse", "json"); }
    }
    private static Failure httpFailure(HttpsURLConnection connection, int status, String requestId, SpeechAnalyzer.Cancel cancel,
                                       BodyInfo info) throws IOException {
        JSONObject body = null; String shape = "empty";
        try (InputStream in = connection.getErrorStream()) {
            if (in != null) {
                byte[] bytes = readBounded(in, MAX_ERROR_BYTES, cancel);
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                try { JSONTokener t = new JSONTokener(json); Object v = t.nextValue(); if (v instanceof JSONObject && t.nextClean() == 0) body = (JSONObject) v; }
                catch (Exception ignored) { }
                shape = body == null ? "non_json" : body.has("error") ? "error" : body.has("detail") ? "detail" : "object";
                info.bodyFormat = body == null ? bytes.length == 0 ? "empty" : "unknown" : "json";
            }
        } catch (IOException malformed) { check(cancel); shape = "unreadable"; }
        return eventFailure(body, status, requestId, shape);
    }
    private static byte[] readBounded(InputStream in, int max, SpeechAnalyzer.Cancel cancel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = in.read(buffer)) != -1) {
            check(cancel); if (count > max - out.size()) throw new IOException("bounded error body"); out.write(buffer, 0, count);
        }
        check(cancel); return out.toByteArray();
    }
    private static Failure eventFailure(JSONObject body, int status, String requestId, String shape) {
        JSONObject error = body == null ? null : body.optJSONObject("error");
        if (error == null) error = body;
        String code = error == null ? "" : safeCode(error.optString("code"));
        String param = error == null ? "" : safeParam(error.optString("param"));
        return failure(code.isEmpty() ? "request_failed" : code, status, param, requestId, shape);
    }
    private static void check(SpeechAnalyzer.Cancel cancel) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancel != null && cancel.isCancelled())
            throw failure("cancelled", 0, "", "", "local");
    }

    /** Android's JSONObject accepts extensions. Compatibility JSON must have unambiguous JSON syntax. */
    private static final class JsonSyntax {
        private final String text; private int position;
        JsonSyntax(String text) { this.text = text; }
        void check() {
            space(); value(0); space();
            if (position != text.length()) invalid();
        }
        private void value(int depth) {
            if (depth > 128 || position >= text.length()) invalid();
            char ch = text.charAt(position);
            if (ch == '{') {
                position++; space(); Set<String> keys = new HashSet<>();
                if (take('}')) return;
                while (true) {
                    if (!keys.add(string(true))) invalid();
                    space(); require(':'); space(); value(depth + 1); space();
                    if (take('}')) return;
                    require(','); space();
                }
            } else if (ch == '[') {
                position++; space();
                if (take(']')) return;
                while (true) {
                    value(depth + 1); space();
                    if (take(']')) return;
                    require(','); space();
                }
            } else if (ch == '"') string(false);
            else if (ch == 't') literal("true");
            else if (ch == 'f') literal("false");
            else if (ch == 'n') literal("null");
            else number();
        }
        private String string(boolean key) {
            require('"'); StringBuilder decoded = key ? new StringBuilder() : null;
            while (position < text.length()) {
                char ch = text.charAt(position++);
                if (ch == '"') return key ? decoded.toString() : "";
                if (ch < 0x20) invalid();
                if (ch == '\\') {
                    if (position >= text.length()) invalid();
                    ch = text.charAt(position++);
                    switch (ch) {
                        case '"': case '\\': case '/': break;
                        case 'b': ch = '\b'; break; case 'f': ch = '\f'; break;
                        case 'n': ch = '\n'; break; case 'r': ch = '\r'; break; case 't': ch = '\t'; break;
                        case 'u':
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                if (position >= text.length()) invalid();
                                char digit = text.charAt(position++);
                                int hex = digit >= '0' && digit <= '9' ? digit - '0'
                                        : digit >= 'a' && digit <= 'f' ? digit - 'a' + 10
                                        : digit >= 'A' && digit <= 'F' ? digit - 'A' + 10 : -1;
                                if (hex < 0) invalid();
                                code = 16 * code + hex;
                            }
                            ch = (char) code; break;
                        default: invalid();
                    }
                }
                if (key) decoded.append(ch);
            }
            invalid(); return "";
        }
        private void number() {
            take('-');
            if (!take('0')) {
                if (position >= text.length() || text.charAt(position) < '1' || text.charAt(position) > '9') invalid();
                digits();
            }
            if (take('.')) { int start = position; digits(); if (position == start) invalid(); }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                int start = position; digits(); if (position == start) invalid();
            }
        }
        private void digits() { while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') position++; }
        private void literal(String value) { if (!text.startsWith(value, position)) invalid(); position += value.length(); }
        private void space() {
            while (position < text.length()) {
                char ch = text.charAt(position);
                if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') return;
                position++;
            }
        }
        private boolean take(char ch) {
            if (position < text.length() && text.charAt(position) == ch) { position++; return true; }
            return false;
        }
        private void require(char ch) { if (!take(ch)) invalid(); }
        private static void invalid() { throw new IllegalArgumentException(); }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maximum; private long consumed;
        LimitedInputStream(InputStream in, long maximum) { super(in); this.maximum = maximum; }
        private void count(long bytes) throws IOException {
            if (bytes > 0) consumed += bytes;
            if (consumed > maximum) throw failure("response_too_large", 200, "", "", "sse");
        }
        @Override public int read() throws IOException { int value = in.read(); if (value != -1) count(1); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int result = in.read(bytes, offset, length); count(result); return result;
        }
    }
    private static String safeCode(String value) { return value != null && value.matches("[a-z][a-z0-9_]{0,100}") ? value : ""; }
    private static String safeParam(String value) { return value != null && value.matches("[A-Za-z0-9_.\\[\\]-]{1,100}") ? value : ""; }
    private static String safeRequestId(String value) { return value != null && value.matches("req_[A-Za-z0-9_-]{1,120}") ? value : ""; }
    private static String mediaType(String header) {
        if (header == null || header.trim().isEmpty()) return "missing";
        int separator = header.indexOf(';');
        String value = (separator < 0 ? header : header.substring(0, separator)).trim().toLowerCase(Locale.ROOT);
        String safe = safeMediaType(value);
        return safe.isEmpty() ? "other" : safe;
    }
    private static String safeMediaType(String value) {
        if (value == null) return "";
        switch (value) {
            case "text/event-stream": case "application/json": case "application/problem+json":
            case "application/x-ndjson": case "application/json-seq": case "text/plain":
            case "text/html": case "application/xhtml+xml": case "application/octet-stream":
            case "missing": case "other": return value;
            default: return "";
        }
    }
    private static String safeBodyFormat(String value) {
        if (value == null) return "";
        switch (value) { case "sse": case "json": case "html": case "empty": case "unknown": return value; default: return ""; }
    }
    private static final class BodyInfo { String mediaType = "", bodyFormat = "unknown"; }
    private static Failure withBody(Failure failure, BodyInfo info) {
        return new Failure(failure.code, failure.httpStatus, failure.param, failure.requestId, failure.responseShape,
                failure.stage, info.mediaType, info.bodyFormat);
    }
    private static Failure failure(String code, int status, String param, String requestId, String shape) {
        return new Failure(code, status, param, requestId, shape);
    }
    private static Failure failure(String code, int status, String param, String requestId, String shape, String stage) {
        return new Failure(code, status, param, requestId, shape, stage);
    }
    private static String safeStage(String value) { return value != null && value.matches("[a-z][a-z0-9_]{0,63}") ? value : ""; }

    /** Structured diagnostics contain only validated identifiers, never tokens or provider message text. */
    public static final class Failure extends IOException {
        public final String code, param, requestId, responseShape, stage, mediaType, bodyFormat;
        public final int httpStatus;
        Failure(String code, int status, String param, String requestId, String shape) {
            this(code, status, param, requestId, shape, "");
        }
        Failure(String code, int status, String param, String requestId, String shape, String stage) {
            this(code, status, param, requestId, shape, stage, "", "");
        }
        Failure(String code, int status, String param, String requestId, String shape, String stage, String mediaType, String bodyFormat) {
            super(message(code, status)); this.code = safeCode(code); httpStatus = status;
            this.param = safeParam(param); this.requestId = safeRequestId(requestId); responseShape = safeStage(shape); this.stage = safeStage(stage);
            this.mediaType = safeMediaType(mediaType); this.bodyFormat = safeBodyFormat(bodyFormat);
        }
        public JSONObject diagnostics() {
            return Json.obj("provider", "chatgpt", "httpStatus", httpStatus, "code", code, "param", param,
                    "requestId", requestId, "responseShape", responseShape, "stage", stage, "mediaType", mediaType, "bodyFormat", bodyFormat);
        }
        private static String message(String code, int status) {
            switch (code) {
                case "subscription_sharing_usage_limit_exceeded":
                    return "ChatGPTのこのアプリ向け利用上限に達しました。設定の「利用量を管理」で確認してから再開してください。API課金への切り替えは行いません。";
                case "subscription_sharing_user_not_eligible":
                    return "選択したChatGPTアカウント・ワークスペースではプラン利用が許可されていません。アカウントの利用条件を確認してください。";
                case "subscription_sharing_usage_unavailable": case "subscription_sharing_user_unavailable":
                    return "ChatGPTの利用可否を一時的に確認できません。ログインは保持しました。時間をおいて再開してください。";
                case "subscription_sharing_unsupported_capability": case "invalid_request":
                    return "この解析要求またはモデルはChatGPTプラン方式で利用できません。利用可能なモデルとアプリの対応を確認してください。";
                case "subscription_sharing_route_not_supported": case "chatpass_v2_scope_not_authorized": case "chatpass_v2_invalid_authorization_context":
                    return "ChatGPTプランの権限がこの解析を許可していません。ログインしたアカウントとプラン利用の許可を確認してください。";
                case "subscription_sharing_invalid_user": case "invalid_credential":
                    return "ChatGPTの認証情報を確認できません。設定で選択中のアカウントとログイン状態を確認してください。";
                case "cancelled": return "ChatGPT解析を一時停止しました。";
                case "timeout": return "ChatGPT解析の応答待ちがタイムアウトしました。完了済み区間は保存されています。時間をおいて再開してください。";
                case "stream_interrupted": case "response_incomplete":
                    return "ChatGPT解析の応答が完了する前に終了しました。途中の応答は採用せず、完了済み区間から再開できます。";
                case "invalid_stream": case "response_mismatch":
                    return "ChatGPT解析の応答形式を確認できませんでした。途中の応答は採用していません。";
                case "response_too_large": return "ChatGPT解析の応答が大きすぎます。途中の応答は採用せず、完了済み区間を保持しました。";
            }
            if (status == 401) return "ChatGPTの認証またはプラン利用権限を確認できません。設定で選択中のアカウントとログイン状態を確認してください。";
            if (status == 403) return "ChatGPTのアカウント・地域・ワークスペースのポリシーにより、この解析が許可されていません。";
            if (status == 429) return "ChatGPT解析の利用制限に達しました。設定の「利用量を管理」で確認してから再開してください。";
            if (status == 503) return "ChatGPTへの接続を一時的に利用できません。ログインは保持しました。時間をおいて再開してください。";
            return "ChatGPT解析の通信に失敗しました。接続状態を確認し、完了済み区間から再開してください。";
        }
    }
}
