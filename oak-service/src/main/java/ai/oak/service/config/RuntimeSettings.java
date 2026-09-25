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
package ai.oak.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The platform connection settings that can change at runtime — the base URL and the API key — set from
 * the management UI and persisted so they survive restarts. This keeps the credential out of the image
 * and the deploy: the operator pastes it into the page once.
 *
 * <p>On startup the values default to whatever {@link PlatformConfig} provides (env / config), then a
 * persisted {@code settings.json} (under {@code oak.data.dir}, default {@code ~/.oak}) overlays them.
 * {@link #update} writes the file and the {@code ExecutorAgent} re-registers.
 */
@ApplicationScoped
public class RuntimeSettings {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeSettings.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    PlatformConfig config;

    @ConfigProperty(name = "oak.data.dir", defaultValue = "")
    String dataDir;

    private Path file;
    private volatile String baseUrl;
    private volatile String apiKey;

    @PostConstruct
    void init() {
        final Path dir = (dataDir == null || dataDir.isBlank())
                ? Path.of(System.getProperty("user.home", "."), ".oak")
                : Path.of(dataDir);
        file = dir.resolve("settings.json");
        baseUrl = config.platform().baseUrl().filter(s -> !s.isBlank()).orElse(null);
        apiKey = config.platform().apiKey().filter(s -> !s.isBlank()).orElse(null);
        loadPersisted();
    }

    private void loadPersisted() {
        try {
            if (Files.exists(file)) {
                final var node = mapper.readTree(Files.readAllBytes(file));
                if (node.hasNonNull("baseUrl")) {
                    baseUrl = emptyToNull(node.get("baseUrl").asText());
                }
                if (node.hasNonNull("apiKey")) {
                    apiKey = emptyToNull(node.get("apiKey").asText());
                }
                LOG.info("Loaded runtime platform settings from {}", file);
            }
        } catch (IOException e) {
            LOG.warn("Could not read {} ({}); using config/env defaults", file, e.getMessage());
        }
    }

    public synchronized String baseUrl() {
        return baseUrl;
    }

    public synchronized String apiKey() {
        return apiKey;
    }

    public synchronized boolean apiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Update the settings and persist. A blank {@code newApiKey} keeps the current key (so the URL can be
     * changed without re-entering the secret).
     */
    public synchronized void update(final String newBaseUrl, final String newApiKey) {
        baseUrl = emptyToNull(newBaseUrl);
        if (newApiKey != null && !newApiKey.isBlank()) {
            apiKey = newApiKey.trim();
        }
        persist();
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            final ObjectNode obj = mapper.createObjectNode();
            obj.put("baseUrl", baseUrl == null ? "" : baseUrl);
            obj.put("apiKey", apiKey == null ? "" : apiKey);
            Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(obj));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (IOException | UnsupportedOperationException ignore) {
                // non-POSIX FS — best effort
            }
            LOG.info("Saved runtime platform settings to {}", file);
        } catch (IOException e) {
            LOG.error("Could not persist settings to {}: {}", file, e.getMessage());
        }
    }

    private static String emptyToNull(final String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
