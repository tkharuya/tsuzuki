package jp.haruyalab.novelshelf;

import java.util.concurrent.atomic.AtomicInteger;

/** Only observes Session's cancellation signal; the actual analyzer is tested in its own suite. */
final class SpeechAnalysis {
    static final AtomicInteger cancellations = new AtomicInteger();
    static void cancel() { cancellations.incrementAndGet(); }
}
