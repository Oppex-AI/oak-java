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

import ai.oak.service.code.CodeAgentRepos.CodeAgentException;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The repo-management relay to the code agent: request shapes and how transport/HTTP errors surface. */
class CodeAgentReposTest {

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
    void nonTwoxxBecomesACodeAgentException() throws Exception {
        final HttpServer server = serve("/v1/code/repos", 503, "down", null);
        try {
            final CodeAgentRepos client = client(server);
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
                () -> new CodeAgentRepos("http://localhost:" + port).list());
        assertTrue(e.getMessage().contains("could not reach the code agent"), e.getMessage());
    }

    private static CodeAgentRepos client(final HttpServer server) {
        return new CodeAgentRepos("http://localhost:" + server.getAddress().getPort());
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
