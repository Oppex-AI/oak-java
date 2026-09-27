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

    private final Map<String, PlatformConnection> connections = new ConcurrentHashMap<>();
    private ToolRegistry registry;
    private ExecutorService workers;

    void onStart(@Observes final StartupEvent event) {
        registry = catalog.registry();
        status.wired(registry.all().stream().map(Tool::capability).toList());
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
        final PlatformConnection.Context ctx = new PlatformConnection.Context(store, registry, workers, status,
                config.service().name(), config.service().version(), config.poll().interval().toMillis());
        return new PlatformConnection(name, store.get(name), ctx);
    }
}
