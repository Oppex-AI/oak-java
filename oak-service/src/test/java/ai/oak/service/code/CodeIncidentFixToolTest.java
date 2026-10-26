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
package ai.oak.service.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolResult;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CODE_INCIDENT_FIX is a thin relay to the local code agent: these pin the outcome→ToolResult mapping
 * (success / agent ERROR / HTTP error / transport failure) with a stub HTTP server, no real agent.
 */
class CodeIncidentFixToolTest {

    @Test
    void contractBasics() {
        final CodeIncidentFixTool tool = new CodeIncidentFixTool("http://localhost:9030");
        assertEquals("CODE_INCIDENT_FIX", tool.capability());
        assertEquals(ToolPermission.READ, tool.permission());
        assertEquals(List.of("incidentId", "repoUrl", "baseBranch", "agentBackend", "analysis"), tool.inputKeys());
        assertTrue(tool.render(Map.of("incidentId", "INC-1", "repoUrl", "r")).contains("/v1/code/incident-fix"));
    }

    @Test
    void successOutcomeMapsToSuccessWithFields() throws Exception {
        final HttpServer server = serve(200, "{\"outcome\":\"PR_CREATED\",\"prUrl\":\"https://github.com/x/y/pull/7\"," +
                "\"branch\":\"fix/inc-1\",\"commitSha\":\"abc123\",\"rationale\":\"bumped the timeout\",\"detail\":\"ok\"}");
        try {
            final ToolResult r = toolFor(server).execute(Map.of("incidentId", "INC-1", "repoUrl", "https://github.com/x/y"));
            assertTrue(r.success());
            assertEquals("PR_CREATED", r.data().get("outcome"));
            assertEquals("https://github.com/x/y/pull/7", r.data().get("prUrl"));
            assertEquals("abc123", r.data().get("commitSha"));
            assertEquals("fix/inc-1", r.data().get("branch"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void agentErrorOutcomeMapsToFailureWithCodeAndMessage() throws Exception {
        final HttpServer server = serve(200, "{\"outcome\":\"ERROR\",\"detail\":\"no safe fix found\"}");
        try {
            final ToolResult r = toolFor(server).execute(Map.of("incidentId", "INC-2"));
            assertFalse(r.success());
            assertEquals("ERROR", r.data().get("outcome"));
            assertEquals("CODE_FIX_ERROR", r.data().get("errorCode"));
            assertEquals("no safe fix found", r.data().get("errorMessage"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noFixNeededMapsToSuccessNotFailure() throws Exception {
        final HttpServer server = serve(200,
                "{\"outcome\":\"NO_FIX_NEEDED\",\"rationale\":\"OPD-880 already moved the write out of the transaction\"}");
        try {
            final ToolResult r = toolFor(server).execute(Map.of("incidentId", "INC-5"));
            assertTrue(r.success(), "a deliberate no-op is a successful analysis, not a failed step");
            assertEquals("NO_FIX_NEEDED", r.data().get("outcome"));
            assertEquals(Boolean.FALSE, r.data().get("fixApplied"));
            // Summary is a short headline only — no embedded rationale (that would double-render on the note).
            assertEquals("No code change needed.", r.data().get("summary"));
            // The reasoning is still carried verbatim, separately, so nothing is lost.
            assertEquals("OPD-880 already moved the write out of the transaction", r.data().get("rationale"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usageIsForwardedVerbatim() throws Exception {
        final HttpServer server = serve(200, "{\"outcome\":\"PR_CREATED\",\"prUrl\":\"https://x/y/pull/9\"," +
                "\"usage\":{\"inputTokens\":1200,\"outputTokens\":300,\"totalTokens\":1500,\"cacheReadInputTokens\":800}}");
        try {
            final ToolResult r = toolFor(server).execute(Map.of("incidentId", "INC-7"));
            assertTrue(r.success());
            @SuppressWarnings("unchecked")
            final Map<String, Object> usage = (Map<String, Object>) r.data().get("usage");
            assertEquals(1200, usage.get("inputTokens"));
            assertEquals(1500, usage.get("totalTokens"));
            assertEquals(800, usage.get("cacheReadInputTokens"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingOutcomeMapsToFailure() throws Exception {
        final HttpServer server = serve(200, "{\"detail\":\"something happened\"}");
        try {
            final ToolResult r = toolFor(server).execute(Map.of("incidentId", "INC-6"));
            assertFalse(r.success());
            assertEquals("CODE_AGENT_NO_OUTCOME", r.data().get("errorCode"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonTwoxxMapsToHttpError() throws Exception {
        final HttpServer server = serve(500, "boom");
        try {
            final ToolResult r = toolFor(server).execute(Map.of("incidentId", "INC-3"));
            assertFalse(r.success());
            assertEquals("CODE_AGENT_HTTP_500", r.data().get("errorCode"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transportFailureMapsToUnreachable() throws Exception {
        // Start then immediately stop to get a port nothing is listening on → connection refused.
        final HttpServer server = serve(200, "{}");
        final int port = server.getAddress().getPort();
        server.stop(0);
        final ToolResult r = new CodeIncidentFixTool("http://localhost:" + port).execute(Map.of("incidentId", "INC-4"));
        assertFalse(r.success());
        assertEquals("CODE_AGENT_UNREACHABLE", r.data().get("errorCode"));
    }

    private static CodeIncidentFixTool toolFor(final HttpServer server) {
        return new CodeIncidentFixTool("http://localhost:" + server.getAddress().getPort());
    }

    private static HttpServer serve(final int status, final String body) throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/code/incident-fix", exchange -> {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
