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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The code-repo store: slug derivation, persistence, lifecycle transitions, and boot-time reconciliation. */
class CodeReposTest {

    private CodeRepos store(final Path dir) {
        final CodeRepos s = new CodeRepos();
        s.dataDir = Optional.of(dir.toString());
        s.init();
        return s;
    }

    @Test
    void slugFromHttpsAndSshUrls() {
        assertEquals("acme_widgets", CodeRepos.slugFor("https://github.com/acme/widgets.git"));
        assertEquals("acme_widgets", CodeRepos.slugFor("https://github.com/acme/widgets"));
        assertEquals("acme_widgets", CodeRepos.slugFor("git@github.com:acme/widgets.git"));
        assertEquals("acme_widgets", CodeRepos.slugFor("https://github.com/acme/widgets/"));
        // Deeper path (e.g. GitLab subgroup) keeps the last two segments.
        assertEquals("sub_widgets", CodeRepos.slugFor("https://gitlab.com/group/sub/widgets.git"));
    }

    @Test
    void upsertRegistersAtStatusNoneUnderReposDir(@TempDir final Path dir) {
        final CodeRepos s = store(dir);
        final String slug = s.upsert("https://github.com/acme/widgets.git", "main");
        assertEquals("acme_widgets", slug);
        final CodeRepo repo = s.get(slug).orElseThrow();
        assertEquals(RepoStatus.NONE, repo.status());
        assertEquals("main", repo.branch());
        assertEquals(dir.resolve("repos").resolve(slug).toString(), repo.localPath());
    }

    @Test
    void transitionsPersistAndReloadAcrossStores(@TempDir final Path dir) throws Exception {
        final CodeRepos s1 = store(dir);
        final String slug = s1.upsert("https://github.com/acme/widgets.git", null);
        // Make the checkout dir exist so the reload doesn't downgrade a READY entry.
        Files.createDirectories(s1.pathFor(slug));
        s1.update(slug, CodeRepo::cloning);
        s1.update(slug, r -> r.ready("abcdef1234"));

        final CodeRepos s2 = store(dir);
        final CodeRepo reloaded = s2.get(slug).orElseThrow();
        assertEquals(RepoStatus.READY, reloaded.status());
        assertEquals("abcdef1234", reloaded.commit());
    }

    @Test
    void readyEntryWithoutCheckoutIsDowngradedOnLoad(@TempDir final Path dir) {
        final CodeRepos s1 = store(dir);
        final String slug = s1.upsert("https://github.com/acme/widgets.git", null);
        s1.update(slug, r -> r.ready("abcdef")); // persisted READY, but no directory on disk

        final CodeRepo reloaded = store(dir).get(slug).orElseThrow();
        assertEquals(RepoStatus.NONE, reloaded.status(), "a READY entry whose checkout is gone must reset to NONE");
    }

    @Test
    void removeDropsEntryAndDeletesCheckout(@TempDir final Path dir) throws Exception {
        final CodeRepos s = store(dir);
        final String slug = s.upsert("https://github.com/acme/widgets.git", null);
        final Path checkout = s.pathFor(slug);
        Files.createDirectories(checkout);
        Files.writeString(checkout.resolve("README.md"), "hi");

        s.remove(slug);
        assertTrue(s.get(slug).isEmpty());
        assertFalse(Files.exists(checkout), "the local checkout must be deleted with the entry");
    }
}
