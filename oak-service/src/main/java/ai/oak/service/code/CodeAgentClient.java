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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relays settings to the <b>Code Agent</b> over HTTP. The agent — which may run on another host — does all
 * code handling: it holds the git and model credentials and pulls the repositories. OAK stores neither; it
 * only tells the agent which repositories to hold and which model API keys to use, and reads back status.
 *
 * <p>The model API key is <b>write-only through OAK</b>: OAK forwards it to the agent and never persists or
 * returns it. The agent stores it. {@link #listCredentials()} reports only which backends are configured,
 * never the key itself.
 *
 * <p>The agent's contract (see {@code docs/code-agent-repos.md}):
 *
 * <ul>
 *   <li>{@code POST /v1/code/repos} {@code {url, branch}} — register a repo; the agent starts pulling.</li>
 *   <li>{@code GET /v1/code/repos} — {@code {repos:[{slug,url,branch,status,commit,detail,updatedAt}]}}.</li>
 *   <li>{@code POST /v1/code/repos/refresh} {@code {slug}} / {@code .../delete} {@code {slug}}.</li>
 *   <li>{@code GET /v1/code/credentials} — {@code {providers:[…], configured:[{backend,provider,configured,updatedAt}]}}
 *       (never the key).</li>
 *   <li>{@code POST /v1/code/credentials} {@code {backend, provider, apiKey?}} / {@code .../delete} {@code {backend}}.</li>
 * </ul>
 */
@ApplicationScoped
public class CodeAgentClient {

    private static final String REPOS = "/v1/code/repos";
    private static final String CREDENTIALS = "/v1/code/credentials";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // Register/refresh kick off an async pull on the agent and return promptly; the rest are quick reads/writes.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    @Inject
    CodeAgentSettings settings;

    private final String overrideBaseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public CodeAgentClient() {
        this(null);
    }

    /** For tests: a fixed base URL instead of the injected settings. */
    CodeAgentClient(final String overrideBaseUrl) {
        this.overrideBaseUrl = overrideBaseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper = JsonMapper.builder().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .serializationInclusion(JsonInclude.Include.NON_NULL).build();
    }

    // ---- repositories ----

    /** The repositories the agent currently holds, each a {@code {slug,url,branch,status,commit,detail,updatedAt}} map. */
    public List<Map<String, Object>> list() {
        return asMaps(exchange(get(REPOS)).get("repos"));
    }

    /** Register a repository with the agent (or re-point it); the agent begins pulling. */
    public Map<String, Object> add(final String url, final String branch) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("branch", branch);
        return exchange(post(REPOS, body));
    }

    public Map<String, Object> refresh(final String slug) {
        return exchange(post(REPOS + "/refresh", Map.of("slug", slug)));
    }

    public Map<String, Object> delete(final String slug) {
        return exchange(post(REPOS + "/delete", Map.of("slug", slug)));
    }

    // ---- model credentials (write-only through OAK) ----

    /**
     * Which backends the agent has a credential configured for, each {@code {backend, provider, configured, updatedAt}}
     * — never the key. Reads the agent's {@code configured} list (the companion {@code providers} catalog is ignored).
     */
    public List<Map<String, Object>> listCredentials() {
        return asMaps(exchange(get(CREDENTIALS)).get("configured"));
    }

    /**
     * Select the {@code provider} the agent uses for {@code backend} (e.g. {@code claude}/{@code claude-api-key}), and
     * for a key-based provider the API key. For a keyless provider (e.g. {@code aws-bedrock}) pass {@code null} —
     * no {@code apiKey} is sent. OAK forwards write-only and keeps nothing.
     */
    public Map<String, Object> setCredential(final String backend, final String provider, final String apiKey) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("backend", backend);
        body.put("provider", provider);
        body.put("apiKey", apiKey); // NON_NULL serialization omits this entirely for a keyless provider
        return exchange(post(CREDENTIALS, body));
    }

    public Map<String, Object> deleteCredential(final String backend) {
        return exchange(post(CREDENTIALS + "/delete", Map.of("backend", backend)));
    }

    // ---- transport ----

    private HttpRequest get(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(REQUEST_TIMEOUT).header("Accept", "application/json")
                .GET().build();
    }

    private HttpRequest post(final String path, final Map<String, Object> body) {
        try {
            return HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        } catch (IOException e) {
            throw new CodeAgentException("could not encode the request: " + e.getMessage());
        }
    }

    private Map<String, Object> exchange(final HttpRequest request) {
        try {
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new CodeAgentException("code agent returned HTTP " + response.statusCode());
            }
            final String raw = response.body();
            return raw == null || raw.isBlank() ? Map.of() : mapper.readValue(raw, MAP);
        } catch (IOException e) {
            throw new CodeAgentException("could not reach the code agent at " + baseUrl() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeAgentException("interrupted while contacting the code agent");
        }
    }

    private static List<Map<String, Object>> asMaps(final Object value) {
        final List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (final Object item : items) {
                if (item instanceof Map<?, ?> m) {
                    final Map<String, Object> copy = new LinkedHashMap<>();
                    m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    out.add(copy);
                }
            }
        }
        return out;
    }

    private String baseUrl() {
        return overrideBaseUrl != null ? overrideBaseUrl : settings.baseUrl();
    }

    /** The code agent could not be reached, or answered with an error; surfaced to the UI as a message. */
    public static final class CodeAgentException extends RuntimeException {
        public CodeAgentException(final String message) {
            super(message);
        }
    }
}
