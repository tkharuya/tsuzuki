package jp.haruyalab.novelshelf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Strict 24 kHz mono PCM16 assembly. RIFF headers never become samples; no speech is trimmed. */
final class VoicePcm {
    interface Sink { void append(byte[] pcm, boolean speechReady) throws IOException; }
    static final int SAMPLE_RATE = 24000;
    static final int MAX_PCM_BYTES = 180 * SAMPLE_RATE * 2;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private final Sink sink;

    VoicePcm() { this(null); }
    VoicePcm(Sink sink) { this.sink = sink; }

    void appendWave(byte[] wave) throws IOException {
        appendSamples(samples(wave));
    }

    void appendSamples(byte[] samples) throws IOException {
        reserve(samples.length);
        // A two-millisecond ramp removes hard PCM edge discontinuities without removing samples.
        // Native silence stays exactly zero; no amplitude threshold is used to guess speech edges.
        int count = Math.min(SAMPLE_RATE / 500, samples.length / 4);
        for (int i = 0; i < count; i++) {
            scale(samples, i * 2, i / (double) count);
            scale(samples, samples.length - 2 - i * 2, i / (double) count);
        }
        pcm.write(samples, 0, samples.length);
        if (sink != null) sink.append(samples, true);
    }

    void silence(int milliseconds) throws IOException {
        if (milliseconds < 0 || milliseconds > 1200) throw invalid();
        int length = milliseconds * (SAMPLE_RATE / 1000) * 2;
        reserve(length);
        byte[] zeros = new byte[Math.min(length, 4096)];
        for (int remaining = length; remaining > 0;) {
            int n = Math.min(zeros.length, remaining);
            pcm.write(zeros, 0, n);
            if (sink != null) sink.append(n == zeros.length ? zeros : Arrays.copyOf(zeros, n), false);
            remaining -= n;
        }
    }

    byte[] wave() throws IOException {
        if (pcm.size() == 0) throw invalid();
        byte[] output = Arrays.copyOf(header(pcm.size()), 44 + pcm.size());
        byte[] samples = pcm.toByteArray();
        System.arraycopy(samples, 0, output, 44, samples.length);
        return output;
    }

    int size() { return pcm.size(); }

    private void reserve(int bytes) throws IOException {
        if (bytes < 0 || bytes > MAX_PCM_BYTES - pcm.size()) throw new IOException("VOICEVOX_AUDIO_TOO_LONG");
    }

    static byte[] samples(byte[] wave) throws IOException {
        if (wave == null || wave.length < 44 || !tag(wave, 0, "RIFF") || !tag(wave, 8, "WAVE")
                || unsigned(wave, 4) != wave.length - 8L) throw invalid();
        int dataStart = -1, dataLength = 0;
        boolean format = false;
        for (int at = 12; at < wave.length;) {
            if (wave.length - at < 8) throw invalid();
            long size = unsigned(wave, at + 4);
            long end = at + 8L + size, padded = end + (size & 1);
            if (padded > wave.length) throw invalid();
            if (tag(wave, at, "fmt ")) {
                if (format || size < 16 || u16(wave, at + 8) != 1 || u16(wave, at + 10) != 1
                        || unsigned(wave, at + 12) != SAMPLE_RATE || unsigned(wave, at + 16) != SAMPLE_RATE * 2
                        || u16(wave, at + 20) != 2 || u16(wave, at + 22) != 16) throw invalid();
                format = true;
            } else if (tag(wave, at, "data")) {
                if (dataStart != -1 || size == 0 || size > MAX_PCM_BYTES || (size & 1) != 0) throw invalid();
                dataStart = at + 8; dataLength = (int) size;
            }
            at = (int) padded;
        }
        if (!format || dataStart < 0) throw invalid();
        return Arrays.copyOfRange(wave, dataStart, dataStart + dataLength);
    }

    static byte[] header(int bytes) throws IOException {
        if (bytes < 0 || bytes > MAX_PCM_BYTES || (bytes & 1) != 0) throw invalid();
        byte[] result = new byte[44];
        tag(result, 0, new char[]{'R','I','F','F'}); integer(result, 4, 36 + bytes);
        tag(result, 8, new char[]{'W','A','V','E','f','m','t',' '}); integer(result, 16, 16);
        result[20] = 1; result[22] = 1;
        integer(result, 24, SAMPLE_RATE); integer(result, 28, SAMPLE_RATE * 2);
        result[32] = 2; result[34] = 16;
        tag(result, 36, new char[]{'d','a','t','a'}); integer(result, 40, bytes);
        return result;
    }

    private static void scale(byte[] pcm, int at, double factor) {
        int value = (short) ((pcm[at] & 255) | (pcm[at + 1] << 8));
        int next = (int) Math.round(value * factor);
        pcm[at] = (byte) next; pcm[at + 1] = (byte) (next >>> 8);
    }
    private static int u16(byte[] b, int at) { return (b[at] & 255) | ((b[at + 1] & 255) << 8); }
    private static long unsigned(byte[] b, int at) {
        return (b[at] & 255L) | ((b[at + 1] & 255L) << 8) | ((b[at + 2] & 255L) << 16) | ((b[at + 3] & 255L) << 24);
    }
    private static boolean tag(byte[] b, int at, String tag) {
        if (at < 0 || at > b.length - tag.length()) return false;
        for (int i = 0; i < tag.length(); i++) if (b[at + i] != tag.charAt(i)) return false;
        return true;
    }
    private static void tag(byte[] b, int at, char[] tag) {
        for (int i = 0; i < tag.length; i++) b[at + i] = (byte) tag[i];
    }
    private static void integer(byte[] b, int at, int value) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) (value >>> (8 * i));
    }
    private static IOException invalid() { return new IOException("VOICEVOX_INVALID_WAVE"); }
}
