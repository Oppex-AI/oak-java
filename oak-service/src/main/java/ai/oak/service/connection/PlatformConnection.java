/*
 * Copyright 2026 Oak Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.oak.service.connection;

import ai.oak.service.AgentStatus;
import ai.oak.service.client.CapabilityDeclaration;
import ai.oak.service.client.PairRequest;
import ai.oak.service.client.PairResponse;
import ai.oak.service.client.PairStatus;
import ai.oak.service.client.RemoteStep;
import ai.oak.service.client.RemoteStepResultRequest;
import ai.oak.service.client.TaskStatus;
import ai.oak.service.client.ToolServiceClient;
import ai.oak.service.client.ToolServiceClient.UnauthorizedException;
import ai.oak.service.client.ToolServiceRegistrationRequest;
import ai.oak.tools.ToolRegistry;
import ai.oak.tools.ToolResult;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the full connection lifecycle for one platform on its own control thread:
 * pair (if needed) → wait for admin approval → register → poll → execute → report, and re-pair when the
 * token is revoked. Steps execute on a shared worker pool; only the control flow is per-platform.
 *
 * <p>The mechanism is identical for every platform — Oppex is just the first configured one. Secrets are
 * never logged; ids are masked. The pairing poll loop logs one line per state transition, never per poll.
 */
