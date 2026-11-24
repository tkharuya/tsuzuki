package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
public final class Handler {
 private static final ConcurrentLinkedQueue<Runnable> QUEUE=new ConcurrentLinkedQueue<>();
 public Handler(Looper looper) { }
 public boolean post(Runnable action) { QUEUE.add(action); return true; }
 public boolean postDelayed(Runnable action, long milliseconds) { throw new AssertionError("Delayed Android/TTS boundary is not exercised by these fixtures"); }
 public void removeCallbacks(Runnable action) { QUEUE.remove(action); }
 public static void drain() { for(Runnable r; (r=QUEUE.poll())!=null;)r.run(); }
}
