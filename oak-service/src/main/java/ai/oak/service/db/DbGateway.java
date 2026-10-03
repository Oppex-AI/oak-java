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

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The SQL operations the DB tools need against one Postgres connection — the seam that keeps JDBC out of
 * the tool logic so the decision-making (the terminate outcome matrix in particular) is unit-testable with
 * a fake. All methods raise {@link DbAccessException} with a contract error code on failure.
 */
public interface DbGateway extends AutoCloseable {

    /** The server's {@code now()} as an ISO-8601 UTC string. */
    String serverTime() throws DbAccessException;

    /** Active backends running longer than {@code minDurationSeconds}, newest-longest first, capped at {@code limit}. */
    List<Map<String, Object>> listActivity(int minDurationSeconds, int limit, boolean includeBlockers) throws DbAccessException;

    /** The live {@code backend_start} (ISO-8601 UTC) for {@code pid}, or empty when that pid is not present. */
    Optional<String> backendStartOf(int pid) throws DbAccessException;

    /**
     * Terminate {@code pid} only if its live {@code backend_start} still equals {@code backendStartIso} —
     * {@code pg_terminate_backend(pid)} guarded in one statement. Never kills by pid alone.
     */
    TerminateResult guardedTerminate(int pid, String backendStartIso) throws DbAccessException;

    @Override
    void close();

    /** Result of a guarded terminate: whether the identity matched a live backend, and whether the kill signal took. */
    record TerminateResult(boolean matched, boolean terminated) {
    }
}