public final class PlatformConnection {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformConnection.class);

    private static final long PAIR_POLL_INITIAL_MS = 5_000;
    private static final long PAIR_POLL_MAX_MS = 30_000;
    private static final long RETRY_SHORT_MS = 5_000;

    /**
     * The dependencies shared by every platform connection; bundled so the constructor stays small.
     * {@code env} maps a capability to the extra environment its tool runs with (the client's AWS / docker
     * settings); {@code active} says whether a capability's preconditions are met (only active ones are
     * advertised and run).
     */
    public record Context(ConnectionStore store, ToolRegistry registry, ExecutorService workers, AgentStatus status,
            ServiceId service, Function<String, Map<String, String>> env, java.util.function.Predicate<String> active) {
    }

    /** How this service names itself at registration. */
    public record ServiceId(String name, String version) {
    }

    private final String name;
    private final PlatformState state;
    private final Context ctx;
    private final long pollIntervalMs;
    private final ScheduledExecutorService control;

    private volatile boolean running;
    private volatile ToolServiceClient client;
    private volatile Future<?> pollTask;

    public PlatformConnection(final String name, final PlatformState state, final Context ctx, final long pollIntervalMs) {
        this.name = name;
        this.state = state;
        this.ctx = ctx;
        this.pollIntervalMs = Math.max(1_000, pollIntervalMs);
        this.control = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "oak-conn-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        running = true;
        control.execute(this::connectCycle);
    }

    public void stop() {
        running = false;
        control.shutdownNow();
        closeClient();
    }

    /** UI edited this platform's settings: restart the cycle from scratch. */
    public void reconnect() {
        if (!running) {
            return;
        }
        cancelPolling();
        control.execute(this::connectCycle);
    }

    // --- lifecycle (all on the control thread) -----------------------------------------------------

    private void connectCycle() {
        if (!running) {
            return;
        }
        closeClient();
        final String baseUrl = state.baseUrl();
        if (baseUrl == null) {
            idle("no base URL configured");
            return;
        }
        ctx.status().meta(name, baseUrl, state.clientId(), state.token() != null);
        client = new ToolServiceClient(baseUrl, name);

        // Approval-based pairing is the only way to a token. Reuse a
        // previously paired token if we have one; otherwise pair (which needs a client id + secret).
        String token = state.token();
        if (token == null) {
            if (state.clientId() == null || state.pairingSecret() == null) {
                idle("set a client id and pairing secret to pair");
                return;
            }
            token = pair();
            if (token == null) {
                return; // pending / terminal / stopped — pair() handled status + any reschedule
            }
        }
        client.setToken(token);
        registerAndPoll();
    }

    private void idle(final String reason) {
        ctx.status().phase(name, ConnectionPhase.IDLE, reason);
        LOG.warn("[{}] idle: {}", name, reason);
    }

    private String pair() {
        try {
            if (state.pairingId() == null && !create()) {
                return null;
            }
            pollForApproval();
            return state.token();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            ctx.status().error(name, ConnectionPhase.ERROR, reason(e));
            LOG.warn("[{}] pairing call failed (will retry): {}", name, reason(e));
            scheduleRetry(RETRY_SHORT_MS);
            return null;
        }
    }

    /** A human reason for an exception, falling back to its type when the message is null. */
    private static String reason(final Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** CREATE: prove identity with the pre-shared secret. true if now pending (go poll); false if resolved. */
    private boolean create() throws IOException, InterruptedException {
        ctx.status().phase(name, ConnectionPhase.PAIRING, "starting pairing");
        LOG.info("[{}] pairing (clientId {})", name, mask(state.clientId()));
        final PairResponse r = client
                .pair(PairRequest.create(state.clientId(), state.pairingSecret(), ctx.service().name(), ctx.service().version()));
        if (r.status() == PairStatus.PENDING_APPROVAL && r.pairingId() != null) {
            state.setPairingId(r.pairingId());
            ctx.store().persist(state);
            ctx.status().phase(name, ConnectionPhase.PENDING_APPROVAL, "waiting for admin approval");
            LOG.info("[{}] waiting for admin approval (clientId {})", name, mask(state.clientId()));
            return true;
        }
        settle(r);
        return false;
    }

    /** Poll until approved or a terminal status. Backs off 5s → 30s; logs no line per poll. */
    private void pollForApproval() throws IOException, InterruptedException {
        long backoff = PAIR_POLL_INITIAL_MS;
        while (running && state.token() == null && state.pairingId() != null) {
            Thread.sleep(backoff);
            if (!running) {
                return;
            }
            final PairResponse r = client.pair(PairRequest.poll(state.clientId(), state.pairingId(), state.pairingSecret()));
            if (r.status() == PairStatus.PENDING_APPROVAL) {
                backoff = Math.min(backoff * 2, PAIR_POLL_MAX_MS);
            } else {
                settle(r);
            }
        }
    }

    /** Resolve a non-pending pairing response: approve, or handle the terminal status per its meaning. */
    private void settle(final PairResponse r) {
        switch (r.status()) {
            case APPROVED -> approve(r);
            case EXPIRED -> {
                state.clearPairingId();
                ctx.store().persist(state);
                ctx.status().phase(name, ConnectionPhase.PAIRING, "pending request expired; re-creating");
                LOG.info("[{}] pairing expired — starting a fresh pairing", name);
                scheduleRetry(RETRY_SHORT_MS);
            }
            case DISCONNECTED -> {
                state.clearToken();
                state.clearPairingId();
                ctx.store().persist(state);
                ctx.status().phase(name, ConnectionPhase.DISCONNECTED, "disconnected by admin; re-pair from Settings");
                LOG.warn("[{}] disconnected by admin — re-pair from the Settings page", name);
            }
            case REJECTED -> fail("rejected: unknown/disabled client id or wrong pairing secret");
            default -> fail("unexpected pairing status: " + r.status());
        }
    }

    private void approve(final PairResponse r) {
        if (r.token() == null) {
            fail("approved but no token was delivered — re-pair from Settings");
            return;
        }
        state.setToken(r.token(), r.connectionId());
        state.clearPairingId();
        ctx.store().persist(state);
        LOG.info("[{}] pairing approved (connectionId {})", name, mask(r.connectionId()));
    }

    /** A permanent pairing failure: surface it and do not retry blindly — the operator must act. */
    private void fail(final String reason) {
        state.clearPairingId();
        ctx.store().persist(state);
        ctx.status().error(name, ConnectionPhase.ERROR, reason);
        LOG.error("[{}] pairing failed: {}", name, reason);
    }

    private void registerAndPoll() {
        final List<CapabilityDeclaration> declared = ctx.registry().all().stream().filter(t -> ctx.active().test(t.capability()))
                .map(t -> new CapabilityDeclaration(t.capability(), t.permission(), t.description())).toList();
        try {
            client.register(new ToolServiceRegistrationRequest(ctx.service().name(), ctx.service().version(), declared));
            ctx.status().connected(name, state.baseUrl(), state.clientId(), state.connectionId(), true);
            LOG.info("[{}] registered: {} capabilities declared", name, declared.size());
            pollTask = control.scheduleWithFixedDelay(this::pollOnce, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
        } catch (UnauthorizedException e) {
            onUnauthorized("registration");
        } catch (IOException e) {
            ctx.status().error(name, ConnectionPhase.ERROR, e.getMessage());
            LOG.warn("[{}] registration failed (will retry): {}", name, e.getMessage());
            scheduleRetry(RETRY_SHORT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pollOnce() {
        try {
            client.nextStep().ifPresent(step -> ctx.workers().submit(() -> handle(step)));
        } catch (UnauthorizedException e) {
            cancelPolling();
            onUnauthorized("poll");
        } catch (IOException e) {
            LOG.warn("[{}] poll failed (will retry): {}", name, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A 401 on register/steps means the admin disconnected us (token revoked): drop it and re-pair. */
    private void onUnauthorized(final String where) {
        state.clearToken();
        ctx.store().persist(state);
        ctx.status().phase(name, ConnectionPhase.DISCONNECTED, "disconnected by platform; re-pairing");
        LOG.warn("[{}] connection disconnected by platform ({}) — re-pairing required", name, where);
        scheduleRetry(RETRY_SHORT_MS);
    }

    // --- step execution (on the shared worker pool) ------------------------------------------------

    private void handle(final RemoteStep step) {
        LOG.info("[{}] step {}/{} received: {}", name, step.workflowId(), step.taskId(), step.capability());
        final Outcome o = runAndReport(step);
        LOG.info("[{}] step {}/{} {} -> {}{}", name, step.workflowId(), step.taskId(), step.capability(), o.label(),
                o.note() == null ? "" : " (" + o.note() + ")");
        ctx.status().recordStep(new AgentStatus.StepRecord(Instant.now(), name, step.workflowId(), step.taskId(),
                step.capability(), o.label(), o.note()));
    }

    private Outcome runAndReport(final RemoteStep step) {
        try {
            if (!ctx.active().test(step.capability())) {
                final String note = "capability not active (missing configuration)";
                client.reportResult(failed(step, note));
                return new Outcome("FAILED", note);
            }
            final Map<String, String> env = ctx.env() == null ? Map.of() : ctx.env().apply(step.capability());
            return report(step, ctx.registry().execute(step.capability(), step.input(), env).orElse(null));
        } catch (UnauthorizedException e) {
            control.execute(() -> {
                cancelPolling();
                onUnauthorized("report");
            });
            return new Outcome("FAILED", "token rejected while reporting");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("[{}] step {}/{} reporting failed: {}", name, step.workflowId(), step.taskId(), e.getMessage());
            return new Outcome("FAILED", "could not report result: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("[{}] step {}/{} threw: {}", name, step.workflowId(), step.taskId(), e.getMessage());
            final String note = "execution error: " + e.getMessage();
            reportSafely(failed(step, note));
            return new Outcome("FAILED", note);
        }
    }

    private void reportSafely(final RemoteStepResultRequest report) {
        try {
            client.reportResult(report);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("[{}] could not report failure for {}/{}: {}", name, report.workflowId(), report.taskId(), e.getMessage());
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private record Outcome(String label, String note) {
    }

    /** Runs the report call for a resolved result (or a missing tool) and returns the outcome. */
    private Outcome report(final RemoteStep step, final ToolResult result) throws IOException, InterruptedException {
        if (result == null) {
            final String note = "no tool registered for capability " + step.capability();
            client.reportResult(failed(step, note));
            return new Outcome("FAILED", note);
        }
        final boolean ok = result.success();
        final String note = ok ? null : (result.timedOut() ? "timed out" : "exit " + result.exitCode());
        client.reportResult(new RemoteStepResultRequest(step.workflowId(), step.taskId(),
                ok ? TaskStatus.SUCCESS : TaskStatus.FAILED, outputOf(result), note));
        return new Outcome(ok ? "SUCCESS" : "FAILED", note);
    }

    private void scheduleRetry(final long delayMs) {
        if (running) {
            control.schedule(this::connectCycle, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelPolling() {
        final Future<?> task = pollTask;
        if (task != null) {
            task.cancel(false);
            pollTask = null;
        }
    }

    private void closeClient() {
        final ToolServiceClient c = client;
        if (c != null) {
            c.close();
            client = null;
        }
    }

    private static RemoteStepResultRequest failed(final RemoteStep step, final String note) {
        return new RemoteStepResultRequest(step.workflowId(), step.taskId(), TaskStatus.FAILED, Map.of(), note);
    }

    private static Map<String, Object> outputOf(final ToolResult result) {
        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("exitCode", result.exitCode());
        output.put("stdout", result.stdout());
        output.put("stderr", result.stderr());
        if (result.timedOut()) {
            output.put("timedOut", true);
        }
        return output;
    }

    private static String mask(final String id) {
        if (id == null || id.isBlank()) {
            return "—";
        }
        final String s = id.trim();
        return s.length() <= 4 ? "****" : s.substring(0, 4) + "…";
    }
}
