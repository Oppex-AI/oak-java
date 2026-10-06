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

import ai.oak.tools.ToolResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a tool with the right per-provider environment resolved for it — the one place that maps a
 * capability to the credentials/settings its command needs. Both the platform step loop
 * ({@link ExecutorAgent}/PlatformConnection) and the UI's read-only Run panel go through here, so a tool
 * executes identically however it was triggered.
 */
@ApplicationScoped
public class ToolExecutor {

    @Inject
    ToolCatalog catalog;

    @Inject
    ToolSettings toolSettings;

    @Inject
    AwsCredentials awsCredentials;

    @Inject
    RegionProvider regions;

    /**
     * The environment a tool of this capability runs with. AWS assumes the linked role (temporary creds
     * as env, in OAK's single region); every other group gets its configured settings as-is.
     */
    public Map<String, String> envForCapability(final String capability) {
        final String group = ToolCatalog.groupOf(capability);
        final Map<String, String> raw = toolSettings.envFor(group);
        if ("AWS".equals(group)) {
            return awsCredentials.assume(raw.get("AWS_ROLE_ARN"), raw.get("OAK_EXTERNAL_ID"), regions.region(),
                    raw.get("AWS_PROFILE"));
        }
        return raw;
    }

    /** Resolve the capability's environment and execute it; empty only if the capability is unknown. */
    public Optional<ToolResult> run(final String capability, final Map<String, Object> input) {
        final Map<String, Object> args = withRegion(input, regions.region());
        return catalog.registry().execute(capability, args, envForCapability(capability));
    }

    /**
     * Region is OAK's connection context, not a planner input (OPD-666). Inject OAK's single region into
     * the step input — overriding anything that arrived — so a tool that takes {@code region} always acts
     * in OAK's region. A null/blank region leaves the input untouched.
     */
    public static Map<String, Object> withRegion(final Map<String, Object> input, final String region) {
        if (region == null || region.isBlank()) {
            return input == null ? Map.of() : input;
        }
        final Map<String, Object> args = new LinkedHashMap<>(input == null ? Map.of() : input);
        args.put("region", region);
        return args;
    }
}
