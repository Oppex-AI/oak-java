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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.tools.ApprovalCandidateSource;
import ai.oak.tools.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The DB tools' contract-critical behaviour: the DB_TERMINATE outcome matrix (terminated / already_gone
 * not_found / already_gone pid_reused / TERMINATE_FAILED / verify), the DB_LIST_ACTIVITY envelope + identity
 * shape, NO_CONNECTION_CONFIGURED, and the query fingerprint — all with a fake gateway, no real Postgres.
 */
class DbToolsTest {

    private static final String REQ = "2026-10-01T00:00:00.123456Z";

    /** A scriptable DbGateway: no JDBC, just canned answers for the decision logic. */
    private static final class FakeGateway implements DbGateway {
        private String serverTime = "2026-10-01T00:05:00Z";
        private final Map<Integer, String> live = new HashMap<>();
        private TerminateResult terminate = new TerminateResult(false, false);
        private List<Map<String, Object>> activities = List.of();

        @Override
        public String serverTime() {
            return serverTime;
        }

        @Override
        public List<Map<String, Object>> listActivity(final int min, final int limit, final boolean blockers) {
            return activities;
        }

        @Override
        public Optional<String> backendStartOf(final int pid) {
            return Optional.ofNullable(live.get(pid));
        }

        @Override
        public TerminateResult guardedTerminate(final int pid, final String backendStartIso) {
            return terminate;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void terminateMatchedKills() throws Exception {
        final FakeGateway gw = new FakeGateway();
        gw.terminate = new DbGateway.TerminateResult(true, true);
        final Map<String, Object> out = DbTerminateTool.decide(gw, 101, REQ, false).data();
        assertEquals("terminated", out.get("outcome"));
        assertEquals(Boolean.TRUE, out.get("terminated"));
        assertEquals(REQ, out.get("requestedBackendStart"));
    }

    @Test
    void terminateMatchedButSignalFailedIsTerminateFailed() throws Exception {
        final FakeGateway gw = new FakeGateway();
        gw.terminate = new DbGateway.TerminateResult(true, false);
        final ToolResult r = DbTerminateTool.decide(gw, 101, REQ, false);
        assertFalse(r.success());
        assertEquals("TERMINATE_FAILED", r.data().get("errorCode"));
    }

    @Test
    void terminatePidAbsentIsAlreadyGoneNotFound() throws Exception {
        final FakeGateway gw = new FakeGateway(); // guardedTerminate matched=false, pid not live
        final Map<String, Object> out = DbTerminateTool.decide(gw, 101, REQ, false).data();
        assertEquals("already_gone", out.get("outcome"));
        assertEquals("not_found", out.get("reason"));
        assertEquals(Boolean.FALSE, out.get("terminated"));
    }

    @Test
    void terminateReusedPidIsAlreadyGonePidReused() throws Exception {
        final FakeGateway gw = new FakeGateway();
        gw.live.put(101, "2026-10-01T09:99:99Z-DIFFERENT"); // a different backend now holds pid 101
        final Map<String, Object> out = DbTerminateTool.decide(gw, 101, REQ, false).data();
        assertEquals("already_gone", out.get("outcome"));
        assertEquals("pid_reused", out.get("reason"));
        assertEquals("2026-10-01T09:99:99Z-DIFFERENT", out.get("observedBackendStart"));
    }

    @Test
    void verifyOnlyMatchesWithoutTerminating() throws Exception {
        final FakeGateway gw = new FakeGateway();
        gw.live.put(101, REQ);
        final Map<String, Object> out = DbTerminateTool.decide(gw, 101, REQ, true).data();
        assertEquals("matched", out.get("outcome"));
        assertEquals(Boolean.FALSE, out.get("terminated"));
    }

    @Test
    void listActivityWrapsIdentityShapeAndCountsRows() {
        final Map<String, Object> activity = Map.of("identity", Map.of("pid", 101, "backendStart", REQ, "queryStart", REQ),
                "query", "SELECT 1", "durationSeconds", 42.0);
        final FakeGateway gw = new FakeGateway();
        gw.activities = List.of(activity);
        final ToolResult r = new DbListActivityTool(providerReturning(gw))
                .execute(Map.of("dbIdentifier", "orders-db", "region", "us-west-2"));
        assertTrue(r.success());
        assertEquals("orders-db", r.data().get("dbIdentifier"));
        assertEquals(1, r.data().get("activityCount"));
        final List<?> activities = (List<?>) r.data().get("activities");
        final Map<?, ?> identity = (Map<?, ?>) ((Map<?, ?>) activities.get(0)).get("identity");
        assertEquals(101, identity.get("pid"));
        assertEquals(REQ, identity.get("backendStart"));
    }

    @Test
    void noConfiguredConnectionIsReported() {
        final ToolResult r = new DbListActivityTool(providerThrowing("NO_CONNECTION_CONFIGURED"))
                .execute(Map.of("dbIdentifier", "unknown-db"));
        assertFalse(r.success());
        assertEquals("NO_CONNECTION_CONFIGURED", r.data().get("errorCode"));
    }

    @Test
    void fingerprintIsStableAcrossWhitespaceAndCasing() {
        assertEquals(JdbcDbGateway.fingerprint("SELECT 1"), JdbcDbGateway.fingerprint("select    1 ;"));
    }

    private static DbConnectionProvider providerReturning(final DbGateway gateway) {
        return new DbConnectionProvider() {
            @Override
            public DbGateway open(final String dbIdentifier, final String stepRegion) {
                return gateway;
            }
        };
    }

    private static DbConnectionProvider providerThrowing(final String code) {
        return new DbConnectionProvider() {
            @Override
            public DbGateway open(final String dbIdentifier, final String stepRegion) throws DbAccessException {
                throw new DbAccessException(code, "no connection");
            }
        };
    }

    /** DB_TERMINATE's approval wiring must line up with DB_LIST_ACTIVITY's output: activities[].identity.pid. */
    @Test
    void dbTerminateAdvertisesSelectThenApproveSource() {
        final ApprovalCandidateSource s = new DbTerminateTool(null).approvalCandidateSource();
        assertEquals("DB_LIST_ACTIVITY", s.fromCapability());
        assertEquals("activities", s.candidatesPath());
        assertEquals("identity", s.identityField());
        assertEquals("pid", s.idField());
        assertEquals("identity", s.inputField());
        assertTrue(s.displayFields().contains("query"));
        assertEquals(List.of("query"), s.redactFields());
    }
}
