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
package ai.oak.service.db;

import ai.oak.service.secret.SecretStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-{@code dbIdentifier} database connection config the DB tools resolve against, persisted to
 * {@code <data-dir>/db-connections.json}. There are no secrets here (RDS IAM auth is used), so it is stored
 * in the clear. A dbIdentifier with no entry means the platform asked for a database this executor was never
 * configured for — the tools answer {@code NO_CONNECTION_CONFIGURED}.
 */
@ApplicationScoped
public class DbConnections {

    private static final Logger LOG = LoggerFactory.getLogger(DbConnections.class);

    @ConfigProperty(name = "oak.data-dir")
    Optional<String> dataDir;

    @Inject
    SecretStore secrets;

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, DbConnection> connections = new ConcurrentHashMap<>();
    private Path file;

    @PostConstruct
    void init() {
        final Path base = dataDir.filter(d -> !d.isBlank()).map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home", "."), ".oak"));
        file = base.resolve("db-connections.json");
        load();
    }

    /** The connection for {@code dbIdentifier}, or empty when none is configured. */
    public Optional<DbConnection> get(final String dbIdentifier) {
        return Optional.ofNullable(dbIdentifier == null ? null : connections.get(dbIdentifier));
    }

    public Set<String> identifiers() {
        return connections.keySet();
    }

    public Map<String, DbConnection> all() {
        return Map.copyOf(connections);
    }

    /** Add or replace a dbIdentifier's connection config, and persist. */
    public synchronized void update(final String dbIdentifier, final DbConnection connection) {
        connections.put(dbIdentifier, connection);
        persist();
    }

    public synchronized void remove(final String dbIdentifier) {
        connections.remove(dbIdentifier);
        persist();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            final Map<String, DbConnection> loaded = mapper.readValue(Files.readAllBytes(file),
                    new TypeReference<LinkedHashMap<String, DbConnection>>() {
                    });
            loaded.forEach((id, c) -> connections.put(id, c.withPassword(secrets.decrypt(c.password()))));
            LOG.info("Loaded {} database connection(s)", connections.size());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {} ({}); starting with no DB connections", file, e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            // Encrypt the password at rest (IAM-auth connections have none); everything else is non-secret.
            final Map<String, DbConnection> encrypted = new LinkedHashMap<>();
            connections.forEach((id, c) -> encrypted.put(id, c.withPassword(secrets.encrypt(c.password()))));
            Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(encrypted));
        } catch (IOException e) {
            LOG.error("Could not persist DB connections: {}", e.getMessage());
        }
    }
}
