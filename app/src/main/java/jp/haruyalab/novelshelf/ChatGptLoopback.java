package jp.haruyalab.novelshelf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Single-use browser callback on IPv4 loopback only. No callback data is reflected into HTML. */
public final class ChatGptLoopback implements AutoCloseable {
    private final ServerSocket server;
    private volatile Socket current;
    private volatile boolean closed;
    public ChatGptLoopback() throws IOException {
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(500);
    }
    public int port() { return server.getLocalPort(); }
    public ChatGptOAuth.Callback await(ChatGptOAuth.Attempt attempt, ChatGptOAuth.Cancel cancel) throws Exception {
        try {
        while (!closed && !cancel.cancelled() && !attempt.expired()) {
            Socket socket;
            try { socket = server.accept(); } catch (SocketTimeoutException timeout) { continue; }
            catch (IOException disconnected) { if (closed || cancel.cancelled()) break; throw disconnected; }
            current = socket;
            try (Socket connection = socket) {
                if (closed || cancel.cancelled()) break;
                if (!connection.getInetAddress().isLoopbackAddress()) continue;
                connection.setSoTimeout(3000);
                String target;
                try { target = target(connection.getInputStream(), port()); }
                catch (IOException invalid) { safeReply(connection, 400, false); continue; }
                if (closed || cancel.cancelled()) break;
                if (!target.startsWith("/auth/callback?")) { safeReply(connection, 404, false); continue; }
                try {
                    ChatGptOAuth.Callback callback = ChatGptOAuth.parseCallback(attempt, target);
                    safeReply(connection, 200, true); return callback;
                } catch (ChatGptOAuth.AuthException invalid) {
                    safeReply(connection, 400, false);
                    if ("invalid_callback".equals(invalid.code) || "state_mismatch".equals(invalid.code)) continue;
                    throw invalid;
                }
            } catch (ChatGptOAuth.AuthException invalid) { throw invalid;
            } catch (SocketTimeoutException ignored) {
                // A local connection that sends no request cannot consume a pending OAuth attempt.
            } catch (IOException ignored) {
                // A browser disconnect or unrelated reset connection does not cancel the OAuth attempt.
            } finally { current = null; }
        }
        if (attempt.expired()) throw new ChatGptOAuth.AuthException("login_expired", 0, "");
        throw new ChatGptOAuth.AuthException("cancelled", 0, "");
        } finally { close(); }
    }
    static String target(InputStream input, int port) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); int match = 0;
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (buffer.size() < 32768) {
            if (System.nanoTime() > deadline) throw new IOException("HTTP_TIMEOUT");
            int value = input.read(); if (value < 0) throw new IOException("HTTP_HEADER");
            if (value == 0 || value > 127) throw new IOException("HTTP_HEADER");
            buffer.write(value);
            int expected = match % 2 == 0 ? '\r' : '\n';
            match = value == expected ? match + 1 : value == '\r' ? 1 : 0;
            if (match == 4) break;
        }
        if (match != 4) throw new IOException("HTTP_HEADER");
        String[] lines = new String(buffer.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
        if (lines.length == 0) throw new IOException("HTTP_REQUEST");
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !"GET".equals(first[0]) || !"HTTP/1.1".equals(first[2]) || first[1].length() > 16384)
            throw new IOException("HTTP_REQUEST");
        String host = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':'); if (colon <= 0 || Character.isWhitespace(lines[i].charAt(0))) throw new IOException("HTTP_HEADER");
            String name = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+")) throw new IOException("HTTP_HEADER");
            String value = lines[i].substring(colon + 1).trim();
            for (int j = colon + 1; j < lines[i].length(); j++) {
                char c = lines[i].charAt(j); if (c < 32 && c != '\t' || c == 127) throw new IOException("HTTP_HEADER");
            }
            if ("host".equals(name)) { if (host != null) throw new IOException("HTTP_HOST"); host = value; }
            if ("transfer-encoding".equals(name) || "content-length".equals(name) && !"0".equals(value)) throw new IOException("HTTP_BODY");
        }
        if (!("127.0.0.1:" + port).equals(host)) throw new IOException("HTTP_HOST");
        return first[1];
    }
    private static void safeReply(Socket socket, int code, boolean accepted) {
        try { reply(socket.getOutputStream(), code, accepted); } catch (IOException ignored) { }
    }
    private static void reply(OutputStream output, int code, boolean accepted) throws IOException {
        String text = accepted ? "認証を確認しています。「つづき」へ戻って完了を確認してください。" : "この認証要求は受け付けられませんでした。「つづき」でログイン状態を確認してください。";
        byte[] body = ("<!doctype html><html lang=\"ja\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>つづき — ChatGPTログイン</title><body><h1>つづき</h1><p>" + text
                + "</p><p><a href=\"tsuzuki://chatgpt/return\">つづきに戻る</a></p></body></html>").getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 404 ? " Not Found" : " Bad Request")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\nPragma: no-cache\r\nReferrer-Policy: no-referrer"
                + "\r\nContent-Security-Policy: default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'"
                + "\r\nX-Content-Type-Options: nosniff\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
    }
    @Override public void close() {
        closed = true; try { server.close(); } catch (IOException ignored) { }
        Socket socket = current; if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
