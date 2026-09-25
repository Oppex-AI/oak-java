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

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A live, in-memory snapshot of what this executor is doing: is it connected, what has it advertised,
 * and what steps has it run lately. Held so the management UI can answer "is my executor healthy and
 * what has it run" without reaching back to the platform.
 *
 * <p>In-memory and best-effort — it resets on restart and is not the system of record; the platform is.
 */
@ApplicationScoped
public class AgentStatus {

    /** How many recent steps to keep. Enough to see the last few incidents, not a log store. */
    private static final int RECENT_LIMIT = 20;

    private volatile boolean connected;
    private volatile String platformName = "";
    private volatile String baseUrl = "";
    private volatile Instant registeredAt;
    private volatile String lastError;
    private volatile List<String> advertised = List.of();

    private final Deque<StepRecord> recent = new ArrayDeque<>();

    /** One executed step, for the recent-activity view. */
    public record StepRecord(Instant at, Long workflowId, Long taskId, String capability, String status, String note) {
    }

    /** The capabilities this service has wired and could run, set once the registry is built. */
    public synchronized void wired(final List<String> advertised) {
        this.advertised = List.copyOf(advertised);
    }

    public synchronized void connected(final String platformName, final String baseUrl) {
        this.connected = true;
        this.platformName = platformName;
        this.baseUrl = baseUrl;
        this.registeredAt = Instant.now();
        this.lastError = null;
    }

    public synchronized void disconnected(final String error) {
        this.connected = false;
        this.lastError = error;
    }

    public synchronized void recordStep(final StepRecord record) {
        recent.addFirst(record);
        while (recent.size() > RECENT_LIMIT) {
            recent.removeLast();
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public String getPlatformName() {
        return platformName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public String getLastError() {
        return lastError;
    }

    public List<String> getAdvertised() {
        return advertised;
    }

    public synchronized List<StepRecord> getRecent() {
        return List.copyOf(recent);
    }
}
