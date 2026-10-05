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
import ai.oak.service.ToolExecutor;
import ai.oak.service.client.CapabilityDeclaration;
import ai.oak.service.client.DiscoverySnapshot;
import ai.oak.service.client.DiscoverySnapshotResponse;
import ai.oak.service.client.PairRequest;
import ai.oak.service.client.PairResponse;
import ai.oak.service.client.PairStatus;
import ai.oak.service.client.RemoteStep;
import ai.oak.service.client.RemoteStepResultRequest;
import ai.oak.service.client.StepError;
import ai.oak.service.client.ToolServiceClient;
import ai.oak.service.client.ToolServiceClient.UnauthorizedException;
import ai.oak.service.client.ToolServiceRegistrationRequest;
import ai.oak.tools.ToolRegistry;
import ai.oak.tools.ToolResult;
import java.io.IOException;
import java.time.Instant;
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
    private static final long DISCOVERY_INITIAL_MS = 3_000;

    /** The AWS capability whose active-state gates discovery: no role linked ⇒ nothing to enumerate. */
    private static final String DISCOVERY_PROBE = "AWS_EC2_DESCRIBE_INSTANCES";

    /**
     * The dependencies shared by every platform connection; bundled so the constructor stays small.
     * {@code env} maps a capability to the extra environment its tool runs with (the client's AWS / docker
     * settings); {@code active} says whether a capability's preconditions are met (only active ones are
     * advertised and run); {@code discovery} produces the infrastructure snapshot to report, or is null
     * when discovery is disabled.
     */
    public record Context(ConnectionStore store, ToolRegistry registry, ExecutorService workers, AgentStatus status,
            ServiceId service, Function<String, Map<String, String>> env, java.util.function.Predicate<String> active,
            java.util.function.Supplier<DiscoverySnapshot> discovery, java.util.function.Supplier<String> region) {
    }

    /** How this service names itself at registration. */
    public record ServiceId(String name, String version) {
    }

    private final String name;
    private final PlatformState state;
    private final Context ctx;
    private final long pollIntervalMs;
    private final long discoveryIntervalMs;
    private final ScheduledExecutorService control;

    private volatile boolean running;
    private volatile ToolServiceClient client;
    private volatile Future<?> pollTask;
    private volatile Future<?> discoveryTask;

    public PlatformConnection(final String name, final PlatformState state, final Context ctx, final long pollIntervalMs,
            final long discoveryIntervalMs) {
        this.name = name;
        this.state = state;
        this.ctx = ctx;
        this.pollIntervalMs = Math.max(1_000, pollIntervalMs);
        this.discoveryIntervalMs = Math.max(30_000, discoveryIntervalMs);
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
        final String token = acquireToken();
        if (token == null) {
            return; // idle / pending / terminal / stopped — acquireToken handled status + any reschedule
        }
        client.setToken(token);
        registerAndPoll();
    }

    /**
     * The token to authenticate with: a persisted one from a previous approval (a restart resumes with no
     * re-pairing and no new approval), or a freshly paired one. Null when not ready — idle for missing
     * config, or pending/terminal after a pairing attempt (each having set status already).
     */
    private String acquireToken() {
        final String existing = state.token();
        if (existing != null) {
            LOG.info("[{}] resuming with a persisted token — no pairing needed (connectionId {})", name,
                    mask(state.connectionId()));
            return existing;
        }
        if (state.clientId() == null || state.pairingSecret() == null) {
            idle("set a client id and pairing secret to pair");
            return null;
        }
        return pair();
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
                .map(t -> new CapabilityDeclaration(t.capability(), t.permission(), t.description(), t.inputKeys(),
                        t.approvalCandidateSource()))
                .toList();
        try {
            client.register(
                    new ToolServiceRegistrationRequest(ctx.service().name(), ctx.service().version(), declared, oakRegion()));
            ctx.status().connected(name, state.baseUrl(), state.clientId(), state.connectionId(), true);
            LOG.info("[{}] registered: {} capabilities declared", name, declared.size());
            pollTask = control.scheduleWithFixedDelay(this::pollOnce, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
            scheduleDiscovery();
        } catch (UnauthorizedException e) {
            onUnauthorized("registration");
        } catch (IOException e) {
            ctx.status().error(name, ConnectionPhase.ERROR, reason(e));
            LOG.warn("[{}] registration failed (will retry): {}", name, reason(e));
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
            LOG.warn("[{}] poll failed (will retry): {}", name, reason(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Report an infra snapshot shortly after connecting, then on the discovery interval — when enabled. */
    private void scheduleDiscovery() {
        if (ctx.discovery() == null) {
            return;
        }
        discoveryTask = control.scheduleWithFixedDelay(this::discoverOnce, DISCOVERY_INITIAL_MS, discoveryIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    /** Collect the full infrastructure snapshot and report it. Skips quietly until AWS is configured. */
    private void discoverOnce() {
        if (!running || !ctx.active().test(DISCOVERY_PROBE)) {
            return; // nothing to enumerate yet (no AWS role linked) — try again next cycle
        }
        try {
            final DiscoverySnapshot snapshot = ctx.discovery().get();
            final DiscoverySnapshotResponse ack = client.postDiscovery(snapshot);
            ctx.status().recordDiscovery(name, snapshot, ack != null && ack.changed());
            LOG.info("[{}] discovery: reported {} resource(s){}", name, snapshot.resources().size(), changedNote(ack));
        } catch (UnauthorizedException e) {
            cancelPolling();
            onUnauthorized("discovery");
        } catch (IOException e) {
            LOG.warn("[{}] discovery report failed (will retry next cycle): {}", name, reason(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String changedNote(final DiscoverySnapshotResponse ack) {
        if (ack == null) {
            return "";
        }
        return ack.changed() ? " (changed)" : " (unchanged)";
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
        LOG.info("[{}] step {}/{} received: {} input={}", name, step.workflowId(), step.taskId(), step.capability(),
                step.input());
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
                client.reportResult(fail(step, "INACTIVE", note));
                return new Outcome("FAILED", note);
            }
            final Map<String, String> env = ctx.env() == null ? Map.of() : ctx.env().apply(step.capability());
            final Map<String, Object> input = ToolExecutor.withRegion(step.input(), oakRegion());
            return report(step, ctx.registry().execute(step.capability(), input, env).orElse(null));
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
            LOG.warn("[{}] step {}/{} threw: {}", name, step.workflowId(), step.taskId(), reason(e));
            final String note = "execution error: " + reason(e);
            reportSafely(fail(step, "EXECUTION_ERROR", note));
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

    /** OAK's single operating region (connection context), or null when it can't be determined. */
    private String oakRegion() {
        return ctx.region() == null ? null : ctx.region().get();
    }

    private record Outcome(String label, String note) {
    }

    /** Runs the report call for a resolved result (or a missing tool) and returns the outcome. */
    private Outcome report(final RemoteStep step, final ToolResult result) throws IOException, InterruptedException {
        if (result == null) {
            final String note = "no tool registered for capability " + step.capability();
            client.reportResult(fail(step, "NO_TOOL", note));
            return new Outcome("FAILED", note);
        }
        final boolean ok = result.success();
        final StepError error = StepResultMapper.error(result);
        if (!ok) {
            LOG.warn("[{}] step {}/{} {} failed ({}); ran: {} | stderr: {}", name, step.workflowId(), step.taskId(),
                    step.capability(), error.code(), ctx.registry().render(step.capability(), step.input()).orElse("<no render>"),
                    snippet(result.stderr()));
        }
        client.reportResult(new RemoteStepResultRequest(step.workflowId(), step.taskId(), step.executionId(), ok,
                StepResultMapper.output(result), error));
        return new Outcome(ok ? "SUCCESS" : "FAILED", ok ? null : error.message());
    }

    private void scheduleRetry(final long delayMs) {
        if (running) {
            control.schedule(this::connectCycle, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelPolling() {
        final Future<?> poll = pollTask;
        if (poll != null) {
            poll.cancel(false);
            pollTask = null;
        }
        final Future<?> discovery = discoveryTask;
        if (discovery != null) {
            discovery.cancel(false);
            discoveryTask = null;
        }
    }

    private void closeClient() {
        final ToolServiceClient c = client;
        if (c != null) {
            c.close();
            client = null;
        }
    }

    /** A failure envelope with no output — for cases that never ran a tool (inactive, no tool, threw). */
    private static RemoteStepResultRequest fail(final RemoteStep step, final String code, final String message) {
        return new RemoteStepResultRequest(step.workflowId(), step.taskId(), step.executionId(), false, Map.of(),
                new StepError(code, message));
    }

    /** A one-line, length-bounded view of command output for a log line. */
    private static String snippet(final String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        final String flat = text.strip().replace('\n', ' ');
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }

    private static String mask(final String id) {
        if (id == null || id.isBlank()) {
            return "—";
        }
        final String s = id.trim();
        return s.length() <= 4 ? "****" : s.substring(0, 4) + "…";
    }
}
