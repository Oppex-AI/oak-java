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

import ai.oak.service.client.DiscoveredResource;
import ai.oak.service.client.DiscoverySnapshot;
import ai.oak.service.connection.ConnectionPhase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A live, in-memory snapshot of what this executor is doing: per-platform connection phase, what it has
 * advertised, and the steps it has run lately. Held so the management UI can answer "is my executor
 * healthy and what has it run" without reaching back to any platform. In-memory and best-effort — it
 * resets on restart and is not the system of record.
 */
@ApplicationScoped
public class AgentStatus {

    private static final int RECENT_LIMIT = 20;

    private volatile List<String> advertised = List.of();
    private final Map<String, Conn> connections = new ConcurrentHashMap<>();
    private final Deque<StepRecord> recent = new ArrayDeque<>();
    private final Map<String, DiscoveryReport> discovery = new ConcurrentHashMap<>();

    /** One executed step, for the recent-activity view. */
    public record StepRecord(Instant at, String platform, Long workflowId, Long taskId, String capability, String status,
            String note) {
    }

    /** The last infrastructure snapshot reported to a platform, for the "what has it discovered" view. */
    public record DiscoveryReport(String platform, Instant at, int resourceCount, boolean changed,
            List<DiscoveredResource> resources) {
    }

    /** An immutable per-platform view for the UI. Secrets are never here; the client id is masked. */
    public record ConnView(String platform, ConnectionPhase phase, String detail, String baseUrl, String clientIdMasked,
            boolean tokenPresent, String connectionId, Instant since, String lastError) {
    }

    private static final class Conn {
        volatile ConnectionPhase phase = ConnectionPhase.IDLE;
        volatile String detail;
        volatile String baseUrl;
        volatile String clientIdMasked;
        volatile boolean tokenPresent;
        volatile String connectionId;
        volatile Instant since;
        volatile String lastError;
    }

    /** The capabilities this service has wired and could run, set once the registry is built. */
    public void wired(final List<String> advertised) {
        this.advertised = List.copyOf(advertised);
    }

    /** Update a platform's phase and a short human detail (e.g. "waiting for admin approval"). */
    public void phase(final String platform, final ConnectionPhase phase, final String detail) {
        final Conn conn = conn(platform);
        conn.phase = phase;
        conn.detail = detail;
        conn.since = Instant.now();
        if (phase != ConnectionPhase.ERROR && phase != ConnectionPhase.DISCONNECTED) {
            conn.lastError = null;
        }
    }

    public void connected(final String platform, final String baseUrl, final String clientId, final String connectionId,
            final boolean tokenPresent) {
        final Conn conn = conn(platform);
        conn.phase = ConnectionPhase.CONNECTED;
        conn.detail = null;
        conn.baseUrl = baseUrl;
        conn.clientIdMasked = mask(clientId);
        conn.connectionId = connectionId;
        conn.tokenPresent = tokenPresent;
        conn.since = Instant.now();
        conn.lastError = null;
    }

    public void error(final String platform, final ConnectionPhase phase, final String error) {
        final Conn conn = conn(platform);
        conn.phase = phase;
        conn.lastError = error;
        conn.since = Instant.now();
    }

    public void meta(final String platform, final String baseUrl, final String clientId, final boolean tokenPresent) {
        final Conn conn = conn(platform);
        conn.baseUrl = baseUrl;
        conn.clientIdMasked = mask(clientId);
        conn.tokenPresent = tokenPresent;
    }

    public synchronized void recordStep(final StepRecord record) {
        recent.addFirst(record);
        while (recent.size() > RECENT_LIMIT) {
            recent.removeLast();
        }
    }

    public List<String> getAdvertised() {
        return advertised;
    }

    public List<ConnView> getConnections() {
        return connections.entrySet().stream()
                .map(e -> new ConnView(e.getKey(), e.getValue().phase, e.getValue().detail, e.getValue().baseUrl,
                        e.getValue().clientIdMasked, e.getValue().tokenPresent, e.getValue().connectionId, e.getValue().since,
                        e.getValue().lastError))
                .sorted((a, b) -> a.platform().compareToIgnoreCase(b.platform())).toList();
    }

    public synchronized List<StepRecord> getRecent() {
        return List.copyOf(recent);
    }

    /** Record the snapshot just reported to a platform, so the UI can show what was last discovered. */
    public void recordDiscovery(final String platform, final DiscoverySnapshot snapshot, final boolean changed) {
        discovery.put(platform,
                new DiscoveryReport(platform, Instant.now(), snapshot.resources().size(), changed, snapshot.resources()));
    }

    /** The last discovery report per platform, newest platforms sorted by name. */
    public List<DiscoveryReport> getDiscovery() {
        return discovery.values().stream().sorted((a, b) -> a.platform().compareToIgnoreCase(b.platform())).toList();
    }

    private Conn conn(final String platform) {
        return connections.computeIfAbsent(platform, k -> new Conn());
    }

    /** Masks an id for display: keep the first 4 chars, hide the rest. Never show the whole thing. */
    private static String mask(final String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        final String s = id.trim();
        return s.length() <= 4 ? "****" : s.substring(0, 4) + "…";
    }
}
