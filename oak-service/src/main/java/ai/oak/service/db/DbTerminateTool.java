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
import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DB_TERMINATE (DESTRUCTIVE) — the safe kill. Oppex dispatches it only after a human approved it, injecting
 * the full identity resolved from a persisted DB_LIST_ACTIVITY snapshot. OAK re-reads the backend at
 * terminate time and issues {@code pg_terminate_backend(pid)} ONLY IF the live {@code backend_start} still
 * equals the requested one — never by pid alone. A reused pid is {@code already_gone} (we killed nothing),
 * which is correct and safe. Idempotent. Works on RDS/Aurora (pure SQL, not an RDS reboot).
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
        return List.of("dbIdentifier", "region", "identity", "queryFingerprint", "verifyOnly");
    }

    @Override
    public String render(final Map<String, Object> input) {
        final Map<?, ?> identity = input.get("identity") instanceof Map<?, ?> m ? m : Map.of();
        final boolean verifyOnly = DbTools.boolOf(input.get("verifyOnly"));
        return (verifyOnly ? "verify" : "pg_terminate_backend") + " pid=" + DbTools.str(identity.get("pid")) + " backend_start=" +
                DbTools.str(identity.get("backendStart")) + " on " + DbTools.str(input.get("dbIdentifier"));
    }

    @Override
    public ToolResult execute(final Map<String, Object> input) {
        final Map<?, ?> identity = input.get("identity") instanceof Map<?, ?> m ? m : Map.of();
        final int pid = DbTools.intOf(identity.get("pid"), -1);
        final String backendStart = DbTools.str(identity.get("backendStart"));
        final boolean verifyOnly = DbTools.boolOf(input.get("verifyOnly"));
        if (pid < 0 || backendStart == null) {
            return DbTools.error("TERMINATE_FAILED", "identity.pid and identity.backendStart are required");
        }
        try (DbGateway gateway = provider.open(DbTools.str(input.get("dbIdentifier")), DbTools.str(input.get("region")))) {
            return decide(gateway, pid, backendStart, verifyOnly);
        } catch (DbAccessException e) {
            return DbTools.error(e.code(), e.getMessage());
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
