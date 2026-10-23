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

import ai.oak.service.code.CodeAgentRepos;
import ai.oak.service.code.CodeAgentRepos.CodeAgentException;
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

/**
 * The Code Agent settings OAK manages: where the agent is (its base URL — it may be on another host), and
 * which repositories it should hold. OAK only relays repository settings to the agent and reads back status;
 * the agent does the pulling and holds the git credentials. OAK stores no checkout and no credentials.
 */
@Path("/api")
public class CodeReposResource {

    @Inject
    CodeAgentSettings settings;

    @Inject
    CodeAgentRepos agent;

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

    /** Add or re-point a repository. A blank {@code branch} lets the agent use the repository default. */
    public record RepoRequest(String url, String branch) {
    }

    public record SlugRequest(String slug) {
    }
}
