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
 * {@code CODE_INCIDENT_FIX} — relays an incident to the <b>Code Agent</b>, a SEPARATE service (on this host
 * or another; its base URL is a setting OAK manages — see {@link CodeAgentSettings}) that proposes a fix as a
 * pull request. OAK does NOT contain the code-fix logic and holds no GitHub/model credentials — it only
 * forwards the step input as JSON and returns what the agent reports. The agent resolves the step's
 * {@code repoUrl} against the repositories it already holds (which OAK configured via the Code Agent settings).
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
    CodeAgentSettings settings;

    private final String overrideBaseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public CodeIncidentFixTool() {
        this(null);
    }

    /** For tests: a fixed base URL instead of the injected settings. */
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
                "and merges). OAK only forwards the request; it holds no code-fix logic or credentials.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("incidentId", "repoUrl", "baseBranch", "agentBackend", "analysis");
    }

    @Override
    public String render(final Map<String, Object> input) {
        final Map<String, Object> in = input == null ? Map.of() : input;
        return "POST " + baseUrl() + PATH + " incidentId=" + str(in.get("incidentId")) + " repo=" + str(in.get("repoUrl"));
    }

    @Override
    public ToolResult execute(final Map<String, Object> input) {
        final String url = baseUrl() + PATH;
        try {
            final String body = mapper.writeValueAsString(input == null ? Map.of() : input);
            final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return error("CODE_AGENT_HTTP_" + response.statusCode(),
                        "code agent returned HTTP " + response.statusCode() + snippet(response.body()));
            }
            final String raw = response.body();
            return toResult(raw == null || raw.isBlank() ? Map.of() : mapper.readValue(raw, MAP));
        } catch (IOException e) {
            // The Tool contract never throws for a failed call — a transport failure is a reportable outcome.
            return error("CODE_AGENT_UNREACHABLE", "could not reach the code agent at " + url + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("CODE_AGENT_INTERRUPTED", "interrupted while waiting for the code agent");
        }
    }

    /**
     * Map the agent's {@code {outcome, prUrl, branch, commitSha, rationale, detail}} to a result.
     *
     * <p>Outcome is the agent's verdict, not a transport status:
     * <ul>
     *   <li>{@code PR_CREATED} — a fix was proposed as a PR → <b>success</b>, {@code fixApplied=true}.</li>
     *   <li>{@code NO_FIX_NEEDED} — the agent analysed and deliberately changed nothing → <b>success</b>,
     *       {@code fixApplied=false} (this is NOT a failure; the step did its job).</li>
     *   <li>{@code ERROR} — the agent could not do the work (bad input, model/clone failure) → <b>failure</b>.</li>
     *   <li>missing/blank outcome → failure (the agent must state a verdict).</li>
     *   <li>any other non-{@code ERROR} outcome → treated as success, forward-compatibly.</li>
     * </ul>
     * The human-readable {@code summary} lets the platform show "no change needed — &lt;why&gt;" cleanly.
     */
    private static ToolResult toResult(final Map<String, Object> body) {
        final String outcome = normalize(str(body.get("outcome")));
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", outcome);
        putIfPresent(data, "prUrl", body.get("prUrl"));
        putIfPresent(data, "branch", body.get("branch"));
        putIfPresent(data, "commitSha", body.get("commitSha"));
        putIfPresent(data, "rationale", body.get("rationale"));
        putIfPresent(data, "detail", body.get("detail"));
        // Forward token usage verbatim (keys already match the platform's shape); lands at output.usage for
        // every outcome, since a run spends tokens whether it opens a PR, finds no fix, or errors.
        putIfPresent(data, "usage", body.get("usage"));

        if (outcome.isBlank()) {
            return error("CODE_AGENT_NO_OUTCOME", "code agent returned no outcome");
        }
        if ("ERROR".equals(outcome)) {
            data.put("errorCode", "CODE_FIX_ERROR");
            data.put("errorMessage",
                    firstNonBlank(str(body.get("detail")), str(body.get("rationale")), "code agent reported an error"));
            return new ToolResult(1, "", "", false, data);
        }
        // A verdict the agent reached successfully (PR_CREATED, NO_FIX_NEEDED, or a future non-error outcome).
        data.put("fixApplied", "PR_CREATED".equals(outcome));
        data.put("summary", summaryFor(outcome, body));
        return new ToolResult(0, "", "", false, data);
    }

    /** A short, human sentence the platform can show for the verdict. */
    private static String summaryFor(final String outcome, final Map<String, Object> body) {
        if ("PR_CREATED".equals(outcome)) {
            final String pr = str(body.get("prUrl"));
            return pr != null && !pr.isBlank() ? "Fix proposed as a pull request: " + pr : "Fix proposed as a pull request.";
        }
        if ("NO_FIX_NEEDED".equals(outcome)) {
            final String why = firstNonBlank(str(body.get("rationale")), str(body.get("detail")), "");
            return why.isBlank() ? "No code change needed." : "No code change needed — " + why;
        }
        return firstNonBlank(str(body.get("detail")), str(body.get("rationale")), outcome);
    }

    private static String normalize(final String outcome) {
        return outcome == null ? "" : outcome.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static void putIfPresent(final Map<String, Object> data, final String key, final Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            data.put(key, value);
        }
    }

    private static ToolResult error(final String code, final String message) {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", code);
        data.put("errorMessage", message);
        return new ToolResult(1, "", "", false, data);
    }

    private String baseUrl() {
        return overrideBaseUrl != null ? overrideBaseUrl : settings.baseUrl();
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
