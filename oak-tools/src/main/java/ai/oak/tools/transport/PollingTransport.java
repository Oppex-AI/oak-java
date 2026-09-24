package ai.oak.tools.transport;

import ai.oak.tools.protocol.RemoteStep;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks Oppex for work on a timer.
 *
 * <p>The fallback, not the default. It exists for environments whose outbound proxy inspects TLS
 * and refuses the WebSocket upgrade — a plain HTTPS request gets through anything. The cost is
 * latency: a step waits up to one interval before it starts.
 *
 * <p>Note the loop keeps asking immediately while steps keep coming, and only sleeps once the queue
 * is empty. During an incident, when several steps are queued, that makes it behave far closer to
 * push than a fixed-interval poll would.
 */
public class PollingTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(PollingTransport.class);

    /** Stop hammering after this many consecutive steps, so one busy client cannot monopolise. */
    private static final int MAX_CONSECUTIVE = 20;

    private final OppexApi api;
    private final Duration interval;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object sleepLock = new Object();

    public PollingTransport(OppexApi api, Duration interval) {
        this.api = api;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "poll";
    }

    @Override
    public void run(StepHandler handler) throws InterruptedException {
        log.info("Polling {} every {}s for work", api.vendor().getName(), interval.toSeconds());
        int consecutiveFailures = 0;
        while (running.get()) {
            int delivered = 0;
            try {
                RemoteStep step;
                while (delivered < MAX_CONSECUTIVE && running.get() && (step = api.nextStep()) != null) {
                    delivered++;
                    handler.onStep(step);
                }
                consecutiveFailures = 0;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                consecutiveFailures++;
                // WARN not ERROR: a poll that fails is usually a transient network problem and the
                // next one will succeed. It becomes ERROR-worthy only if it keeps happening, which
                // the count makes visible without one line per failed poll.
                log.warn("Poll of {} failed ({} in a row): {}", api.vendor().getName(),
                        consecutiveFailures, e.getMessage());
            }
            if (delivered == 0 && running.get()) {
                sleep(interval);
            }
        }
        log.info("Polling stopped");
    }

    @Override
    public void stop() {
        running.set(false);
        synchronized (sleepLock) {
            sleepLock.notifyAll();
        }
    }

    /** Interruptible wait, so stop() returns promptly instead of after a full interval. */
    private void sleep(Duration duration) throws InterruptedException {
        synchronized (sleepLock) {
            if (running.get()) {
                sleepLock.wait(Math.max(1L, duration.toMillis()));
            }
        }
    }
}
