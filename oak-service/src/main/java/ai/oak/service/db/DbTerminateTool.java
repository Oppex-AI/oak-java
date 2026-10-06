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
package ai.oak.service.db;

import ai.oak.service.db.DbGateway.TerminateResult;
import ai.oak.tools.ApprovalCandidateSource;
import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * DB_TERMINATE (DESTRUCTIVE) — the safe kill. Oppex dispatches it only after a human approved it, injecting
 * the identity (or identities) resolved from a persisted DB_LIST_ACTIVITY snapshot. OAK re-reads each
 * backend at terminate time and issues {@code pg_terminate_backend(pid)} ONLY IF the live
 * {@code backend_start} still equals the requested one — never by pid alone. A reused pid is
 * {@code pid_reused} (we killed nothing), which is correct and safe. Idempotent.
 *
 * <p>{@code identity} may be a single {@code {pid, backendStart}} object or an array of them. Multiple
 * targets are fired with a small bounded worker pool (default 3, {@code OAK_DB_TERMINATE_MAX_CONCURRENCY})
 * and then verified; the result carries a per-identity {@code results} array. The batch succeeds
 * ({@code success:true}) whenever it could execute — individual {@code already_gone}/{@code pid_reused}/
 * {@code permission_denied} outcomes live in {@code results}; {@code success:false} only when it could not
 * connect at all ({@code NO_CONNECTION_CONFIGURED} / {@code DB_UNREACHABLE}). Works on RDS/Aurora (pure SQL).
 */
public final class DbTerminateTool implements Tool {

    private final DbConnectionProvider provider;

    public DbTerminateTool(final DbConnectionProvider provider) {
        this.provider = provider;
    }

    @Override
    public String capability() {
        return "DB_TERMINATE";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.DESTRUCTIVE;
    }

    @Override
    public String description() {
        return "Terminate a Postgres backend by identity (pid + backend_start), only if it still matches.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("dbIdentifier", "identity", "queryFingerprint", "verifyOnly");
    }

    /**
     * Select-then-approve: the target is ONE backend the operator picks from DB_LIST_ACTIVITY's output.
     * Each {@code activities[]} row carries {@code identity} ({@code pid}+{@code backendStart}); the chosen
     * row's {@code identity} is injected back into this tool's {@code identity} input, and the top-level row
     * fields are shown to the approver (the SQL {@code query} is masked).
     */
    @Override
    public ApprovalCandidateSource approvalCandidateSource() {
        return new ApprovalCandidateSource("DB_LIST_ACTIVITY", "activities", "identity", "pid", "identity", List.of("query",
                "durationSeconds", "state", "datname", "applicationName", "waitEventType", "waitEvent", "blockedBy"),
                List.of("query"));
    }

    @Override
    public String render(final Map<String, Object> input) {
        final List<Map<?, ?>> ids = identities(input.get("identity"));
        final String verb = DbTools.boolOf(input.get("verifyOnly")) ? "verify" : "pg_terminate_backend";
        final String on = " on " + DbTools.str(input.get("dbIdentifier"));
        if (ids.size() == 1) {
            final Map<?, ?> id = ids.get(0);
            return verb + " pid=" + DbTools.str(id.get("pid")) + " backend_start=" + DbTools.str(id.get("backendStart")) + on;
        }
        return verb + " " + ids.size() + " backend(s)" + on;
    }

    @Override
    public ToolResult execute(final Map<String, Object> input) {
        final String dbIdentifier = DbTools.str(input.get("dbIdentifier"));
        final String region = DbTools.str(input.get("region"));
        final boolean verifyOnly = DbTools.boolOf(input.get("verifyOnly"));
        final List<Map<?, ?>> ids = identities(input.get("identity"));
        if (ids.isEmpty()) {
            return DbTools.error("TERMINATE_FAILED", "identity is required — a {pid, backendStart} object or an array of them");
        }
        try {
            if (ids.size() == 1) {
                try (DbGateway gateway = provider.open(dbIdentifier, region)) {
                    return batch(List.of(killOne(gateway, ids.get(0), verifyOnly)));
                }
            }
            try (DbGateway probe = provider.open(dbIdentifier, region)) {
                probe.serverTime(); // validate connectivity once — batch-fatal if it throws
            }
            return batch(runConcurrently(dbIdentifier, region, ids, verifyOnly));
        } catch (DbAccessException e) {
            return DbTools.error(e.code(), e.getMessage()); // could not execute at all → success:false
        }
    }

