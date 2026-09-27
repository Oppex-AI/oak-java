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
package ai.oak.service.ui;

import ai.oak.service.ExecutorAgent;
import ai.oak.service.connection.ConnectionStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Sets a platform's connection from the management page: the base URL and the workspace client id used
 * to pair (and, for dev/manual bootstrap, an optional pre-issued API key). Secrets are persisted
 * encrypted, never baked into the image; the executor re-pairs / re-registers immediately. Write-only
 * for secrets — they are never read back.
 */
@Path("/api")
public class SettingsResource {

    @Inject
    ConnectionStore store;

    @Inject
    ExecutorAgent agent;

    @POST
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> save(final SettingsRequest req) {
        final String platform = req != null && req.platform() != null && !req.platform().isBlank()
                ? req.platform().trim()
                : defaultPlatform();
        store.update(platform, req == null ? null : req.baseUrl(), req == null ? null : req.clientId(),
                req == null ? null : req.apiKey());
        agent.reconnect(platform);
        return Map.of("ok", true, "platform", platform);
    }

    private String defaultPlatform() {
        return store.names().stream().findFirst().orElse("oppex");
    }

    /** Inbound settings. A blank {@code apiKey} keeps the current bootstrap key. */
    public record SettingsRequest(String platform, String baseUrl, String clientId, String apiKey) {
    }
}
