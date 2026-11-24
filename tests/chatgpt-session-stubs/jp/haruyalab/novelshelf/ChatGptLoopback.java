package jp.haruyalab.novelshelf;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEST ONLY: queued callback boundary, with no sockets. The production loopback parser/server
 * has a separate suite. Session still calls the real OAuth.parseCallback state/client checks.
 */
public final class ChatGptLoopback implements AutoCloseable {
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(41000);
    private static final ConcurrentHashMap<Integer, ChatGptLoopback> OPEN = new ConcurrentHashMap<>();
    private static final String CLOSED = "fixture-listener-closed";
    private final int port = NEXT_PORT.getAndIncrement();
    private final LinkedBlockingQueue<String> callbacks = new LinkedBlockingQueue<>();
    private volatile boolean closed;
    public ChatGptLoopback() { OPEN.put(port, this); }
    public int port() { return port; }
    public ChatGptOAuth.Callback await(ChatGptOAuth.Attempt attempt, ChatGptOAuth.Cancel cancel) throws Exception {
        while (!closed && !cancel.cancelled() && !attempt.expired()) {
            String target = callbacks.poll(100, TimeUnit.MILLISECONDS);
            if (target == null) continue;
            if (CLOSED.equals(target) || closed || cancel.cancelled()) break;
            return ChatGptOAuth.parseCallback(attempt, target);
        }
        throw new ChatGptOAuth.AuthException(attempt.expired() ? "login_expired" : "cancelled", 0, "");
    }
    public static boolean deliverFixtureCallback(int port, String target) {
        ChatGptLoopback listener = OPEN.get(port);
        return listener != null && !listener.closed && listener.callbacks.offer(target);
    }
    public static boolean fixtureListening(int port) { return OPEN.containsKey(port); }
    public static int fixtureListenerCount() { return OPEN.size(); }
    @Override public void close() { closed = true; OPEN.remove(port, this); callbacks.offer(CLOSED); }
}
