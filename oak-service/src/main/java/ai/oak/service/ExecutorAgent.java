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

import ai.oak.service.config.OakConfig;
import ai.oak.service.connection.ConnectionStore;
import ai.oak.service.connection.PlatformConnection;
import ai.oak.tools.Tool;
import ai.oak.tools.ToolRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the executor: builds the tool registry once, then runs one {@link PlatformConnection} per
 * configured platform — each pairs, registers and polls independently, on a shared worker pool. Every
 * platform runs the same handshake; Oppex is just the first configured one.
 */
@ApplicationScoped
public class ExecutorAgent {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorAgent.class);

    @Inject
    OakConfig config;

    @Inject
    ConnectionStore store;

    @Inject
    AgentStatus status;

    @Inject
    ToolCatalog catalog;

    @Inject
    ToolSettings toolSettings;

    @Inject
    AwsCredentials awsCredentials;

    private final Map<String, PlatformConnection> connections = new ConcurrentHashMap<>();
    private ToolRegistry registry;
    private ExecutorService workers;

    void onStart(@Observes final StartupEvent event) {
        registry = catalog.registry();
        status.wired(registry.all().stream().map(Tool::capability).toList());
        // Generate + persist the AWS external id up front so it exists in the data dir from first boot and
        // survives restarts/updates — the client's role trust policy is built around this stable value.
        LOG.info("AWS external id: {} (in {}/tool-settings.json)", toolSettings.ensureExternalId("AWS"),
                config.dataDir().filter(d -> !d.isBlank()).orElse("~/.oak"));
        workers = Executors.newFixedThreadPool(Math.max(1, config.poll().workerThreads()), r -> {
            final Thread t = new Thread(r, "oak-worker");
            t.setDaemon(true);
            return t;
        });
        if (store.names().isEmpty()) {
            LOG.warn("No platforms configured (oak.platforms.*). {} tool(s) wired and idle.", registry.all().size());
            return;
        }
        for (final String name : store.names()) {
            connections.computeIfAbsent(name, this::newConnection).start();
        }
    }

    void onStop(@Observes final ShutdownEvent event) {
        connections.values().forEach(PlatformConnection::stop);
        connections.clear();
        if (workers != null) {
            workers.shutdown();
        }
    }

    /** Restart one platform's connection after the UI changed its settings. Starts it if it is new. */
    public synchronized void reconnect(final String platform) {
        final PlatformConnection existing = connections.get(platform);
        if (existing != null) {
            existing.reconnect();
            return;
        }
        if (store.get(platform) != null) {
            final PlatformConnection created = newConnection(platform);
            connections.put(platform, created);
            created.start();
        }
    }

    private PlatformConnection newConnection(final String name) {
        final var service = new PlatformConnection.ServiceId(config.service().name(), config.service().version());
        final PlatformConnection.Context ctx = new PlatformConnection.Context(store, registry, workers, status, service,
                this::envForCapability, catalog::isActive);
        return new PlatformConnection(name, store.get(name), ctx, config.poll().interval().toMillis());
    }

    /** The environment a tool of this capability runs with. AWS assumes the linked role; others get their env. */
    private Map<String, String> envForCapability(final String capability) {
        final String group = ToolCatalog.groupOf(capability);
        final Map<String, String> raw = toolSettings.envFor(group);
        if ("AWS".equals(group)) {
            return awsCredentials.assume(raw.get("AWS_ROLE_ARN"), raw.get("OAK_EXTERNAL_ID"), raw.get("AWS_REGION"));
        }
        return raw;
    }
}
