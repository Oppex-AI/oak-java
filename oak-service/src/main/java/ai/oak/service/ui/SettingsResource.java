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
import ai.oak.service.config.RuntimeSettings;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Sets the platform connection from the management page: the base URL and the API key. The key is
 * persisted (not baked into the image) and the executor re-registers immediately. Write-only for the
 * key — it is never read back (the status endpoint reports only whether one is configured).
 */
@Path("/api")
public class SettingsResource {

    @Inject
    RuntimeSettings settings;

    @Inject
    ExecutorAgent agent;

    @POST
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> save(final SettingsRequest req) {
        settings.update(req == null ? null : req.baseUrl(), req == null ? null : req.apiKey());
        agent.reconnect();
        return Map.of("ok", true, "baseUrl", settings.baseUrl() == null ? "" : settings.baseUrl(), "apiKeyConfigured",
                settings.apiKeyConfigured());
    }

    /** Inbound settings. A blank {@code apiKey} keeps the current key. */
    public record SettingsRequest(String baseUrl, String apiKey) {
    }
}
