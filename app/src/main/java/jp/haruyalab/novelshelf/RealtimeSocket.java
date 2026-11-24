package jp.haruyalab.novelshelf;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Small RFC 6455 client for one fixed, authenticated Realtime endpoint.
 * No custom trust manager, redirects, compression, cookies, or caller-controlled host.
 */
final class RealtimeSocket implements Closeable {
    private static final String HOST = "api.openai.com";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 32768;
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();
    private final Object transportLock = new Object();
    private volatile boolean closed;
    private volatile Socket transport;
    private InputStream input;
    private OutputStream output;
    private long deadlineNanos;

    RealtimeSocket() {}

    // In-memory protocol harness; does not change production endpoint or TLS verification.
    RealtimeSocket(InputStream in, OutputStream out) {
        input = in; output = out; deadlineNanos = System.nanoTime() + 30_000_000_000L;
    }

    void connect(String key, String model, int timeoutMillis) throws IOException {
        if (key == null || key.isEmpty() || key.length() > 1024 || !key.matches("[!-~]+"))
            throw new IOException("API_KEY_INVALID");
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
            throw new IOException("MODEL_INVALID");
        deadlineNanos = System.nanoTime() + Math.max(1000L, timeoutMillis) * 1_000_000L;
        Socket raw = new Socket();
        setTransport(raw);
        try {
            raw.connect(new InetSocketAddress(HOST, 443), 15000);
            raw.setSoTimeout(20000);
            checkOpen();
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, HOST, 443, true);
            setTransport(tls);
            SSLParameters params = tls.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            params.setServerNames(Collections.singletonList(new SNIHostName(HOST)));
            tls.setSSLParameters(params);
            tls.setSoTimeout(20000);
            tls.startHandshake();
            checkOpen();
            input = tls.getInputStream();
            output = tls.getOutputStream();
            byte[] nonce = new byte[16]; random.nextBytes(nonce);
            String wsKey = Base64.getEncoder().encodeToString(nonce);
            String request = "GET /v1/realtime?model=" + model + " HTTP/1.1\r\n"
                    + "Host: " + HOST + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\nSec-WebSocket-Version: 13\r\n"
                    + "Authorization: Bearer " + key + "\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            readHandshake(input, wsKey, deadlineNanos);
        } catch (IOException | RuntimeException ex) {
            close();
            throw ex;
        }
    }

    private void setTransport(Socket socket) throws IOException {
        synchronized (transportLock) {
            if (closed) { socket.close(); throw new IOException("CANCELLED"); }
            transport = socket;
        }
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("CANCELLED");
        if (deadlineNanos > 0 && System.nanoTime() > deadlineNanos)
            throw new java.net.SocketTimeoutException("REALTIME_TIMEOUT");
    }

    static void readHandshake(InputStream in, String key) throws IOException {
        readHandshake(in,key,System.nanoTime()+30_000_000_000L);
    }

    private static void readHandshake(InputStream in, String key, long deadline) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched != 4) {
            if(System.nanoTime()>deadline)throw new java.net.SocketTimeoutException("UPGRADE_TIMEOUT");
            int b = in.read();
            if (b < 0) throw new EOFException("UPGRADE_CLOSED");
            bytes.write(b);
            if (bytes.size() > MAX_HEADER_BYTES) throw new IOException("UPGRADE_TOO_LARGE");
            int expected = (matched % 2 == 0) ? 13 : 10;
            if (b == expected) matched++; else matched = b == 13 ? 1 : 0;
        }
        String[] lines = new String(bytes.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
        if (lines.length == 0 || !lines[0].matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"))
            throw new IOException("UPGRADE_INVALID");
        int status = Integer.parseInt(lines[0].substring(9, 12));
        if (status != 101) throw new HttpFailure(status);
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0 || Character.isWhitespace(line.charAt(0)))
                throw new IOException("UPGRADE_HEADER_INVALID");
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (headers.containsKey(name)) {
                if (name.equals("sec-websocket-accept")) throw new IOException("UPGRADE_INVALID");
                value = headers.get(name) + "," + value;
            }
            headers.put(name, value);
        }
        String expected;
        try {
            expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException ex) { throw new IOException("SHA1_UNAVAILABLE"); }
        if (!hasToken(headers.get("upgrade"), "websocket")
                || !hasToken(headers.get("connection"), "upgrade")
                || !expected.equals(headers.get("sec-websocket-accept"))
                || headers.containsKey("sec-websocket-extensions")
                || headers.containsKey("sec-websocket-protocol"))
            throw new IOException("UPGRADE_INVALID");
    }

    private static boolean hasToken(String value, String token) {
        if (value == null) return false;
        for (String s : value.split(",")) if (s.trim().equalsIgnoreCase(token)) return true;
        return false;
    }

    void sendText(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MESSAGE_BYTES) throw new IOException("MESSAGE_TOO_LARGE");
        sendFrame(1, bytes);
    }

    private void sendFrame(int opcode, byte[] bytes) throws IOException {
        synchronized (writeLock) {
            checkOpen();
            if (output == null) throw new IOException("NOT_CONNECTED");
            ByteArrayOutputStream frame = new ByteArrayOutputStream(bytes.length + 14);
            frame.write(0x80 | opcode);
            if (bytes.length < 126) frame.write(0x80 | bytes.length);
            else if (bytes.length <= 65535) {
                frame.write(0x80 | 126); frame.write(bytes.length >>> 8); frame.write(bytes.length);
            } else {
                frame.write(0x80 | 127);
                for (int s = 56; s >= 0; s -= 8) frame.write((int) ((long) bytes.length >>> s));
            }
            byte[] mask = new byte[4]; random.nextBytes(mask); frame.write(mask);
            for (int i = 0; i < bytes.length; i++) frame.write(bytes[i] ^ mask[i % 4]);
            output.write(frame.toByteArray()); output.flush();
        }
    }

    String readText() throws IOException {
        ByteArrayOutputStream message = null;
        while (true) {
            checkOpen();
            if (input == null) throw new IOException("NOT_CONNECTED");
            int b0 = readByte(input), b1 = readByte(input);
            boolean fin = (b0 & 128) != 0;
            int opcode = b0 & 15;
            if ((b0 & 112) != 0 || (b1 & 128) != 0) throw new IOException("FRAME_INVALID");
            boolean control = opcode >= 8;
            int lengthTag = b1 & 127;
            long length = lengthTag;
            if (lengthTag == 126) {
                length = ((long) readByte(input) << 8) | readByte(input);
                if (length < 126) throw new IOException("FRAME_INVALID");
            } else if (lengthTag == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    int v = readByte(input);
                    if (i == 0 && (v & 128) != 0) throw new IOException("FRAME_INVALID");
                    length = (length << 8) | v;
                }
                if (length <= 65535) throw new IOException("FRAME_INVALID");
            }
            if (length > MAX_MESSAGE_BYTES || control && (!fin || length > 125))
                throw new IOException("FRAME_TOO_LARGE");
            byte[] data = new byte[(int) length];
            int position = 0;
            while (position < data.length) {
                checkOpen();
                int read = input.read(data, position, data.length - position);
                if (read < 0) throw new EOFException("FRAME_CLOSED");
                position += read;
            }
            if (opcode == 9) { sendFrame(10, data); continue; }
            if (opcode == 10) continue;
            if (opcode == 8) {
                if (data.length == 1) throw new IOException("CLOSE_INVALID");
                if (data.length >= 2) {
                    int code=((data[0]&255)<<8)|(data[1]&255);
                    if(!(code>=1000&&code<=1014&&code!=1004&&code!=1005&&code!=1006 || code>=3000&&code<=4999))
                        throw new IOException("CLOSE_INVALID");
                    decodeUtf8(data, 2, data.length - 2);
                }
                try { sendFrame(8, data); } finally { close(); }
                throw new EOFException("REALTIME_CLOSED");
            }
            if (opcode == 1) {
                if (message != null) throw new IOException("FRAGMENT_INVALID");
                if (fin) return decodeUtf8(data, 0, data.length);
                message = new ByteArrayOutputStream(data.length + 512);
            } else if (opcode == 0) {
                if (message == null) throw new IOException("FRAGMENT_INVALID");
            } else throw new IOException("FRAME_OPCODE_INVALID");
            if ((long) message.size() + data.length > MAX_MESSAGE_BYTES)
                throw new IOException("MESSAGE_TOO_LARGE");
            message.write(data);
            if (fin) {
                byte[] merged = message.toByteArray();
                return decodeUtf8(merged, 0, merged.length);
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("FRAME_CLOSED");
        return b;
    }

    private static String decodeUtf8(byte[] data, int offset, int length) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length)).toString();
        } catch (CharacterCodingException ex) { throw new IOException("FRAME_UTF8_INVALID"); }
    }

    @Override public void close() {
        synchronized (transportLock) {
            closed = true;
            Socket s = transport; transport = null;
            if (s != null) try { s.close(); } catch (IOException ignored) {}
        }
    }

    static final class HttpFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        HttpFailure(int code) { super("HTTP_" + code); status = code; }
    }
}
