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
import ai.oak.service.config.OakConfig;
import ai.oak.tools.ToolResult;
import ai.oak.tools.cli.CommandRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enumerates the infrastructure this service can see and returns it as a {@link DiscoverySnapshot} of
 * plain facts. It is the "eyes" half of the service: it reports what exists and never interprets it —
 * tags are passed through verbatim, no resource is mapped to a logical service, nothing is filtered to
 * "relevant". All of that is the platform's job.
 *
 * <p>It reads through the same AWS CLI + assumed-role credentials the AWS tools use (via
 * {@link ToolExecutor}), forcing {@code --output json} so parsing is deterministic. Resources are sorted
 * by type then id so an unchanged environment yields an identical array between cycles. Best-effort: a
 * region or service that errors is logged and skipped, never fatal. New resource types (RDS, ElastiCache,
 * MSK, ECS) slot in as more {@code collect*} helpers.
 */
@ApplicationScoped
public class DiscoveryCollector {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryCollector.class);
    private static final String EC2_INSTANCE = "EC2_INSTANCE";

    @Inject
    OakConfig config;

    @Inject
    ToolExecutor executor;

    @Inject
    ToolSettings toolSettings;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Collect a full snapshot across the configured regions. AWS env is resolved once (role assumed once). */
    public DiscoverySnapshot collect() {
        final Map<String, String> env = executor.envForCapability("AWS_EC2_DESCRIBE_INSTANCES");
        final List<DiscoveredResource> resources = new ArrayList<>();
        final List<String> types = config.discovery().resourceTypes();
        for (final String region : regions()) {
            if (types.contains(EC2_INSTANCE)) {
                collectEc2(region, env, resources);
            }
        }
        resources.sort(Comparator.comparing(DiscoveredResource::type).thenComparing(DiscoveredResource::id));
        return new DiscoverySnapshot(Instant.now().toString(), resources);
    }

    /** The regions to scan: the configured list, else the AWS setting's region, else one unspecified scan. */
    private List<String> regions() {
        final List<String> configured = config.discovery().regions().orElse(List.of());
        if (!configured.isEmpty()) {
            return configured;
        }
        final String region = toolSettings.envFor("AWS").get("AWS_REGION");
        return region == null || region.isBlank() ? List.of("") : List.of(region);
    }

    private void collectEc2(final String region, final Map<String, String> env, final List<DiscoveredResource> out) {
        final JsonNode root = runJson(argv("ec2", "describe-instances", region), env, region, "EC2 instances");
        if (root == null) {
            return;
        }
        final Map<String, String> asgByInstance = collectAsgMembership(region, env);
        out.addAll(parseInstances(root, region, asgByInstance));
    }

    /** Map a {@code describe-instances} response to resources — the pure, testable core of EC2 collection. */
    static List<DiscoveredResource> parseInstances(final JsonNode root, final String region, final Map<String, String> asg) {
        final List<DiscoveredResource> out = new ArrayList<>();
        for (final JsonNode reservation : root.path("Reservations")) {
            for (final JsonNode instance : reservation.path("Instances")) {
                out.add(toEc2Resource(instance, region, asg));
            }
        }
        return out;
    }

    private static DiscoveredResource toEc2Resource(final JsonNode instance, final String region, final Map<String, String> asg) {
        final String id = text(instance, "InstanceId");
        final String state = instance.path("State").path("Name").asText("");
        return new DiscoveredResource(EC2_INSTANCE, id, blankToNull(region), blankToNull(state),
                text(instance, "PrivateIpAddress"), tagsOf(instance), asg.get(id));
    }

    /** instanceId → ASG name, so an instance can report its group. Empty if none / the call fails. */
    private Map<String, String> collectAsgMembership(final String region, final Map<String, String> env) {
        final JsonNode root = runJson(argv("autoscaling", "describe-auto-scaling-groups", region), env, region, "ASGs");
        return root == null ? new LinkedHashMap<>() : parseAsgMembership(root);
    }

    /** Map a {@code describe-auto-scaling-groups} response to instanceId → ASG name — the testable core. */
    static Map<String, String> parseAsgMembership(final JsonNode root) {
        final Map<String, String> byInstance = new LinkedHashMap<>();
        for (final JsonNode group : root.path("AutoScalingGroups")) {
            final String name = group.path("AutoScalingGroupName").asText("");
            for (final JsonNode member : group.path("Instances")) {
                byInstance.put(member.path("InstanceId").asText(""), name);
            }
        }
        return byInstance;
    }

    private static Map<String, String> tagsOf(final JsonNode instance) {
        final Map<String, String> tags = new LinkedHashMap<>();
        for (final JsonNode tag : instance.path("Tags")) {
            tags.put(tag.path("Key").asText(""), tag.path("Value").asText(""));
        }
        return tags;
    }

    private static List<String> argv(final String service, final String action, final String region) {
        final List<String> argv = new ArrayList<>(List.of("aws", service, action, "--output", "json"));
        if (region != null && !region.isBlank()) {
            argv.add("--region");
            argv.add(region);
        }
        return argv;
    }

    /** Run an aws command and parse its JSON stdout, or null (logged) if it failed or was unparseable. */
    private JsonNode runJson(final List<String> argv, final Map<String, String> env, final String region, final String what) {
        final ToolResult result = CommandRunner.run(argv, env);
        if (!result.success()) {
            LOG.warn("discovery: could not list {} in region [{}]: {}", what, region,
                    result.stderr().isBlank() ? result.stdout() : result.stderr().strip());
            return null;
        }
        try {
            return mapper.readTree(result.stdout());
        } catch (java.io.IOException e) {
            LOG.warn("discovery: unparseable {} response in region [{}]: {}", what, region, e.getMessage());
            return null;
        }
    }

    private static String text(final JsonNode node, final String field) {
        return blankToNull(node.path(field).asText(""));
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
