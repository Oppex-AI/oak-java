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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.oak.tools.cli.CommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The downloader: the clone argv is pinned as a pure unit, and the happy / failure paths are exercised
 * end-to-end by cloning a throwaway local repository (skipped where {@code git} isn't on the build host).
 */
class RepoDownloaderTest {

    @Test
    void cloneArgsAreShallowAndSingleBranch() {
        final Path dir = Path.of("/tmp/x");
        assertEquals(List.of("git", "clone", "--depth", "1", "--single-branch", "https://h/r.git", dir.toString()),
                RepoDownloader.cloneArgs("https://h/r.git", null, dir));
        assertEquals(
                List.of("git", "clone", "--depth", "1", "--single-branch", "--branch", "dev", "https://h/r.git", dir.toString()),
                RepoDownloader.cloneArgs("https://h/r.git", "dev", dir));
    }

    @Test
    void downloadsALocalRepoAndReportsReady(@TempDir final Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "git is not installed on this build host");
        final String url = makeSourceRepo(tmp.resolve("src")).toUri().toString();

        final CodeRepos store = store(tmp.resolve("data"));
        final RepoDownloader downloader = downloader(store);
        final String slug = store.upsert(url, null);
        downloader.enqueue(slug);
        assertTrue(downloader.awaitIdle(60_000), "download should finish within the timeout");

        final CodeRepo repo = store.get(slug).orElseThrow();
        assertEquals(RepoStatus.READY, repo.status(), repo.detail());
        assertFalse(repo.commit().isBlank(), "a ready repo records its HEAD commit");
        assertTrue(Files.exists(store.pathFor(slug).resolve("README.md")), "the checked-out file is present");
    }

    @Test
    void anUnreachableRepoIsReportedFailed(@TempDir final Path tmp) throws Exception {
        assumeTrue(gitAvailable(), "git is not installed on this build host");
        final CodeRepos store = store(tmp.resolve("data"));
        final RepoDownloader downloader = downloader(store);
        final String slug = store.upsert(tmp.resolve("does-not-exist").toUri().toString(), null);
        downloader.enqueue(slug);
        assertTrue(downloader.awaitIdle(60_000));

        assertEquals(RepoStatus.FAILED, store.get(slug).orElseThrow().status());
    }

    private static CodeRepos store(final Path dataDir) {
        final CodeRepos s = new CodeRepos();
        s.dataDir = Optional.of(dataDir.toString());
        s.init();
        return s;
    }

    private static RepoDownloader downloader(final CodeRepos store) {
        final RepoDownloader d = new RepoDownloader();
        d.repos = store;
        return d;
    }

    private static boolean gitAvailable() {
        return CommandRunner.run(List.of("git", "--version")).success();
    }

    /** Create a one-commit git repo and return its path. */
    private static Path makeSourceRepo(final Path src) throws Exception {
        Files.createDirectories(src);
        Files.writeString(src.resolve("README.md"), "hello");
        final Map<String, String> env = Map.of("GIT_TERMINAL_PROMPT", "0");
        run(src, env, "git", "init", "-q");
        run(src, env, "git", "config", "user.email", "t@example.com");
        run(src, env, "git", "config", "user.name", "Test");
        run(src, env, "git", "add", "-A");
        run(src, env, "git", "commit", "-q", "-m", "init");
        return src;
    }

    private static void run(final Path dir, final Map<String, String> env, final String... argv) {
        final List<String> cmd = new java.util.ArrayList<>();
        cmd.add(argv[0]);
        cmd.add("-C");
        cmd.add(dir.toString());
        for (int i = 1; i < argv.length; i++) {
            cmd.add(argv[i]);
        }
        // `git init -C` needs the dir to exist (it does); other subcommands run inside it.
        CommandRunner.run(cmd, env);
    }
}
