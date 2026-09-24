package ai.oppex.adk.tools;

import ai.oppex.adk.tools.config.AgentConfig;
import ai.oppex.adk.tools.config.TransportMode;
import ai.oppex.adk.tools.protocol.ConnectResponse;
import ai.oppex.adk.tools.protocol.RegistrationRequest;
import ai.oppex.adk.tools.protocol.RemoteStep;
import ai.oppex.adk.tools.protocol.StepResult;
import ai.oppex.adk.tools.transport.OppexApi;
import ai.oppex.adk.tools.transport.PollingTransport;
import ai.oppex.adk.tools.transport.Transport;
import ai.oppex.adk.tools.transport.WebSocketTransport;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agent. Registers what it can do, connects to Oppex, runs the steps it is sent, and reports
 * what happened.
 *
 * <pre>{@code
 * var registry = new ToolRegistry().register(new MyCapability());
 * var config = new AgentConfig().setVendor(
 *         new VendorConfig("oppex", "https://api.oppex.example", apiKey));
 *
 * try (var agent = new OppexAgent(config, registry)) {
 *     agent.start();          // returns immediately; the loop runs on its own thread
 *     Runtime.getRuntime().addShutdownHook(new Thread(agent::close));
 * }
 * }</pre>
 *
 * <p><strong>Nothing here dials into your network.</strong> This process makes outbound HTTPS calls
 * only. Oppex has no address for you and no way to reach you if this is not running.
 */
