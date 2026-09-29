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

import ai.oak.service.AwsCredentials;
import ai.oak.service.ToolSettings;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The per-provider execution settings the Settings page edits — the AWS role link (role ARN + region,
 * with OAK's generated external id) and the docker host. Secret values are never returned (only whether
 * set); changes take effect on the next step. For AWS it also returns this host's own identity so the UI
 * can render the exact role trust policy the client should create.
 */
@Path("/api")
public class ToolSettingsResource {

    private static final List<String> KNOWN_GROUPS = List.of("AWS", "Docker");
    /** Keys shown as their own read-only fields, not as editable values. */
    private static final List<String> RESERVED = List.of("OAK_EXTERNAL_ID");

    @Inject
    ToolSettings settings;

    @Inject
    AwsCredentials aws;

    @GET
    @Path("/tool-settings")
    @Produces(MediaType.APPLICATION_JSON)
    public View get() {
        final var groups = new TreeSet<String>(KNOWN_GROUPS);
        groups.addAll(settings.masked().keySet());
        final var out = new LinkedHashMap<String, GroupView>();
        for (final String group : groups) {
            final Map<String, String> env = settings.envFor(group);
            final var values = new LinkedHashMap<String, String>();
            final var secretsSet = new ArrayList<String>();
            env.forEach((k, v) -> {
                if (RESERVED.contains(k)) {
                    return;
                }
                if (ToolSettings.isSecret(k)) {
                    secretsSet.add(k);
                } else {
                    values.put(k, v);
                }
            });
            final String externalId = "AWS".equals(group) ? settings.ensureExternalId(group) : null;
            out.put(group, new GroupView(values, secretsSet, externalId));
        }
        return new View(out, aws.callerIdentity(settings.envFor("AWS").get("AWS_PROFILE")));
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

    /** The whole settings view: per-group config, plus this host's AWS identity for trust-policy help. */
    public record View(Map<String, GroupView> groups, Map<String, String> oakIdentity) {
    }

    /** Non-secret values, the names of secrets that are set, and (AWS) the OAK-issued external id. */
    public record GroupView(Map<String, String> values, List<String> secretsSet, String externalId) {
    }

    /** A group's variables to merge; a blank value removes a plain var and keeps an existing secret. */
    public record SaveRequest(String group, Map<String, String> env) {
    }
}
