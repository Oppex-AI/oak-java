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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The code repositories OAK has been asked to make available to the local Code Agent, keyed by a slug
 * derived from the URL, persisted to {@code <data-dir>/code-repos.json}. The checkouts themselves live
 * under {@code <data-dir>/repos/<slug>}.
 *
 * <p>No secrets are held here — the download uses the host's own git credentials (see {@link CodeRepo}) —
 * so the file is stored in the clear. The store only tracks <em>what</em> to download and <em>where it got
 * to</em>; {@link RepoDownloader} does the downloading and calls back here to record the outcome.
 */
@ApplicationScoped
public class CodeRepos {

    private static final Logger LOG = LoggerFactory.getLogger(CodeRepos.class);

    @ConfigProperty(name = "oak.data-dir")
    Optional<String> dataDir;

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, CodeRepo> repos = new ConcurrentHashMap<>();
    private Path file;
    private Path reposDir;

    @PostConstruct
    void init() {
        final Path base = dataDir.filter(d -> !d.isBlank()).map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home", "."), ".oak"));
        file = base.resolve("code-repos.json");
        reposDir = base.resolve("repos");
        load();
    }

    /** Where {@code slug}'s checkout lives on this host. */
    public Path pathFor(final String slug) {
        return reposDir.resolve(slug);
    }

    public Optional<CodeRepo> get(final String slug) {
        return Optional.ofNullable(slug == null ? null : repos.get(slug));
    }

    public Map<String, CodeRepo> all() {
        return Map.copyOf(repos);
    }

    /**
     * Register (or re-point) a repository and return its slug. The entry starts at {@link RepoStatus#NONE};
     * the caller triggers the download. Re-saving the same URL keeps its slug and resets it for a fresh pull.
     */
    public synchronized String upsert(final String url, final String branch) {
        final String slug = slugFor(url);
        final CodeRepo repo = new CodeRepo(url.trim(), branch == null ? null : branch.trim(), RepoStatus.NONE, "",
                pathFor(slug).toString(), "Not downloaded yet.", java.time.Instant.now().toString());
        repos.put(slug, repo);
        persist();
        return slug;
    }

    /** Apply a lifecycle transition to {@code slug} (no-op if it was removed meanwhile) and persist. */
    public synchronized void update(final String slug, final java.util.function.UnaryOperator<CodeRepo> transition) {
        final CodeRepo current = repos.get(slug);
        if (current == null) {
            return;
        }
        repos.put(slug, transition.apply(current));
        persist();
    }

    /** Remove a repository and delete its local checkout. */
    public synchronized void remove(final String slug) {
        if (repos.remove(slug) != null) {
            deleteRecursively(pathFor(slug));
            persist();
        }
    }

    /**
     * Derive a stable, filesystem-safe slug from a git URL — {@code owner_repo} from the last two path
     * segments, minus any {@code .git}. Handles HTTPS ({@code https://host/owner/repo.git}) and SSH
     * scp-style ({@code git@host:owner/repo.git}).
     */
    public static String slugFor(final String url) {
        String s = url == null ? "" : url.trim();
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        final String path = pathPart(s);
        final String[] parts = path.split("/");
        final String repo = parts.length >= 1 ? parts[parts.length - 1] : path;
        final String owner = parts.length >= 2 ? parts[parts.length - 2] : "";
        final String joined = owner.isBlank() ? repo : owner + "_" + repo;
        final String slug = joined.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-{2,}", "-");
        return slug.isBlank() ? "repo" : slug;
    }

    private static String pathPart(final String url) {
        final int scheme = url.indexOf("://");
        if (scheme >= 0) {
            final String afterScheme = url.substring(scheme + 3);
            final int slash = afterScheme.indexOf('/');
            return slash >= 0 ? afterScheme.substring(slash + 1) : afterScheme;
        }
        final int colon = url.indexOf(':');
        return colon >= 0 ? url.substring(colon + 1) : url;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            final Map<String, CodeRepo> loaded = mapper.readValue(Files.readAllBytes(file),
                    new TypeReference<LinkedHashMap<String, CodeRepo>>() {
                    });
            // A clone interrupted by a restart, or a READY entry whose checkout went missing, is downgraded
            // to NONE so the UI offers a re-download rather than claiming a repo that isn't there.
            loaded.forEach((slug, repo) -> repos.put(slug, reconcile(slug, repo)));
            LOG.info("Loaded {} code repository entry(ies)", repos.size());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {} ({}); starting with no code repositories", file, e.getMessage());
        }
    }

    private CodeRepo reconcile(final String slug, final CodeRepo repo) {
        if (repo.status() == RepoStatus.CLONING) {
            return repo.reset("A download was interrupted by a restart; download again.");
        }
        if (repo.status() == RepoStatus.READY && !Files.isDirectory(pathFor(slug))) {
            return repo.reset("The local checkout is no longer present; download again.");
        }
        return repo;
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(repos));
        } catch (IOException e) {
            LOG.error("Could not persist code repositories: {}", e.getMessage());
        }
    }

    /** Best-effort recursive delete of a checkout directory. */
    static void deleteRecursively(final Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warn("Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warn("Could not walk {} for deletion: {}", dir, e.getMessage());
        }
    }
}
