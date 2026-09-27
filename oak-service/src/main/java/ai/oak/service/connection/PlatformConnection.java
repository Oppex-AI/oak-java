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
    private static final long RETRY_LONG_MS = 60_000;
    private static final long FRESH_PAIR_DELAY_MS = 15_000;

    /** The dependencies shared by every platform connection; bundled so the constructor stays small. */
    public record Context(ConnectionStore store, ToolRegistry registry, ExecutorService workers, AgentStatus status,
            String serviceName, String serviceVersion, long pollIntervalMs) {
    }

    private final String name;
    private final PlatformState state;
    private final Context ctx;
    private final long pollIntervalMs;
    private final ScheduledExecutorService control;

    private volatile boolean running;
    private volatile ToolServiceClient client;
    private volatile Future<?> pollTask;

    public PlatformConnection(final String name, final PlatformState state, final Context ctx) {
        this.name = name;
        this.state = state;
        this.ctx = ctx;
        this.pollIntervalMs = Math.max(1_000, ctx.pollIntervalMs());
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

        String token = state.effectiveToken();
        if (token == null) {
            if (state.clientId() == null) {
                idle("set a client id (to pair) or an API key");
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
            if (state.pairingId() == null || state.pairingSecret() == null) {
                if (!beginPairing()) {
                    return null;
                }
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

    /** FIRST pairing request. true to proceed (pending, or already approved); false if terminal (rescheduled). */
    private boolean beginPairing() throws IOException, InterruptedException {
        ctx.status().phase(name, ConnectionPhase.PAIRING, "starting pairing");
        LOG.info("[{}] pairing (clientId {})", name, mask(state.clientId()));
        final PairResponse first = client.pair(PairRequest.first(state.clientId(), ctx.serviceName(), ctx.serviceVersion()));
        if (first.status() == PairStatus.PENDING_APPROVAL && first.pairingId() != null) {
            state.setPairing(first.pairingId(), first.pairingSecret());
            ctx.store().persist(state);
            ctx.status().phase(name, ConnectionPhase.PENDING_APPROVAL, "waiting for admin approval");
            LOG.info("[{}] waiting for admin approval (clientId {})", name, mask(state.clientId()));
            return true;
        }
        if (first.status() == PairStatus.APPROVED && first.token() != null) {
            approve(first);
            return true;
        }
        terminal(first.status());
        return false;
    }

    /** Poll until approved or terminal. Backs off 5s → 30s; logs no line per poll. */
    private void pollForApproval() throws IOException, InterruptedException {
        long backoff = PAIR_POLL_INITIAL_MS;
        while (running && state.token() == null && state.pairingId() != null) {
            Thread.sleep(backoff);
            if (!running) {
                return;
            }
            final PairResponse r = client.pair(PairRequest.poll(state.clientId(), state.pairingId(), state.pairingSecret()));
            if (r.status() == PairStatus.APPROVED) {
                approve(r);
            } else if (r.status() == PairStatus.PENDING_APPROVAL) {
                backoff = Math.min(backoff * 2, PAIR_POLL_MAX_MS);
            } else {
                terminal(r.status());
            }
        }
    }

    private void approve(final PairResponse r) {
        state.setToken(r.token(), r.connectionId());
        state.clearPairing();
        ctx.store().persist(state);
        LOG.info("[{}] pairing approved (connectionId {})", name, mask(r.connectionId()));
    }

    private void terminal(final PairStatus s) {
        state.clearPairing();
        ctx.store().persist(state);
        ctx.status().phase(name, ConnectionPhase.PAIRING, s + "; starting a fresh pairing");
        LOG.info("[{}] pairing {} — starting a fresh pairing shortly", name, s);
        scheduleRetry(FRESH_PAIR_DELAY_MS);
    }

    private void registerAndPoll() {
        final List<CapabilityDeclaration> declared = ctx.registry().all().stream()
                .map(t -> new CapabilityDeclaration(t.capability(), t.permission(), t.description())).toList();
        try {
            client.register(new ToolServiceRegistrationRequest(ctx.serviceName(), ctx.serviceVersion(), declared));
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

    private void onUnauthorized(final String where) {
        if (state.token() != null) {
            state.clearToken();
            ctx.store().persist(state);
            ctx.status().phase(name, ConnectionPhase.DISCONNECTED, "disconnected by platform; re-pairing");
            LOG.warn("[{}] connection disconnected by platform ({}) — re-pairing required", name, where);
            scheduleRetry(RETRY_SHORT_MS);
        } else {
            ctx.status().error(name, ConnectionPhase.ERROR, "API key rejected");
            LOG.error("[{}] bootstrap API key rejected ({}); check the key", name, where);
            scheduleRetry(RETRY_LONG_MS);
        }
    }

    // --- step execution (on the shared worker pool) ------------------------------------------------

    private void handle(final RemoteStep step) {
        final Outcome o = runAndReport(step);
        ctx.status().recordStep(new AgentStatus.StepRecord(Instant.now(), name, step.workflowId(), step.taskId(),
                step.capability(), o.label(), o.note()));
    }

    private Outcome runAndReport(final RemoteStep step) {
        try {
            return report(step, ctx.registry().execute(step.capability(), step.input()).orElse(null));
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
