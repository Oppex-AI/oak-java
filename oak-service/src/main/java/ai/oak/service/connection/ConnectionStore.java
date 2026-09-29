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
package ai.oak.service.connection;

import ai.oak.service.config.OakConfig;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the {@link PlatformState} for every configured platform and persists it across restarts.
 *
 * <p>On startup each platform's state is seeded from {@link OakConfig}, then overlaid by a persisted
 * {@code <data-dir>/connections/<name>.json} if one exists (so a pasted URL / an earned token survive a
 * restart). Secrets — the pairing secret and the token — are written through
 * {@link SecretStore} (encrypted); ids and URLs are written in the clear.
 */
@ApplicationScoped
public class ConnectionStore {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionStore.class);

    @Inject
    OakConfig config;

    @Inject
    SecretStore secrets;

    @ConfigProperty(name = "oak.data-dir")
    Optional<String> dataDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, PlatformState> states = new ConcurrentHashMap<>();
    private Path dir;

    @PostConstruct
    void init() {
        final Path base = dataDir.filter(d -> !d.isBlank()).map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home", "."), ".oak"));
        dir = base.resolve("connections");
        for (final Map.Entry<String, OakConfig.Platform> entry : config.platforms().entrySet()) {
            final PlatformState state = new PlatformState(entry.getKey());
            state.setBaseUrl(entry.getValue().baseUrl().orElse(null));
            entry.getValue().clientId().ifPresent(state::setClientId);
            entry.getValue().pairingSecret().ifPresent(state::setPairingSecret);
            loadPersisted(state);
            states.put(entry.getKey(), state);
        }
        LOG.info("Connection store ready: {} platform(s) configured {}", states.size(), states.keySet());
    }

    public Set<String> names() {
        return states.keySet();
    }

    public PlatformState get(final String name) {
        return states.get(name);
    }

    /**
     * UI edit: change how a platform is reached. A blank {@code pairingSecret} keeps the current one.
     * Changing the identity (URL or client id) drops the earned token and any in-flight pairing; changing
     * just the secret drops the in-flight pairing but keeps a working token.
     */
    public synchronized void update(final String name, final String baseUrl, final String clientId, final String pairingSecret) {
        final PlatformState state = states.computeIfAbsent(name, PlatformState::new);
        final boolean identityChanged = !equalsTrimmed(state.baseUrl(), baseUrl) || !equalsTrimmed(state.clientId(), clientId);
        final boolean secretChanged = pairingSecret != null && !pairingSecret.isBlank() &&
                !equalsTrimmed(state.pairingSecret(), pairingSecret);
        state.setBaseUrl(baseUrl);
        state.setClientId(clientId);
        if (pairingSecret != null && !pairingSecret.isBlank()) {
            state.setPairingSecret(pairingSecret);
        }
        if (identityChanged) {
            state.clearToken();
            state.clearPairingId();
        } else if (secretChanged) {
            state.clearPairingId();
        }
        persist(state);
    }

    /** Writes a platform's state to disk, encrypting the secrets. Best-effort; logs on failure. */
    public synchronized void persist(final PlatformState state) {
        try {
            Files.createDirectories(dir);
            final ObjectNode node = mapper.createObjectNode();
            putIfPresent(node, "baseUrl", state.baseUrl());
            putIfPresent(node, "clientId", state.clientId());
            putIfPresent(node, "pairingId", state.pairingId());
            putIfPresent(node, "pairingSecret", secrets.encrypt(state.pairingSecret()));
            putIfPresent(node, "token", secrets.encrypt(state.token()));
            putIfPresent(node, "connectionId", state.connectionId());
            node.put("tokenDelivered", state.tokenDelivered());
            final Path file = file(state.name());
            Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
            restrict(file);
        } catch (IOException e) {
            LOG.error("Could not persist connection state for {}: {}", state.name(), e.getMessage());
        }
    }

    private void loadPersisted(final PlatformState state) {
        final Path file = file(state.name());
        if (!Files.exists(file)) {
            return;
        }
        try {
            overlay(state, mapper.readTree(Files.readAllBytes(file)));
            LOG.info("Loaded persisted connection state for {}", state.name());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read connection state for {} ({}); using config defaults", state.name(), e.getMessage());
        }
    }

    /**
     * Overlay a persisted document onto the config-seeded state, decrypting the secret fields. Each secret
     * is decrypted independently: if the encryption key changed since it was written (a rotated/lost {@code
     * oak.key}), that one field is skipped with an actionable warning rather than discarding the whole
     * record — so the client id and URL survive and the operator just needs to re-pair.
     */
    private void overlay(final PlatformState state, final JsonNode node) {
        if (node.hasNonNull("baseUrl")) {
            state.setBaseUrl(node.get("baseUrl").asText());
        }
        if (node.hasNonNull("clientId")) {
            state.setClientId(node.get("clientId").asText());
        }
        if (node.hasNonNull("pairingSecret")) {
            final String secret = tryDecrypt(node.get("pairingSecret").asText(), state.name(), "pairing secret");
            if (secret != null) {
                state.setPairingSecret(secret);
            }
        }
        if (node.hasNonNull("pairingId")) {
            state.setPairingId(node.get("pairingId").asText());
        }
        if (node.hasNonNull("token")) {
            final String token = tryDecrypt(node.get("token").asText(), state.name(), "token");
            if (token != null) {
                state.setToken(token, textOrNull(node, "connectionId"));
            }
        }
        state.setTokenDelivered(node.path("tokenDelivered").asBoolean(state.token() != null));
    }

    /** Decrypt one persisted secret, or null (with an actionable log) if the encryption key no longer matches. */
    private String tryDecrypt(final String ciphertext, final String platform, final String what) {
        try {
            return secrets.decrypt(ciphertext);
        } catch (IllegalStateException e) {
            LOG.warn("[{}] saved {} could not be decrypted — the encryption key changed (a new oak.key, or a new " +
                    "OAK_SECRET_PASSPHRASE). Re-pair from Settings to restore the connection.", platform, what);
            return null;
        }
    }

    private Path file(final String name) {
        return dir.resolve(name.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json");
    }

    private static void putIfPresent(final ObjectNode node, final String field, final String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static String textOrNull(final JsonNode node, final String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static boolean equalsTrimmed(final String a, final String b) {
        final String x = a == null ? "" : a.trim();
        final String y = b == null ? "" : b.trim();
        return x.equals(y);
    }

    private static void restrict(final Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignore) {
            // non-POSIX filesystem — best effort
        }
    }
}
