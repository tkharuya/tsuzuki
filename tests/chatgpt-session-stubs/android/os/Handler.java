package android.os;

/** Posting boundary only. Test code explicitly drains the fixture main queue. */
public final class Handler {
    private final Looper looper;
    public Handler(Looper looper) { this.looper = looper; }
    public boolean post(Runnable job) { return looper.enqueue(job); }
}