    /** {@code identity} as a list of 1..N — a single object becomes a one-element batch. */
    private static List<Map<?, ?>> identities(final Object raw) {
        final List<Map<?, ?>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (final Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add(m);
                }
            }
        } else if (raw instanceof Map<?, ?> m) {
            out.add(m);
        }
        return out;
    }

    /** Fire the kills on a small bounded pool (each worker its own connection), then collect every outcome. */
    private List<Map<String, Object>> runConcurrently(final String dbIdentifier, final String region, final List<Map<?, ?>> ids,
            final boolean verifyOnly) {
        final ExecutorService pool = Executors.newFixedThreadPool(Math.min(maxConcurrency(), ids.size()), r -> {
            final Thread t = new Thread(r, "oak-db-terminate");
            t.setDaemon(true);
            return t;
        });
        try {
            final List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (final Map<?, ?> id : ids) {
                futures.add(pool.submit(() -> killOneSafely(dbIdentifier, region, id, verifyOnly)));
            }
            final List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                results.add(await(futures.get(i), ids.get(i)));
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private Map<String, Object> killOneSafely(final String dbIdentifier, final String region, final Map<?, ?> id,
            final boolean verifyOnly) {
        try (DbGateway gateway = provider.open(dbIdentifier, region)) {
            return killOne(gateway, id, verifyOnly);
        } catch (DbAccessException e) {
            return perIdError(id, mapCode(e.code()), e.getMessage());
        }
    }

    private static Map<String, Object> await(final Future<Map<String, Object>> future, final Map<?, ?> id) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return perIdError(id, "error", "interrupted");
        } catch (ExecutionException e) {
            return perIdError(id, "error", String.valueOf(e.getCause()));
        }
    }

    /** Terminate (or verify) one identity on an open gateway, mapped to a per-id result. */
    private static Map<String, Object> killOne(final DbGateway gateway, final Map<?, ?> identity, final boolean verifyOnly) {
        final int pid = DbTools.intOf(identity.get("pid"), -1);
        final String backendStart = DbTools.str(identity.get("backendStart"));
        if (pid < 0 || backendStart == null) {
            return perIdError(identity, "error", "identity.pid and identity.backendStart are required");
        }
        try {
            final ToolResult r = decide(gateway, pid, backendStart, verifyOnly);
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("pid", pid);
            out.put("backendStart", backendStart);
            if (r.success()) {
                out.put("status", statusOf(DbTools.str(r.data().get("outcome")), DbTools.str(r.data().get("reason"))));
                out.put("observedBackendStart", r.data().get("observedBackendStart"));
            } else {
                out.put("status", "error");
                out.put("message", DbTools.str(r.data().get("errorMessage")));
            }
            return out;
        } catch (DbAccessException e) {
            return perIdError(identity, mapCode(e.code()), e.getMessage());
        }
    }

    private static Map<String, Object> perIdError(final Map<?, ?> identity, final String status, final String message) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("pid", DbTools.intOf(identity.get("pid"), -1));
        out.put("backendStart", DbTools.str(identity.get("backendStart")));
        out.put("status", status);
        out.put("message", message);
        return out;
    }

    private static String mapCode(final String code) {
        return "PERMISSION_DENIED".equals(code) ? "permission_denied" : "error";
    }

    /** Map the single-kill {@code decide} outcome to the batch per-id status vocabulary. */
    private static String statusOf(final String outcome, final String reason) {
        if ("terminated".equals(outcome)) {
            return "terminated";
        }
        if ("matched".equals(outcome)) {
            return "matched";
        }
        if ("already_gone".equals(outcome)) {
            return "pid_reused".equals(reason) ? "pid_reused" : "already_gone";
        }
        return "error";
    }

    /** Wrap the per-id results with a small summary. Always a success envelope — the tool executed. */
    private static ToolResult batch(final List<Map<String, Object>> results) {
        final long terminated = results.stream().filter(r -> "terminated".equals(r.get("status"))).count();
        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("requested", results.size());
        output.put("terminated", (int) terminated);
        output.put("results", results);
        return DbTools.ok(output);
    }

    private static int maxConcurrency() {
        final String env = System.getenv("OAK_DB_TERMINATE_MAX_CONCURRENCY");
        if (env == null || env.isBlank()) {
            return 3;
        }
        try {
            return Math.max(1, Integer.parseInt(env.trim()));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /** The outcome matrix — pure given the gateway, so it is unit-testable with a fake. */
    static ToolResult decide(final DbGateway gateway, final int pid, final String requestedBackendStart, final boolean verifyOnly)
            throws DbAccessException {
        final String serverTime = gateway.serverTime();
        if (verifyOnly) {
            final Optional<String> observed = gateway.backendStartOf(pid);
            if (observed.isPresent() && observed.get().equals(requestedBackendStart)) {
                return result("matched", null, pid, false, requestedBackendStart, observed.get(), serverTime);
            }
            return alreadyGone(pid, requestedBackendStart, observed, serverTime);
        }
        final TerminateResult tr = gateway.guardedTerminate(pid, requestedBackendStart);
        if (tr.matched() && tr.terminated()) {
            return result("terminated", null, pid, true, requestedBackendStart, requestedBackendStart, serverTime);
        }
        if (tr.matched()) {
            return DbTools.error("TERMINATE_FAILED", "pg_terminate_backend returned false for pid " + pid);
        }
        return alreadyGone(pid, requestedBackendStart, gateway.backendStartOf(pid), serverTime);
    }

    private static ToolResult alreadyGone(final int pid, final String requested, final Optional<String> observed,
            final String serverTime) {
        final String reason = observed.isEmpty() ? "not_found" : "pid_reused";
        return result("already_gone", reason, pid, false, requested, observed.orElse(null), serverTime);
    }

    private static ToolResult result(final String outcome, final String reason, final int pid, final boolean terminated,
            final String requestedBackendStart, final String observedBackendStart, final String serverTime) {
        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("outcome", outcome);
        if (reason != null) {
            output.put("reason", reason);
        }
        output.put("pid", pid);
        output.put("terminated", terminated);
        output.put("requestedBackendStart", requestedBackendStart);
        output.put("observedBackendStart", observedBackendStart);
        output.put("serverTime", serverTime);
        return DbTools.ok(output);
    }
}
