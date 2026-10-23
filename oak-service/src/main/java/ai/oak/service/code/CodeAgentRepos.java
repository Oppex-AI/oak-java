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
 * Relays repository management to the <b>Code Agent</b> over HTTP. The agent — which may run on another host
 * and holds the git credentials — does the actual pulling; this client only tells it which repositories to
 * hold and reads back their status. OAK stores no checkout and no credentials.
 *
 * <p>The agent's repository contract (see {@code docs/code-agent-repos.md}):
 *
 * <ul>
 *   <li>{@code POST /v1/code/repos} {@code {url, branch}} — register a repo; the agent starts pulling.</li>
 *   <li>{@code GET /v1/code/repos} — {@code {repos:[{slug,url,branch,status,commit,detail,updatedAt}]}}.</li>
 *   <li>{@code POST /v1/code/repos/refresh} {@code {slug}} — pull again.</li>
 *   <li>{@code POST /v1/code/repos/delete} {@code {slug}} — forget it.</li>
 * </ul>
 */
@ApplicationScoped
public class CodeAgentRepos {

    private static final String PATH = "/v1/code/repos";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // Register/refresh kick off an async pull on the agent and return promptly; listing is a quick read.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    @Inject
    CodeAgentSettings settings;

    private final String overrideBaseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public CodeAgentRepos() {
        this(null);
    }

    /** For tests: a fixed base URL instead of the injected settings. */
    CodeAgentRepos(final String overrideBaseUrl) {
        this.overrideBaseUrl = overrideBaseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper = JsonMapper.builder().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .serializationInclusion(JsonInclude.Include.NON_NULL).build();
    }

    /** The repositories the agent currently holds, each a {@code {slug,url,branch,status,commit,detail,updatedAt}} map. */
    public List<Map<String, Object>> list() {
        final Object repos = exchange(get(PATH)).get("repos");
        final List<Map<String, Object>> out = new ArrayList<>();
        if (repos instanceof List<?> items) {
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

    /** Register a repository with the agent (or re-point it); the agent begins pulling. */
    public Map<String, Object> add(final String url, final String branch) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("branch", branch);
        return exchange(post(PATH, body));
    }

    public Map<String, Object> refresh(final String slug) {
        return exchange(post(PATH + "/refresh", Map.of("slug", slug)));
    }

    public Map<String, Object> delete(final String slug) {
        return exchange(post(PATH + "/delete", Map.of("slug", slug)));
    }

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
