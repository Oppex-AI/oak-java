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
package ai.oak.service.ui;

import ai.oak.service.code.CodeAgentClient;
import ai.oak.service.code.CodeAgentClient.CodeAgentException;
import ai.oak.service.code.CodeAgentSettings;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The Code Agent settings OAK manages: where the agent is (its base URL — it may be on another host), which
 * repositories it should hold, and the model API keys it should use. OAK only relays these to the agent and
 * reads back status; the agent does the pulling and holds the git and model credentials. OAK stores no
 * checkout and no credentials — a model API key is forwarded write-only and never persisted or returned.
 */
@Path("/api")
public class CodeReposResource {

    /**
     * The LLM credential providers the agent accepts, each pinned to its backend and whether an API key is required.
     * OAK validates the customer's choice against this before relaying it — there is no default provider on the agent.
     * Add {@code codex-api-key} to the UI when we surface Codex; it is already a valid relay target here.
     */
    private static final Map<String, Provider> PROVIDERS = Map.of("claude-api-key", new Provider("claude", true), "aws-bedrock",
            new Provider("claude", false), "codex-api-key", new Provider("codex", true));

    /** Backends we accept a delete for — derived from the providers. */
    private static final Set<String> BACKENDS = PROVIDERS.values().stream().map(Provider::backend)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** An LLM credential provider: which backend it drives, and whether it needs an API key ({@code aws-bedrock} is keyless). */
    private record Provider(String backend, boolean keyRequired) {
    }

    @Inject
    CodeAgentSettings settings;

    @Inject
    CodeAgentClient agent;

    @GET
    @Path("/code-agent")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> agentInfo() {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseUrl", settings.baseUrl());
        out.put("overridden", settings.isOverridden());
        try {
            agent.list(); // a cheap probe: if it answers, it's reachable
            out.put("reachable", true);
        } catch (CodeAgentException e) {
            out.put("reachable", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    @POST
    @Path("/code-agent")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> saveAgent(final AgentRequest req) {
        if (req == null || blank(req.baseUrl())) {
            return Map.of("ok", false, "error", "a code-agent base URL is required");
        }
        if (!looksLikeHttpUrl(req.baseUrl())) {
            return Map.of("ok", false, "error", "the base URL must start with http:// or https://");
        }
        settings.setBaseUrl(req.baseUrl().trim());
        return Map.of("ok", true, "baseUrl", settings.baseUrl());
    }

    @GET
    @Path("/code-agent/credentials")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> listCredentials() {
        try {
            // The agent returns only which backends/providers are configured — never the key itself.
            return Map.of("ok", true, "configured", agent.listCredentials());
        } catch (CodeAgentException e) {
            return Map.of("ok", false, "error", e.getMessage(), "configured", java.util.List.of());
        }
    }

    @POST
    @Path("/code-agent/credentials")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> saveCredential(final CredentialRequest req) {
        if (req == null || blank(req.provider())) {
            return Map.of("ok", false, "error", "a model provider is required; supported: " + PROVIDERS.keySet());
        }
        final Provider provider = PROVIDERS.get(req.provider());
        if (provider == null) {
            return Map.of("ok", false, "error", "unknown provider '" + req.provider() + "'; supported: " + PROVIDERS.keySet());
        }
        if (!provider.backend().equals(req.backend())) {
            return Map.of("ok", false, "error",
                    "provider '" + req.provider() + "' is for backend '" + provider.backend() + "', not '" + req.backend() + "'");
        }
        if (provider.keyRequired() && blank(req.apiKey())) {
            return Map.of("ok", false, "error", "provider '" + req.provider() + "' requires an API key");
        }
        try {
            // Forward write-only: the agent stores the credential; OAK keeps nothing and never logs it. A keyless
            // provider (e.g. aws-bedrock) sends no apiKey — the agent uses the host's AWS role/SSO.
            agent.setCredential(req.backend(), req.provider(), provider.keyRequired() ? req.apiKey() : null);
            return Map.of("ok", true, "backend", req.backend(), "provider", req.provider(), "configured", true);
        } catch (CodeAgentException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @POST
    @Path("/code-agent/credentials/delete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> deleteCredential(final BackendRequest req) {
        if (req == null || !BACKENDS.contains(req.backend())) {
            return Map.of("ok", false, "error", "unknown model backend; supported: " + BACKENDS);
        }
        try {
            agent.deleteCredential(req.backend());
            return Map.of("ok", true);
        } catch (CodeAgentException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @GET
    @Path("/code-repos")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> listRepos() {
        try {
            final Map<String, Object> repos = new LinkedHashMap<>();
            agent.list().forEach(r -> repos.put(slugOf(r), view(r)));
            return Map.of("ok", true, "repos", repos);
        } catch (CodeAgentException e) {
            return Map.of("ok", false, "error", e.getMessage(), "repos", Map.of());
        }
    }

    @POST
    @Path("/code-repos")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> addRepo(final RepoRequest req) {
        if (req == null || blank(req.url())) {
            return Map.of("ok", false, "error", "a repository URL is required");
        }
        if (!looksLikeGitUrl(req.url())) {
            return Map.of("ok", false, "error", "that doesn't look like a git URL (expected https://… or git@…)");
        }
        try {
            final Map<String, Object> res = agent.add(req.url().trim(), blank(req.branch()) ? null : req.branch().trim());
            return Map.of("ok", true, "slug", String.valueOf(res.getOrDefault("slug", "")), "status",
                    String.valueOf(res.getOrDefault("status", "CLONING")));
        } catch (CodeAgentException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @POST
    @Path("/code-repos/refresh")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> refreshRepo(final SlugRequest req) {
        return relaySlug(req, agent::refresh);
    }

    @POST
    @Path("/code-repos/delete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> deleteRepo(final SlugRequest req) {
        return relaySlug(req, agent::delete);
    }

    private Map<String, Object> relaySlug(final SlugRequest req, final java.util.function.Consumer<String> action) {
        if (req == null || blank(req.slug())) {
            return Map.of("ok", false, "error", "slug is required");
        }
        try {
            action.accept(req.slug().trim());
            return Map.of("ok", true);
        } catch (CodeAgentException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    /** Pass the agent's view through, adding a {@code ready} convenience flag. */
    private static Map<String, Object> view(final Map<String, Object> repo) {
        final Map<String, Object> v = new LinkedHashMap<>(repo);
        v.put("ready", "READY".equalsIgnoreCase(String.valueOf(repo.get("status"))));
        return v;
    }

    private static String slugOf(final Map<String, Object> repo) {
        final Object slug = repo.get("slug");
        return slug != null && !slug.toString().isBlank() ? slug.toString() : String.valueOf(repo.get("url"));
    }

    private static boolean looksLikeHttpUrl(final String url) {
        final String s = url.trim();
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static boolean looksLikeGitUrl(final String url) {
        final String s = url.trim();
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("ssh://") ||
                s.contains("@") && s.contains(":");
    }

    private static boolean blank(final String s) {
        return s == null || s.isBlank();
    }

    public record AgentRequest(String baseUrl) {
    }

    /**
     * Select a model {@code provider} for a {@code backend} (e.g. {@code claude}/{@code claude-api-key}) and, for a
     * key-based provider, its {@code apiKey}. Forwarded write-only; the key is never stored or returned by OAK.
     */
    public record CredentialRequest(String backend, String provider, String apiKey) {
    }

    public record BackendRequest(String backend) {
    }

    /** Add or re-point a repository. A blank {@code branch} lets the agent use the repository default. */
    public record RepoRequest(String url, String branch) {
    }

    public record SlugRequest(String slug) {
    }
}
