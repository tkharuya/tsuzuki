package jp.haruyalab.novelshelf;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;

/** A bounded, growing PCM file. No whole-unit or multi-unit PCM queue is retained in RAM. */
final class SpeechPcmSource implements Closeable {
    private final RandomAccessFile reader;
    private long available;
    private boolean complete, closed;
    private String failure;

    SpeechPcmSource(File file) throws IOException { reader = new RandomAccessFile(file, "r"); }

    synchronized void publish(long bytes) throws IOException {
        if (bytes < available || bytes > VoicePcm.MAX_PCM_BYTES || (bytes & 1) != 0)
            throw new IOException("VOICEVOX_STREAM_LENGTH");
        if (closed) throw new InterruptedIOException("VOICEVOX_CANCELLED");
        available = bytes;
        notifyAll();
    }

    synchronized void finish() { complete = true; notifyAll(); }
    synchronized void fail(String code) { failure = code; notifyAll(); }
    synchronized long availableBytes() { return available; }
    synchronized boolean isComplete() { return complete; }
    synchronized long durationMs() { return available / 48; }

    /** Returns 0 when more PCM is needed, -1 only after the full file has committed. */
    synchronized int read(long offset, byte[] destination, int length) throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("VOICEVOX_CANCELLED");
        if (failure != null) throw new IOException(failure);
        if (offset < 0 || (offset & 1) != 0 || length < 2 || (length & 1) != 0)
            throw new IOException("VOICEVOX_STREAM_LENGTH");
        if (offset >= available) return complete ? -1 : 0;
        int count = (int)Math.min(length, available - offset);
        reader.seek(44 + offset); reader.readFully(destination, 0, count);
        return count;
    }

    synchronized void awaitData(long milliseconds) throws InterruptedException {
        if (!closed && failure == null && !complete) wait(Math.min(50, Math.max(1, milliseconds)));
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true; notifyAll();
        try { reader.close(); } catch (IOException ignored) { }
    }
}
