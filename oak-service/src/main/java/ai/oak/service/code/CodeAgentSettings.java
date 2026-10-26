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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the <b>Code Agent</b> runs, as a setting OAK manages. The agent is a separate service that may live
 * on another host entirely; OAK only needs its base URL to relay repository settings and incidents to it.
 *
 * <p>The URL resolves from an operator override (set in the UI, persisted to {@code <data-dir>/code-agent.json})
 * falling back to the {@code oak.code-agent.base-url} config default. Nothing here is a secret — it is just an
 * address — so it is stored in the clear. OAK holds no git credentials and no code; those live in the agent.
 */
@ApplicationScoped
public class CodeAgentSettings {

    private static final Logger LOG = LoggerFactory.getLogger(CodeAgentSettings.class);
    /** Last-resort default when neither an override nor config provides a URL (e.g. config absent in tests). */
    private static final String DEFAULT_BASE_URL = "http://localhost:9030";

    @ConfigProperty(name = "oak.data-dir")
    Optional<String> dataDir;

    @Inject
    OakConfig config;

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private Path file;
    private volatile String override;

    @PostConstruct
    void init() {
        final Path base = dataDir.filter(d -> !d.isBlank()).map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home", "."), ".oak"));
        file = base.resolve("code-agent.json");
        load();
    }

    /** The effective base URL of the code agent (override if set, else the config default), without a trailing slash. */
    public String baseUrl() {
        final String configured = config == null ? null : config.codeAgent().baseUrl();
        final String effective = override != null && !override.isBlank() ? override : configured;
        return strip(effective == null || effective.isBlank() ? DEFAULT_BASE_URL : effective);
    }

    /** Whether the base URL was overridden from the UI (vs. taken from config). */
    public boolean isOverridden() {
        return override != null && !override.isBlank();
    }

    /** Set (or, with blank, clear) the operator override and persist it. */
    public synchronized void setBaseUrl(final String url) {
        override = url == null || url.isBlank() ? null : strip(url.trim());
        persist();
    }

    private static String strip(final String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            final Map<String, Object> saved = mapper.readValue(Files.readAllBytes(file),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            final Object url = saved.get("baseUrl");
            if (url != null && !url.toString().isBlank()) {
                override = strip(url.toString().trim());
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {} ({}); using the configured code-agent URL", file, e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(Map.of("baseUrl", override == null ? "" : override)));
        } catch (IOException e) {
            LOG.error("Could not persist the code-agent URL: {}", e.getMessage());
        }
    }
}
