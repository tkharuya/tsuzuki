package jp.haruyalab.novelshelf;

import java.io.IOException;

/** Small-buffer playback of a growing file. Android output is an injected boundary. */
final class PcmPlayback implements AutoCloseable {
    interface Output extends AutoCloseable {
        int write(byte[] pcm, int offset, int length) throws Exception;
        long playedFrames();
        void play();
        void pause();
        void speed(float value);
        default void finishInput() { }
        void close();
    }
    interface Factory { Output open() throws Exception; }
    interface Listener {
        void playing();
        void buffering();
        void completed();
        void failed();
    }
    private final SpeechPcmSource source;
    private final Factory factory;
    private final Listener listener;
    private final Object control = new Object();
    private volatile Output output;
    private volatile boolean closed, paused, finished;
    private volatile float speed = 1;
    private volatile long baseBytes, writtenBytes, lastPosition;
    private long seekBytes = -1;
    private boolean resumeRequested;
    private Thread worker;

    PcmPlayback(SpeechPcmSource source, Factory factory, Listener listener) {
        this.source = source; this.factory = factory; this.listener = listener;
    }

    void start(float value, boolean startPaused) {
        synchronized (control) {
            if (worker != null || closed) return;
            speed = value; paused = startPaused;
            worker = new Thread(this::pump, "TsuzukiPcmPlayback"); worker.setDaemon(true); worker.start();
        }
    }
    void pause() {
        synchronized(control) {
            paused = true;
            Output current = output;
            if (current != null) try { current.pause(); } catch (RuntimeException ignored) { }
        }
    }
    void resume(float value) { synchronized (control) { speed = value; paused = false; resumeRequested=true; control.notifyAll(); } }
    void speed(float value) { synchronized (control) { speed = value; control.notifyAll(); } }
    boolean seek(long milliseconds) {
        synchronized (control) {
            if (closed || finished) return false;
            seekBytes = Math.max(0, Math.min(source.durationMs(), milliseconds)) * 48;
            seekBytes &= ~1L; control.notifyAll();
            return true;
        }
    }
    long positionMs() {
        synchronized (control) { if (seekBytes >= 0) return seekBytes / 48; }
        Output current = output;
        if (current != null) try {
            return (baseBytes + Math.min(writtenBytes, current.playedFrames() * 2)) / 48;
        } catch (RuntimeException ignored) { }
        return lastPosition / 48;
    }
    long durationMs() { return source.durationMs(); }
    boolean finished() { return finished; }

    private void pump() {
        byte[] buffer = new byte[8192];
        long offset = 0;
        int loaded = 0, sent = 0;
        boolean started = false, buffering = false, wasPaused = false;
        float appliedSpeed = -1;
        try {
            while (!closed) {
                synchronized (control) {
                    while (paused && !closed) { wasPaused = true; control.wait(50); }
                    if (closed) break;
                    if(resumeRequested){wasPaused=true;resumeRequested=false;}
                    if (seekBytes >= 0) {
                        closeOutput(); offset = seekBytes; seekBytes = -1;
                        loaded = sent = 0; started = false; appliedSpeed = -1;
                    }
                }
                if (output == null) { baseBytes = offset; writtenBytes = 0; output = factory.open(); }
                Output current = output;
                if (appliedSpeed != speed) { current.speed(speed); appliedSpeed = speed; }
                if (wasPaused) {
                    synchronized(control){if(started&&!paused&&!closed)current.play();}
                    wasPaused = false;if(started&&!paused&&!closed)listener.playing();
                }
                if (sent >= loaded) {
                    loaded = source.read(offset, buffer, buffer.length); sent = 0;
                    if (loaded <= 0) {
                        if(loaded<0)current.finishInput();
                        boolean drained = current.playedFrames() * 2 >= writtenBytes;
                        if (loaded < 0 && drained) {
                            synchronized (control) {
                                // A seek/pause accepted before EOF wins over completion. A later
                                // seek is rejected so the service can reopen the completed WAV.
                                if (closed) break;
                                if (seekBytes >= 0 || paused) continue;
                                lastPosition = offset; finished = true;
                            }
                            if (!closed) listener.completed(); return;
                        }
                        if (loaded == 0 && drained && !buffering && !paused) { buffering = true; listener.buffering(); }
                        if (loaded == 0) source.awaitData(30);
                        else synchronized (control) { control.wait(10); }
                        continue;
                    }
                }
                int written;
                synchronized(control) {
                    if(paused||closed)continue;
                    written = current.write(buffer, sent, loaded - sent);
                    if(written>0&&!started){current.play();started=true;}
                }
                if (written < 0 || written > loaded - sent || (written & 1) != 0)
                    throw new IOException("VOICEVOX_PLAYBACK_FAILED");
                if (written == 0) { synchronized (control) { control.wait(10); } continue; }
                sent += written; offset += written; writtenBytes += written;
                if (buffering || writtenBytes == written) {
                    buffering = false; if (!paused && !closed) listener.playing();
                }
                lastPosition = baseBytes + Math.min(writtenBytes, current.playedFrames() * 2);
            }
        } catch (Exception | LinkageError error) {
            if (!closed) listener.failed();
        } finally { synchronized (control) { closed = true; } closeOutput(); source.close(); }
    }

    private void closeOutput() {
        Output previous = output; output = null;
        if (previous != null) try { previous.close(); } catch (RuntimeException ignored) { }
    }
    @Override public void close() {
        synchronized (control) {
            if (closed) return;
            closed = true; source.close(); control.notifyAll();
            if (worker != null) worker.interrupt();
        }
        Output current = output;
        if (current != null) try { current.pause(); } catch (RuntimeException ignored) { }
    }
}
