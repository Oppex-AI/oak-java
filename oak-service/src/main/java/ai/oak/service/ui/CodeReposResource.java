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

import ai.oak.service.code.CodeRepo;
import ai.oak.service.code.CodeRepos;
import ai.oak.service.code.RepoDownloader;
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
 * Manage the code repositories OAK downloads for the local Code Agent. Setting a repo URL kicks off an
 * asynchronous download; the UI polls {@link #list()} until the repo reports {@code READY} — OAK's
 * confirmation that the code is downloaded and ready. No secret is held (the host's git credentials are
 * used), so the full config is returned.
 */
@Path("/api")
public class CodeReposResource {

    @Inject
    CodeRepos store;

    @Inject
    RepoDownloader downloader;

    @GET
    @Path("/code-repos")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> list() {
        final Map<String, Object> out = new LinkedHashMap<>();
        store.all().forEach((slug, repo) -> out.put(slug, view(repo)));
        return out;
    }

    @POST
    @Path("/code-repos")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> save(final SaveRequest req) {
        if (req == null || blank(req.url())) {
            return Map.of("ok", false, "error", "a repository URL is required");
        }
        if (!looksLikeGitUrl(req.url())) {
            return Map.of("ok", false, "error", "that doesn't look like a git URL (expected https://… or git@…)");
        }
        final String slug = store.upsert(req.url(), req.branch());
        downloader.enqueue(slug);
        return Map.of("ok", true, "slug", slug, "status", "CLONING");
    }

    @POST
    @Path("/code-repos/refresh")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> refresh(final SlugRequest req) {
        if (req == null || blank(req.slug()) || store.get(req.slug()).isEmpty()) {
            return Map.of("ok", false, "error", "unknown repository");
        }
        downloader.enqueue(req.slug().trim());
        return Map.of("ok", true, "status", "CLONING");
    }

    @POST
    @Path("/code-repos/delete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> delete(final SlugRequest req) {
        if (req == null || blank(req.slug())) {
            return Map.of("ok", false, "error", "slug is required");
        }
        store.remove(req.slug().trim());
        return Map.of("ok", true);
    }

    private static Map<String, Object> view(final CodeRepo repo) {
        final Map<String, Object> v = new LinkedHashMap<>();
        v.put("url", repo.url());
        v.put("branch", repo.branch());
        v.put("status", repo.status().name());
        v.put("ready", repo.isReady());
        v.put("commit", repo.commit());
        v.put("localPath", repo.localPath());
        v.put("detail", repo.detail());
        v.put("updatedAt", repo.updatedAt());
        return v;
    }

    private static boolean looksLikeGitUrl(final String url) {
        final String s = url.trim();
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("ssh://") ||
                s.contains("@") && s.contains(":");
    }

    private static boolean blank(final String s) {
        return s == null || s.isBlank();
    }

    /** Add or re-point a repository. A blank {@code branch} checks out the repository default. */
    public record SaveRequest(String url, String branch) {
    }

    public record SlugRequest(String slug) {
    }
}
