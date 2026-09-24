package ai.oak.tools.transport;

import ai.oak.tools.protocol.ConnectResponse;
import ai.oak.tools.protocol.RemoteStep;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds a WebSocket open to Oppex and receives steps pushed down it.
 *
 * <p>Uses {@link java.net.http.WebSocket} from the JDK — no websocket library is added, for the
 * same reason no HTTP library is: this jar runs inside someone else's application.
 *
 * <p><strong>Why the connection is held open.</strong> A firewall blocks strangers <em>starting</em>
 * conversations, not inbound data — which is why every web page you load works. So a connection the
 * agent opens outbound is permitted, and traffic flows both ways on it for as long as it lives.
 * Oppex cannot open one to the customer, so if this closes there is no way to deliver a step until
 * the agent dials again. That, and not efficiency, is why it stays open.
 *
 * <p>Reconnection is deliberately <em>not</em> handled here. {@link #run} returns when the socket
 * closes, and the caller re-dials with backoff — keeping the retry policy in one place instead of
 * splitting it between transport and agent.
 */
public class WebSocketTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(WebSocketTransport.class);
    private static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(30);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(20);

    private final OppexApi api;
    private final ConnectResponse connectResponse;
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile boolean stopping;

    public WebSocketTransport(OppexApi api, ConnectResponse connectResponse) {
        this.api = api;
        this.connectResponse = connectResponse;
    }

    @Override
    public String name() {
        return "websocket";
    }

    @Override
    public void run(StepHandler handler) throws Exception {
        final URI uri = URI.create(connectResponse.getWebsocketUrl());
        log.info("Connecting to {} at {}", api.vendor().getName(), redactQuery(uri));

        final WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header(Paths.TICKET_HEADER, connectResponse.getTicket())
                .connectTimeout(HANDSHAKE_TIMEOUT)
                .buildAsync(uri, new StepListener(handler))
                .get(HANDSHAKE_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
        socket.set(ws);

        final ScheduledExecutorService pinger = startHeartbeat(ws);
        try {
            finished.await();
        } finally {
            pinger.shutdownNow();
        }
        if (!stopping) {
            throw new java.io.IOException("WebSocket to " + api.vendor().getName() + " closed");
        }
    }

    @Override
    public void stop() {
        stopping = true;
        final WebSocket ws = socket.get();
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "agent shutting down");
        }
        finished.countDown();
    }

    /**
     * Sends a ping on a timer.
     *
     * <p>Not optional. An AWS load balancer's idle timeout defaults to 60 seconds and will drop a
     * WebSocket that has said nothing — which looks exactly like a network fault and is the single
     * most common cause of "the connection keeps dropping" reports. It also detects a
     * half-open connection, where the socket looks alive locally but nothing is at the far end.
     */
    private ScheduledExecutorService startHeartbeat(WebSocket ws) {
        final long seconds = connectResponse.getHeartbeatSeconds() == null
                ? DEFAULT_HEARTBEAT.toSeconds()
                : Math.max(5L, connectResponse.getHeartbeatSeconds());
        final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "oppex-adk-heartbeat");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(() -> {
            try {
                if (!ws.isOutputClosed()) {
                    ws.sendPing(java.nio.ByteBuffer.allocate(0));
                }
            } catch (RuntimeException e) {
                log.debug("Heartbeat ping failed: {}", e.getMessage());
            }
        }, seconds, seconds, TimeUnit.SECONDS);
        log.info("Heartbeat every {}s", seconds);
        return exec;
    }

    /** Strips any query string before logging a URL, in case a deployment does put a token there. */
    private static String redactQuery(URI uri) {
        return uri.getQuery() == null ? uri.toString() : uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
    }

    private final class StepListener implements WebSocket.Listener {

        private final StepHandler handler;
        private final StringBuilder buffer = new StringBuilder();

        private StepListener(StepHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            // Unbounded demand. The JDK's WebSocket delivers a message only against outstanding
            // demand, and the initial demand is zero — so failing to request more is a silent
            // stall, the worst possible failure mode here. Requesting once, unbounded, removes a
            // whole class of bug. Safe because onText does not block: it hands the step to a
            // worker pool and returns immediately.
            webSocket.request(Long.MAX_VALUE);
            log.info("Connected to {}", api.vendor().getName());
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // A text message can arrive in fragments, so it must be accumulated until last==true.
            // Parsing each fragment as JSON would work in testing, where messages are small and
            // never split, and fail in production on the first large one.
            buffer.append(data);
            if (!last) {
                return null;
            }
            final String message = buffer.toString();
            buffer.setLength(0);
            dispatch(message);
            return null;
        }

        private void dispatch(String message) {
            try {
                final RemoteStep step = api.mapper().readValue(message, RemoteStep.class);
                if (step == null || step.getTaskId() == null) {
                    log.debug("Ignoring non-step message: {}", abbreviate(message));
                    return;
                }
                handler.onStep(step);
            } catch (Exception e) {
                // Never let a bad message kill the connection: one unparseable frame would
                // otherwise take down a socket that is otherwise working fine.
                log.warn("Could not read a message from {} ({}): {}",
                        api.vendor().getName(), e.getMessage(), abbreviate(message));
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (stopping) {
                log.info("Connection closed as requested");
            } else {
                log.warn("{} closed the connection (status {}{}). Will reconnect.",
                        api.vendor().getName(), statusCode,
                        reason == null || reason.isBlank() ? "" : ", " + reason);
            }
            finished.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("WebSocket to {} failed: {}", api.vendor().getName(), error.getMessage());
            finished.countDown();
        }

        private String abbreviate(String s) {
            return s.length() <= 200 ? s : s.substring(0, 200) + "…";
        }
    }
}
