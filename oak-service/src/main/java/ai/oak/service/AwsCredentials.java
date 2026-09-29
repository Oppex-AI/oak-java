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
import ai.oak.tools.cli.CommandRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an AWS role link (role ARN + OAK-issued external id + region) into short-lived credentials the
 * tool commands run with. It assumes the customer's role with {@code aws sts assume-role} using this
 * host's <b>ambient</b> credentials as the source — the instance/task role on an AWS host, or the
 * operator's SSO/default profile locally — which is exactly the identity the role's trust policy allows,
 * gated by the external id.
 *
 * <p>The temporary credentials are cached per role until shortly before they expire, so a burst of steps
 * assumes the role once. They are never persisted — only the role ARN / external id / region are.
 */
@ApplicationScoped
public class AwsCredentials {

    private static final Logger LOG = LoggerFactory.getLogger(AwsCredentials.class);
    private static final long REFRESH_SKEW_SECONDS = 300;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Map<String, String> env, Instant expiry) {
    }

    /**
     * The environment for AWS tool commands: assumed-role credentials plus the region. On failure (no
     * ambient creds, wrong trust, aws CLI absent) it returns just the region, so the tool still runs and
     * surfaces AWS's own error rather than this masking it.
     *
     * <p>{@code profile} is the local AWS CLI profile whose credentials assume the role — the source
     * identity. It matters when the operator's login is a named profile (e.g. an SSO profile) rather than
     * the default: without it the CLI resolves the default profile, which may have no active session.
     */
    public synchronized Map<String, String> assume(final String roleArn, final String externalId, final String region,
            final String profile) {
        final Map<String, String> regionOnly = regionEnv(region);
        if (roleArn == null || roleArn.isBlank()) {
            return regionOnly;
        }
        final String key = roleArn + "|" + externalId + "|" + region + "|" + profile;
        final Cached hit = cache.get(key);
        if (hit != null && hit.expiry().isAfter(Instant.now().plusSeconds(REFRESH_SKEW_SECONDS))) {
            return hit.env();
        }
        final ToolResult result = runAssumeRole(roleArn, externalId, region, profile);
        if (!result.success()) {
            LOG.warn("assume-role for {} failed (exit {}): {}", roleArn, result.exitCode(),
                    result.stderr().isBlank() ? result.stdout() : result.stderr());
            return regionOnly;
        }
        final Cached fresh = parse(result.stdout(), regionOnly);
        if (fresh == null) {
            return regionOnly;
        }
        cache.put(key, fresh);
        LOG.info("Assumed role {} (credentials valid until {})", roleArn, fresh.expiry());
        return fresh.env();
    }

    /** This host's own AWS identity ({account, arn}), for the trust-policy setup instructions. Best-effort. */
    public Map<String, String> callerIdentity(final String profile) {
        final ToolResult r = CommandRunner.run(List.of("aws", "sts", "get-caller-identity", "--output", "json"),
                sourceEnv(profile));
        if (!r.success()) {
            return Map.of();
        }
        try {
            final JsonNode node = mapper.readTree(r.stdout());
            return Map.of("account", node.path("Account").asText(""), "arn", node.path("Arn").asText(""));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private ToolResult runAssumeRole(final String roleArn, final String externalId, final String region, final String profile) {
        final List<String> argv = new ArrayList<>(
                List.of("aws", "sts", "assume-role", "--role-arn", roleArn, "--role-session-name", "oak-service"));
        if (externalId != null && !externalId.isBlank()) {
            argv.add("--external-id");
            argv.add(externalId);
        }
        if (region != null && !region.isBlank()) {
            argv.add("--region");
            argv.add(region);
        }
        argv.add("--output");
        argv.add("json");
        return CommandRunner.run(argv, sourceEnv(profile));
    }

    /** Env for a source (pre-assume) AWS call: the CLI profile to authenticate as, when one is configured. */
    private static Map<String, String> sourceEnv(final String profile) {
        final Map<String, String> env = new LinkedHashMap<>();
        if (profile != null && !profile.isBlank()) {
            env.put("AWS_PROFILE", profile);
        }
        return env;
    }

    private Cached parse(final String stdout, final Map<String, String> regionOnly) {
        try {
            final JsonNode creds = mapper.readTree(stdout).get("Credentials");
            final Map<String, String> env = new LinkedHashMap<>(regionOnly);
            env.put("AWS_ACCESS_KEY_ID", creds.get("AccessKeyId").asText());
            env.put("AWS_SECRET_ACCESS_KEY", creds.get("SecretAccessKey").asText());
            env.put("AWS_SESSION_TOKEN", creds.get("SessionToken").asText());
            return new Cached(env, Instant.parse(creds.get("Expiration").asText()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not parse assume-role output: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, String> regionEnv(final String region) {
        final Map<String, String> env = new LinkedHashMap<>();
        if (region != null && !region.isBlank()) {
            env.put("AWS_REGION", region);
            env.put("AWS_DEFAULT_REGION", region);
        }
        return env;
    }
}
