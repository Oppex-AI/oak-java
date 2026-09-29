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
package ai.oak.service;

import ai.oak.service.secret.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-provider-group execution settings — the environment a tool's command needs to reach the client's
 * own account: an AWS profile/region (or keys), a docker host, and so on. Keyed by group ("AWS",
 * "Docker", …) as {@link ToolCatalog#groupOf} reports it; each is a map of environment variables applied
 * to the process when a tool of that group runs.
 *
 * <p>This is what makes the service generic: the tool code is the same everywhere, and each client
 * supplies how to authenticate to their cloud here (the recommended path is an {@code AWS_PROFILE} whose
 * role/SSO is set up in the client's {@code ~/.aws/config}; explicit keys are supported too). Values
 * whose name looks like a secret ({@code SECRET}/{@code TOKEN}/{@code PASSWORD}/{@code ACCESS_KEY}) are
 * persisted encrypted via {@link SecretStore} and never returned to the UI.
 */
@ApplicationScoped
public class ToolSettings {

    private static final Logger LOG = LoggerFactory.getLogger(ToolSettings.class);
    private static final String[] SECRET_MARKERS = {"SECRET", "TOKEN", "PASSWORD", "ACCESS_KEY"};

    @Inject
    SecretStore secrets;

    @ConfigProperty(name = "oak.data-dir")
    Optional<String> dataDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, String>> byGroup = new ConcurrentHashMap<>();
    private Path file;

    @PostConstruct
    void init() {
        final Path base = dataDir.filter(d -> !d.isBlank()).map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home", "."), ".oak"));
        file = base.resolve("tool-settings.json");
        load();
    }

    /** The environment variables to apply when running a tool of {@code group}. Never null. */
    public Map<String, String> envFor(final String group) {
        return Map.copyOf(byGroup.getOrDefault(group, Map.of()));
    }

    /** All groups and their variables, with secret values blanked — for the UI. */
    public Map<String, Map<String, String>> masked() {
        final Map<String, Map<String, String>> out = new LinkedHashMap<>();
        byGroup.forEach((group, env) -> {
            final Map<String, String> view = new LinkedHashMap<>();
            env.forEach((k, v) -> view.put(k, isSecret(k) ? "" : v));
            out.put(group, view);
        });
        return out;
    }

    /**
     * Merge {@code env} into a group's variables. A blank value removes a plain variable, and <b>keeps</b>
     * an existing secret (so the UI can leave a secret field blank to preserve it); a non-blank value sets.
     */
    public synchronized void update(final String group, final Map<String, String> env) {
        final Map<String, String> current = new LinkedHashMap<>(byGroup.getOrDefault(group, Map.of()));
        env.forEach((k, v) -> {
            if (k == null || k.isBlank()) {
                return;
            }
            if (v == null || v.isBlank()) {
                if (!isSecret(k)) {
                    current.remove(k);
                }
            } else {
                current.put(k.trim(), v.trim());
            }
        });
        byGroup.put(group, current);
        persist();
    }

    /** The stable, OAK-generated external id for a group's role trust (created + persisted on first use). */
    public synchronized String ensureExternalId(final String group) {
        final Map<String, String> env = new LinkedHashMap<>(byGroup.getOrDefault(group, Map.of()));
        String id = env.get("OAK_EXTERNAL_ID");
        if (id == null || id.isBlank()) {
            id = "oak-" + java.util.UUID.randomUUID();
            env.put("OAK_EXTERNAL_ID", id);
            byGroup.put(group, env);
            persist();
        }
        return id;
    }

    public static boolean isSecret(final String key) {
        final String upper = key.toUpperCase(java.util.Locale.ROOT);
        for (final String marker : SECRET_MARKERS) {
            if (upper.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            final ObjectNode root = mapper.createObjectNode();
            byGroup.forEach((group, env) -> {
                final ObjectNode node = root.putObject(group);
                env.forEach((k, v) -> node.put(k, isSecret(k) ? secrets.encrypt(v) : v));
            });
            Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (IOException | UnsupportedOperationException ignore) {
                // non-POSIX filesystem — best effort
            }
        } catch (IOException e) {
            LOG.error("Could not persist tool settings: {}", e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            final JsonNode root = mapper.readTree(Files.readAllBytes(file));
            root.fieldNames().forEachRemaining(group -> {
                final Map<String, String> env = new LinkedHashMap<>();
                final JsonNode node = root.get(group);
                node.fieldNames().forEachRemaining(
                        k -> env.put(k, isSecret(k) ? secrets.decrypt(node.get(k).asText()) : node.get(k).asText()));
                byGroup.put(group, env);
            });
            LOG.info("Loaded tool settings for {}", byGroup.keySet());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read tool settings ({}); starting empty", e.getMessage());
        }
    }
}