public class OppexAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OppexAgent.class);

    private final AgentConfig config;
    private final ToolRegistry registry;
    private final OppexApi api;
    private final ExecutorService workers;
    private final AtomicReference<Transport> current = new AtomicReference<>();

    private Thread loop;
    private volatile boolean stopping;

    public OppexAgent(AgentConfig config, ToolRegistry registry) {
        if (config.getVendor() == null) {
            throw new IllegalArgumentException("AgentConfig needs a vendor (base URL and API key)");
        }
        this.config = config;
        this.registry = registry;
        this.api = new OppexApi(config.getVendor());
        this.workers = Executors.newFixedThreadPool(config.getWorkerThreads(), r -> {
            final Thread t = new Thread(r, "oppex-adk-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts the connect loop on its own thread and returns. */
    public synchronized void start() {
        if (loop != null) {
            throw new IllegalStateException("Agent already started");
        }
        if (registry.size() == 0) {
            log.warn("No capabilities registered — this agent will connect and then refuse every "
                    + "step it is sent. Register at least one before starting.");
        }
        loop = new Thread(this::connectForever, "oppex-adk-agent");
        loop.setDaemon(false);
        loop.start();
    }

    /**
     * Registers, connects, and reconnects for as long as the agent is running.
     *
     * <p>Backoff doubles up to a cap. Without it, an Oppex outage would have every agent in every
     * customer estate retrying in a tight loop — turning our problem into a second problem for
     * them, and delaying our recovery.
     */
    private void connectForever() {
        Duration backoff = config.getReconnectDelay();
        while (!stopping) {
            try {
                register();
                final Transport transport = chooseTransport();
                current.set(transport);
                log.info("Receiving work over {}", transport.name());
                backoff = config.getReconnectDelay();   // reset only after a connection succeeded
                transport.run(this::submit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (stopping) {
                    return;
                }
                log.warn("Disconnected from {}: {}. Retrying in {}s.",
                        config.getVendor().getName(), e.getMessage(), backoff.toSeconds());
                if (!sleep(backoff)) {
                    return;
                }
                backoff = doubled(backoff);
            }
        }
    }

    private void register() throws Exception {
        final RegistrationRequest request = new RegistrationRequest();
        request.setName(config.getAgentName());
        request.setVersion(config.getAgentVersion());
        request.setCapabilities(registry.declarations());
        api.register(request);
    }

    /**
     * WebSocket if we can get one, polling if we cannot.
     *
     * <p>The fallback matters because some corporate proxies terminate TLS, inspect the traffic, and
     * do not support the {@code Upgrade: websocket} handshake. A plain tunnelling proxy cannot break
     * it — the upgrade happens inside TLS — so this affects a minority of environments, but it is
     * the difference between "works everywhere" and "works at most customers".
     *
     * <p>The degrade is logged at WARN on purpose. An agent silently running in polling mode, with
     * an operator wondering why steps take ten seconds, is a support call nobody enjoys.
     */
    private Transport chooseTransport() throws Exception {
        if (config.getTransportMode() == TransportMode.POLL) {
            return new PollingTransport(api, config.getPollInterval());
        }
        try {
            final ConnectResponse connect = api.connect();
            if (connect != null && connect.getWebsocketUrl() != null && !connect.getWebsocketUrl().isBlank()) {
                return new WebSocketTransport(api, connect);
            }
            if (config.getTransportMode() == TransportMode.WEBSOCKET) {
                throw new IllegalStateException(
                        "transportMode=WEBSOCKET but " + config.getVendor().getName()
                                + " offered no WebSocket endpoint");
            }
        } catch (Exception e) {
            if (config.getTransportMode() == TransportMode.WEBSOCKET) {
                throw e;
            }
            log.warn("Could not establish a WebSocket to {} ({}). Falling back to polling every {}s — "
                            + "steps will be picked up on a timer rather than pushed. If this persists, "
                            + "an outbound proxy is most likely refusing the upgrade.",
                    config.getVendor().getName(), e.getMessage(), config.getPollInterval().toSeconds());
        }
        return new PollingTransport(api, config.getPollInterval());
    }

    /** Hands a step to the worker pool so the transport thread is never blocked. */
    private void submit(RemoteStep step) {
        workers.submit(() -> execute(step));
    }

    private void execute(RemoteStep step) {
        final Optional<Capability> capability = registry.find(step.getCapability());
        if (capability.isEmpty()) {
            // A real and expected case: a runbook references a capability this agent does not
            // provide. Report it rather than staying silent, or the run parks forever with nothing
            // saying why.
            log.warn("Step {} asked for capability {}, which is not registered here. Registered: {}",
                    step.getTaskId(), step.getCapability(), registry.declarations().size());
            report(StepResult.failure(step,
                    "Capability " + step.getCapability() + " is not implemented by this tool service"));
            return;
        }
        final long startedAt = System.nanoTime();
        try {
            final Map<String, Object> output = capability.get().execute(step.getInput());
            final long ms = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("Step ok: task={} capability={} took={}ms outputKeys={}",
                    step.getTaskId(), step.getCapability(), ms,
                    output == null ? 0 : output.size());
            report(StepResult.success(step, output));
        } catch (Exception e) {
            final long ms = (System.nanoTime() - startedAt) / 1_000_000L;
            log.error("Step failed: task={} capability={} took={}ms: {}",
                    step.getTaskId(), step.getCapability(), ms, e.getMessage(), e);
            report(StepResult.failure(step, message(e)));
        }
    }

    /**
     * Reports the outcome, retrying briefly.
     *
     * <p>Worth the retry: the work has already been done. Losing the result means Oppex never
     * learns the step finished and the runbook stalls, so a transient network blip at exactly the
     * wrong moment should not waste a completed step.
     */
    private void report(StepResult result) {
        Duration wait = Duration.ofSeconds(1);
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                api.reportResult(result);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (attempt == 4) {
                    log.error("Gave up reporting task {} after {} attempts: {}. "
                                    + "The workflow will stay parked on this step.",
                            result.getTaskId(), attempt, e.getMessage());
                    return;
                }
                log.warn("Could not report task {} (attempt {}): {}. Retrying in {}s.",
                        result.getTaskId(), attempt, e.getMessage(), wait.toSeconds());
                if (!sleep(wait)) {
                    return;
                }
                wait = doubled(wait);
            }
        }
    }

    /** An exception's message is often null; a class name is more use than "null" to an operator. */
    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    private Duration doubled(Duration current) {
        final Duration next = current.multipliedBy(2);
        return next.compareTo(config.getMaxReconnectDelay()) > 0 ? config.getMaxReconnectDelay() : next;
    }

    /** @return false if interrupted, so callers can unwind rather than loop */
    private boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        stopping = true;
        final Transport transport = current.get();
        if (transport != null) {
            transport.stop();
        }
        workers.shutdown();
        try {
            // Give a step in flight a chance to finish and report. Cutting it off mid-execution
            // leaves the workflow parked with no result at all, which is worse than waiting.
            if (!workers.awaitTermination(20, TimeUnit.SECONDS)) {
                log.warn("Some steps were still running at shutdown; their results may be lost");
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
        api.close();
        log.info("Agent stopped");
    }
}
