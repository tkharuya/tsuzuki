package android.os;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Deterministically drained queue; it does not emulate Android threading or lifecycle. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private final LinkedBlockingQueue<Runnable> jobs = new LinkedBlockingQueue<>();
    private Looper() { }
    public static Looper getMainLooper() { return MAIN; }
    boolean enqueue(Runnable job) { return jobs.offer(job); }
    public static void drainFixtureQueue() { Runnable job; while ((job = MAIN.jobs.poll()) != null) job.run(); }
    public static void runFixtureEvent(long timeoutMs) throws InterruptedException {
        Runnable job = MAIN.jobs.poll(timeoutMs, TimeUnit.MILLISECONDS); if (job != null) job.run();
    }
}
