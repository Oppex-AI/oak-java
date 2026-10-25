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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.service.code.CodeAgentClient.CodeAgentException;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The relay to the code agent: repo + credential request shapes and how transport/HTTP errors surface. */
class CodeAgentClientTest {

    @Test
    void listParsesTheAgentsRepos() throws Exception {
        final HttpServer server = serve("/v1/code/repos", 200,
                "{\"repos\":[{\"slug\":\"acme_widgets\",\"url\":\"https://github.com/acme/widgets.git\"," +
                        "\"status\":\"READY\",\"commit\":\"abc123\"}]}",
                null);
        try {
            final List<Map<String, Object>> repos = client(server).list();
            assertEquals(1, repos.size());
            assertEquals("acme_widgets", repos.get(0).get("slug"));
            assertEquals("READY", repos.get(0).get("status"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void addPostsUrlAndBranchToTheAgent() throws Exception {
        final AtomicReference<String> sent = new AtomicReference<>();
        final HttpServer server = serve("/v1/code/repos", 200, "{\"slug\":\"acme_widgets\",\"status\":\"CLONING\"}", sent);
        try {
            final Map<String, Object> res = client(server).add("https://github.com/acme/widgets.git", "main");
            assertTrue(sent.get().contains("https://github.com/acme/widgets.git"), sent.get());
            assertTrue(sent.get().contains("\"branch\":\"main\""), sent.get());
            assertEquals("CLONING", res.get("status"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void setCredentialPostsBackendProviderAndKeyToTheAgent() throws Exception {
        final AtomicReference<String> sent = new AtomicReference<>();
        final HttpServer server = serve("/v1/code/credentials", 200,
                "{\"backend\":\"claude\",\"provider\":\"claude-api-key\",\"configured\":true}", sent);
        try {
            final Map<String, Object> res = client(server).setCredential("claude", "claude-api-key", "sk-ant-secret");
            assertTrue(sent.get().contains("\"backend\":\"claude\""), sent.get());
            assertTrue(sent.get().contains("\"provider\":\"claude-api-key\""), sent.get());
            assertTrue(sent.get().contains("sk-ant-secret"), sent.get());
            assertEquals(Boolean.TRUE, res.get("configured"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keylessProviderSendsNoApiKey() throws Exception {
        final AtomicReference<String> sent = new AtomicReference<>();
        final HttpServer server = serve("/v1/code/credentials", 200,
                "{\"backend\":\"claude\",\"provider\":\"aws-bedrock\",\"configured\":true}", sent);
        try {
            client(server).setCredential("claude", "aws-bedrock", null);
            assertTrue(sent.get().contains("\"provider\":\"aws-bedrock\""), sent.get());
            assertTrue(!sent.get().contains("apiKey"), sent.get()); // keyless → the field is omitted entirely
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listCredentialsParsesConfigured() throws Exception {
        final HttpServer server = serve("/v1/code/credentials", 200, "{\"providers\":[\"claude-api-key\",\"aws-bedrock\"]," +
                "\"configured\":[{\"backend\":\"claude\",\"provider\":\"claude-api-key\",\"configured\":true}]}", null);
        try {
            final List<Map<String, Object>> configured = client(server).listCredentials();
            assertEquals(1, configured.size());
            assertEquals("claude", configured.get(0).get("backend"));
            assertEquals("claude-api-key", configured.get(0).get("provider"));
            assertEquals(Boolean.TRUE, configured.get(0).get("configured"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonTwoxxBecomesACodeAgentException() throws Exception {
        final HttpServer server = serve("/v1/code/repos", 503, "down", null);
        try {
            final CodeAgentClient client = client(server);
            final CodeAgentException e = assertThrows(CodeAgentException.class, client::list);
            assertTrue(e.getMessage().contains("503"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transportFailureBecomesACodeAgentException() throws Exception {
        final HttpServer server = serve("/v1/code/repos", 200, "{}", null);
        final int port = server.getAddress().getPort();
        server.stop(0); // nothing listening now → connection refused
        final CodeAgentException e = assertThrows(CodeAgentException.class,
                () -> new CodeAgentClient("http://localhost:" + port).list());
        assertTrue(e.getMessage().contains("could not reach the code agent"), e.getMessage());
    }

    private static CodeAgentClient client(final HttpServer server) {
        return new CodeAgentClient("http://localhost:" + server.getAddress().getPort());
    }

    private static HttpServer serve(final String path, final int status, final String body,
            final AtomicReference<String> captured) throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            if (captured != null) {
                captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
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
