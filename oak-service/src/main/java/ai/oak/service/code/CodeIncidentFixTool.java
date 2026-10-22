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

import ai.oak.service.config.OakConfig;
import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code CODE_INCIDENT_FIX} — relays an incident to the local <b>Code Agent</b>, a SEPARATE service on
 * the same host (default {@code http://localhost:9030}) that proposes a fix as a pull request. OAK does
 * NOT contain the code-fix logic and holds no GitHub/model credentials — it only forwards the step input
 * as JSON and returns what the agent reports.
 *
 * <p>When the step's {@code repoUrl} matches a repository OAK has already downloaded (see {@link CodeRepos}),
 * OAK adds the local checkout path and commit to the forwarded payload, so the agent operates on what OAK
 * fetched with the host's git rather than cloning again. The same repo context is echoed on the result.
 *
 * <p>Permission is {@link ToolPermission#READ}: proposing a PR mutates nothing on customer infrastructure
 * — a human reviews and merges. Auto-registers as a {@code @ApplicationScoped} {@link Tool} bean (picked
 * up by {@code ToolCatalog}'s {@code Instance<Tool>}), which also auto-advertises it to the platform at
 * the next registration.
 */
@ApplicationScoped
public class CodeIncidentFixTool implements Tool {

    private static final String CAPABILITY = "CODE_INCIDENT_FIX";
    private static final String PATH = "/v1/code/incident-fix";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // A code fix (clone, analyse, generate, open a PR) can take a while — generous request budget.
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    @Inject
    OakConfig config;

    @Inject
    CodeRepos repos;

    private final String overrideBaseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public CodeIncidentFixTool() {
        this(null);
    }

    /** For tests: a fixed base URL instead of the injected config. */
    CodeIncidentFixTool(final String overrideBaseUrl) {
        this.overrideBaseUrl = overrideBaseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper = JsonMapper.builder().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .serializationInclusion(JsonInclude.Include.NON_NULL).build();
    }

    @Override
    public String capability() {
        return CAPABILITY;
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Relay an incident to the local Code Agent, which proposes a fix as a pull request (a human reviews " +
                "and merges). When OAK has already downloaded the target repo, it passes the local checkout so the " +
                "agent skips cloning. OAK holds no code-fix logic or credentials.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("incidentId", "repoUrl", "baseBranch", "agentBackend", "analysis");
    }

    @Override
    public String render(final Map<String, Object> input) {
        final Map<String, Object> in = input == null ? Map.of() : input;
        final Object localPath = resolveRepo(in).get("repoLocalPath");
        final String local = localPath == null ? "" : " local=" + localPath;
        return "POST " + baseUrl() + PATH + " incidentId=" + str(in.get("incidentId")) + " repo=" + str(in.get("repoUrl")) +
                local;
    }

    @Override
    public ToolResult execute(final Map<String, Object> input) {
        final String url = baseUrl() + PATH;
        // Enrich the forwarded step with the local checkout OAK already downloaded for this repo, so the
        // Code Agent works on that instead of cloning again. Empty when the repo isn't configured here.
        final Map<String, Object> repoCtx = resolveRepo(input);
        try {
            final Map<String, Object> payload = new LinkedHashMap<>();
            if (input != null) {
                payload.putAll(input);
            }
            payload.putAll(repoCtx);
            final String body = mapper.writeValueAsString(payload);
            final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return withRepo(error("CODE_AGENT_HTTP_" + response.statusCode(),
                        "code agent returned HTTP " + response.statusCode() + snippet(response.body())), repoCtx);
            }
            final String raw = response.body();
            return withRepo(toResult(raw == null || raw.isBlank() ? Map.of() : mapper.readValue(raw, MAP)), repoCtx);
        } catch (IOException e) {
            // The Tool contract never throws for a failed call — a transport failure is a reportable outcome.
            return withRepo(error("CODE_AGENT_UNREACHABLE", "could not reach the code agent at " + url + ": " + e.getMessage()),
                    repoCtx);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return withRepo(error("CODE_AGENT_INTERRUPTED", "interrupted while waiting for the code agent"), repoCtx);
        }
    }

    /**
     * The repo context OAK contributes to the step. When {@code repoUrl} matches a repository OAK has
     * downloaded and marked ready, this carries the local checkout path (and commit) so the agent can skip
     * its own clone. Empty when no repo store is wired or no {@code repoUrl} is present; {@code UNCONFIGURED}
     * when the URL isn't one OAK was told to download.
     */
    private Map<String, Object> resolveRepo(final Map<String, Object> input) {
        final Map<String, Object> ctx = new LinkedHashMap<>();
        final Object repoUrl = input == null ? null : input.get("repoUrl");
        if (repos == null || repoUrl == null || repoUrl.toString().isBlank()) {
            return ctx;
        }
        final String slug = CodeRepos.slugFor(repoUrl.toString());
        final CodeRepo repo = repos.get(slug).orElse(null);
        if (repo == null) {
            ctx.put("repoStatus", "UNCONFIGURED");
            ctx.put("repoReady", false);
            return ctx;
        }
        ctx.put("repoSlug", slug);
        ctx.put("repoStatus", repo.status().name());
        ctx.put("repoReady", repo.isReady());
        if (repo.isReady()) {
            ctx.put("repoLocalPath", repo.localPath());
            if (repo.commit() != null && !repo.commit().isBlank()) {
                ctx.put("repoCommit", repo.commit());
            }
        }
        return ctx;
    }

    /** Merge the repo context into a result's data, so the incident record shows what OAK supplied. */
    private static ToolResult withRepo(final ToolResult result, final Map<String, Object> repoCtx) {
        if (repoCtx.isEmpty()) {
            return result;
        }
        final Map<String, Object> merged = new LinkedHashMap<>(repoCtx);
        merged.putAll(result.data());
        return new ToolResult(result.exitCode(), result.stdout(), result.stderr(), result.timedOut(), merged);
    }

    /** Map the agent's {@code {outcome, prUrl, branch, commitSha, rationale, detail}} to a result; success = outcome != ERROR. */
    private static ToolResult toResult(final Map<String, Object> body) {
        final String outcome = str(body.get("outcome"));
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", outcome);
        data.put("prUrl", body.get("prUrl"));
        data.put("branch", body.get("branch"));
        data.put("commitSha", body.get("commitSha"));
        data.put("rationale", body.get("rationale"));
        data.put("detail", body.get("detail"));
        if (outcome != null && !"ERROR".equalsIgnoreCase(outcome)) {
            return new ToolResult(0, "", "", false, data);
        }
        data.put("errorCode", "CODE_FIX_ERROR");
        data.put("errorMessage",
                firstNonBlank(str(body.get("detail")), str(body.get("rationale")), "code agent reported outcome=" + outcome));
        return new ToolResult(1, "", "", false, data);
    }

    private static ToolResult error(final String code, final String message) {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", code);
        data.put("errorMessage", message);
        return new ToolResult(1, "", "", false, data);
    }

    private String baseUrl() {
        final String base = overrideBaseUrl != null ? overrideBaseUrl : config.codeAgent().baseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String str(final Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(final String... values) {
        for (final String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String snippet(final String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        final String trimmed = body.strip();
        return ": " + (trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed);
    }
}
