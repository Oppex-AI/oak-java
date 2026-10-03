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

/**
 * The mutable connection state for one platform: how to reach it, how to pair with it, and the
 * credentials earned so far. Seeded from {@link ai.oak.service.config.OakConfig}, overlaid by what was
 * persisted, and updated as pairing progresses. Read by the connection thread and the UI, so every
 * accessor is synchronised.
 *
 * <p>The {@code pairingSecret} and {@code token} are secrets — persisted
 * encrypted and never logged.
 */
public final class PlatformState {

    private final String name;
    private String baseUrl;
    private String clientId;
    private String pairingId;
    private String pairingSecret;
    private String token;
    private String connectionId;
    private boolean tokenDelivered;

    public PlatformState(final String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public synchronized String baseUrl() {
        return baseUrl;
    }

    public synchronized void setBaseUrl(final String baseUrl) {
        this.baseUrl = trimToNull(baseUrl);
    }

    public synchronized String clientId() {
        return clientId;
    }

    public synchronized void setClientId(final String clientId) {
        this.clientId = trimToNull(clientId);
    }

    /** The pre-shared workspace pairing secret (from config or the UI), sent on every pairing call. */
    public synchronized String pairingSecret() {
        return pairingSecret;
    }

    public synchronized void setPairingSecret(final String pairingSecret) {
        this.pairingSecret = trimToNull(pairingSecret);
    }

    /** The in-flight pairing id the platform returned from CREATE; null before/after a pairing. */
    public synchronized String pairingId() {
        return pairingId;
    }

    public synchronized void setPairingId(final String pairingId) {
        this.pairingId = trimToNull(pairingId);
    }

    public synchronized void clearPairingId() {
        this.pairingId = null;
    }

    public synchronized String token() {
        return token;
    }

    public synchronized void setToken(final String token, final String connectionId) {
        this.token = trimToNull(token);
        this.connectionId = trimToNull(connectionId);
        this.tokenDelivered = this.token != null;
    }

    public synchronized void clearToken() {
        this.token = null;
        this.connectionId = null;
        this.tokenDelivered = false;
    }

    public synchronized String connectionId() {
        return connectionId;
    }

    public synchronized boolean tokenDelivered() {
        return tokenDelivered;
    }

    public synchronized void setTokenDelivered(final boolean tokenDelivered) {
        this.tokenDelivered = tokenDelivered;
    }

    private static String trimToNull(final String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
