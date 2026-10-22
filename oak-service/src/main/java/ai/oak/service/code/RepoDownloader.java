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

import ai.oak.tools.ToolResult;
import ai.oak.tools.cli.CommandRunner;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads a configured repository by shelling out to the host's {@code git}, off the request thread, and
 * records the outcome back into {@link CodeRepos}. The clone is shallow and single-branch — OAK wants the
 * code present and ready for the Code Agent, not its full history.
 *
 * <p>OAK holds no git credentials: {@code git} is invoked with {@code GIT_TERMINAL_PROMPT=0} so a repo the
 * host isn't already authorised for fails fast (reported as {@link RepoStatus#FAILED}) instead of blocking
 * on an interactive password prompt.
 */
@ApplicationScoped
public class RepoDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(RepoDownloader.class);
    // A clone (even shallow) over a slow link can take a while; generous, but bounded so a worker can't wedge.
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration REV_PARSE_TIMEOUT = Duration.ofSeconds(30);
    // Don't block on a credential prompt — a repo the host's git can't reach is a reportable failure.
    private static final Map<String, String> GIT_ENV = Map.of("GIT_TERMINAL_PROMPT", "0");

    @Inject
    CodeRepos repos;

    private final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "oak-repo-downloader");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    /** Queue a (re)download of {@code slug}. Returns immediately; progress is tracked in {@link CodeRepos}. */
    public void enqueue(final String slug) {
        repos.update(slug, CodeRepo::cloning);
        workers.submit(() -> run(slug));
    }

    private void run(final String slug) {
        final CodeRepo repo = repos.get(slug).orElse(null);
        if (repo == null) {
            return;
        }
        final Path dir = repos.pathFor(slug);
        try {
            // Start from a clean directory so a re-download can't collide with a previous checkout.
            CodeRepos.deleteRecursively(dir);
            java.nio.file.Files.createDirectories(dir.getParent());
            final ToolResult clone = CommandRunner.run(cloneArgs(repo.url(), repo.branchOrNull(), dir), GIT_ENV, CLONE_TIMEOUT);
            if (!clone.success()) {
                final String why = clone.timedOut() ? "git clone timed out after " + CLONE_TIMEOUT : tail(clone.stderr());
                repos.update(slug, r -> r.failed("Download failed: " + why));
                LOG.warn("Code repo '{}' download failed: {}", slug, why);
                return;
            }
            final ToolResult head = CommandRunner.run(List.of("git", "-C", dir.toString(), "rev-parse", "HEAD"), GIT_ENV,
                    REV_PARSE_TIMEOUT);
            final String commit = head.success() ? head.stdout().strip() : "";
            repos.update(slug, r -> r.ready(commit));
            LOG.info("Code repo '{}' ready at {} (commit {})", slug, dir, commit.isBlank() ? "unknown" : commit);
        } catch (java.io.IOException e) {
            repos.update(slug, r -> r.failed("Could not prepare the download directory: " + e.getMessage()));
            LOG.warn("Code repo '{}' download could not start: {}", slug, e.getMessage());
        }
    }

    /** The shallow, single-branch clone argv. Package-private + pure, so the argument shape is unit-tested. */
    static List<String> cloneArgs(final String url, final String branch, final Path dir) {
        final List<String> argv = new ArrayList<>(List.of("git", "clone", "--depth", "1", "--single-branch"));
        if (branch != null && !branch.isBlank()) {
            argv.add("--branch");
            argv.add(branch.trim());
        }
        argv.add(url);
        argv.add(dir.toString());
        return argv;
    }

    /** Keep the last few lines of git's stderr for the UI — the actual error is at the end. */
    private static String tail(final String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "git reported no detail";
        }
        final String[] lines = stderr.strip().split("\\R");
        final int from = Math.max(0, lines.length - 4);
        return String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    /** For tests: block until queued work drains. */
    boolean awaitIdle(final long timeoutMs) throws InterruptedException {
        workers.shutdown();
        return workers.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
