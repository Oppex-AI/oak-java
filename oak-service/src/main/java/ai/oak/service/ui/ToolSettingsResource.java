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

import ai.oak.service.ToolSettings;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The per-provider execution settings the Tools/Settings page edits — the AWS profile/region, docker
 * host, etc. that each tool group runs with. Secret values are never returned (only whether one is set);
 * changes take effect on the next step, no restart needed.
 */
@Path("/api")
public class ToolSettingsResource {

    /** Groups always shown in the UI, even when empty, so the client can fill them in. */
    private static final List<String> KNOWN_GROUPS = List.of("AWS", "Docker");

    @Inject
    ToolSettings settings;

    @GET
    @Path("/tool-settings")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, GroupView> get() {
        final var groups = new TreeSet<String>(KNOWN_GROUPS);
        groups.addAll(settings.masked().keySet());
        final var out = new java.util.LinkedHashMap<String, GroupView>();
        for (final String group : groups) {
            final Map<String, String> env = settings.envFor(group);
            final var values = new java.util.LinkedHashMap<String, String>();
            final var secretsSet = new java.util.ArrayList<String>();
            env.forEach((k, v) -> {
                if (ToolSettings.isSecret(k)) {
                    secretsSet.add(k);
                } else {
                    values.put(k, v);
                }
            });
            out.put(group, new GroupView(values, secretsSet));
        }
        return out;
    }

    @POST
    @Path("/tool-settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> save(final SaveRequest req) {
        if (req == null || req.group() == null || req.group().isBlank()) {
            return Map.of("ok", false, "error", "group is required");
        }
        settings.update(req.group().trim(), req.env() == null ? Map.of() : req.env());
        return Map.of("ok", true, "group", req.group().trim());
    }

    /** Non-secret values (shown), plus the names of secret variables that are set (values withheld). */
    public record GroupView(Map<String, String> values, List<String> secretsSet) {
    }

    /** A group's variables to merge; a blank value removes a plain var and keeps an existing secret. */
    public record SaveRequest(String group, Map<String, String> env) {
    }
}
