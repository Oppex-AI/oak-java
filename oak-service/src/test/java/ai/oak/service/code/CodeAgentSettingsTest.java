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

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The code-agent URL setting: default, override, trailing-slash normalisation, and persistence. */
class CodeAgentSettingsTest {

    private CodeAgentSettings settings(final Path dir) {
        final CodeAgentSettings s = new CodeAgentSettings();
        s.dataDir = Optional.of(dir.toString());
        s.init(); // config left null → falls back to the built-in default
        return s;
    }

    @Test
    void defaultsWhenNothingSet(@TempDir final Path dir) {
        final CodeAgentSettings s = settings(dir);
        assertEquals("http://localhost:9030", s.baseUrl());
        assertFalse(s.isOverridden());
    }

    @Test
    void overrideIsNormalisedAndFlagged(@TempDir final Path dir) {
        final CodeAgentSettings s = settings(dir);
        s.setBaseUrl("https://code-agent.internal:9030/");
        assertEquals("https://code-agent.internal:9030", s.baseUrl(), "a trailing slash is stripped");
        assertTrue(s.isOverridden());
    }

    @Test
    void overridePersistsAcrossInstances(@TempDir final Path dir) {
        settings(dir).setBaseUrl("https://remote:9999");
        final CodeAgentSettings reloaded = settings(dir);
        assertEquals("https://remote:9999", reloaded.baseUrl());
        assertTrue(reloaded.isOverridden());
    }

    @Test
    void blankClearsTheOverride(@TempDir final Path dir) {
        final CodeAgentSettings s = settings(dir);
        s.setBaseUrl("https://remote:9999");
        s.setBaseUrl("  ");
        assertEquals("http://localhost:9030", s.baseUrl());
        assertFalse(s.isOverridden());
    }
}
