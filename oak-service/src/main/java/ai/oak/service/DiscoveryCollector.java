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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enumerates the infrastructure this service can see and returns it as a {@link DiscoverySnapshot} of
 * plain facts. It is the "eyes" half of the service: it reports what exists and never interprets it —
 * tags/labels are passed through verbatim, no resource is mapped to a logical service, nothing is filtered
 * to "relevant". All of that is the platform's job.
 *
 * <p>It reads through the same AWS CLI + assumed-role credentials the AWS tools use (via
 * {@link ToolExecutor}), forcing {@code --output json} so parsing is deterministic. It reports EC2
 * instances, RDS instances, and — via {@link HostContainerScanner} over SSM — the Docker containers
 * running inside each reachable instance. Resources are sorted by type then id so an unchanged environment
 * yields an identical array between cycles. Best-effort: a region, service or host that errors is logged
 * and skipped, never fatal.
 */
@ApplicationScoped
public class DiscoveryCollector {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryCollector.class);
    private static final String EC2_INSTANCE = "EC2_INSTANCE";
    private static final String RDS_INSTANCE = "RDS_INSTANCE";
    private static final String DOCKER_CONTAINER = "DOCKER_CONTAINER";
    /** How many hosts to scan over SSM at once — bounded so discovery never floods SSM. */
    private static final int MAX_SCAN_CONCURRENCY = 6;

    @Inject
    OakConfig config;

    @Inject
    ToolExecutor executor;

    @Inject
    ToolSettings toolSettings;

    @Inject
    HostContainerScanner containers;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Collect a full snapshot across the configured regions. AWS env is resolved once (role assumed once). */
    public DiscoverySnapshot collect() {
        final Map<String, String> env = executor.envForCapability("AWS_EC2_DESCRIBE_INSTANCES");
        final List<String> types = config.discovery().resourceTypes();
        final List<DiscoveredResource> resources = new ArrayList<>();
        for (final String region : regions()) {
            final List<DiscoveredResource> ec2 = types.contains(EC2_INSTANCE) ? collectEc2(region, env) : List.of();
            resources.addAll(ec2);
            if (types.contains(RDS_INSTANCE)) {
                resources.addAll(collectRds(region, env));
            }
            if (types.contains(DOCKER_CONTAINER)) {
                resources.addAll(collectContainers(region, env, ec2));
            }
        }
        resources.sort(Comparator.comparing(DiscoveredResource::getType).thenComparing(DiscoveredResource::getId));
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

    private List<DiscoveredResource> collectEc2(final String region, final Map<String, String> env) {
        final JsonNode root = runJson(argv("ec2", "describe-instances", region), env, region, "EC2 instances");
        if (root == null) {
            return new ArrayList<>();
        }
        return parseInstances(root, region, collectAsgMembership(region, env));
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

    private static DiscoveredResource toEc2Resource(final JsonNode node, final String region, final Map<String, String> asg) {
        final String id = text(node, "InstanceId");
        final Map<String, Object> facts = new LinkedHashMap<>();
        put(facts, "region", region);
        put(facts, "state", node.path("State").path("Name").asText(null));
        put(facts, "instanceType", text(node, "InstanceType"));
        put(facts, "privateIp", text(node, "PrivateIpAddress"));
        put(facts, "publicIp", text(node, "PublicIpAddress"));
        put(facts, "availabilityZone", node.path("Placement").path("AvailabilityZone").asText(null));
        put(facts, "imageId", text(node, "ImageId"));
        put(facts, "vpcId", text(node, "VpcId"));
        put(facts, "subnetId", text(node, "SubnetId"));
        put(facts, "launchTime", text(node, "LaunchTime"));
        putMap(facts, "tags", tagsOf(node, "Tags"));
        put(facts, "asg", asg.get(id));
        return new DiscoveredResource(EC2_INSTANCE, id, facts);
    }

    private List<DiscoveredResource> collectRds(final String region, final Map<String, String> env) {
        final JsonNode root = runJson(argv("rds", "describe-db-instances", region), env, region, "RDS instances");
        return root == null ? new ArrayList<>() : parseDbInstances(root, region);
    }

    /** Map a {@code describe-db-instances} response to resources — the pure, testable core of RDS collection. */
    static List<DiscoveredResource> parseDbInstances(final JsonNode root, final String region) {
        final List<DiscoveredResource> out = new ArrayList<>();
        for (final JsonNode db : root.path("DBInstances")) {
            out.add(toDbResource(db, region));
        }
        return out;
    }

    private static DiscoveredResource toDbResource(final JsonNode node, final String region) {
        final Map<String, Object> facts = new LinkedHashMap<>();
        put(facts, "region", region);
        put(facts, "state", text(node, "DBInstanceStatus"));
        put(facts, "engine", text(node, "Engine"));
        put(facts, "engineVersion", text(node, "EngineVersion"));
        put(facts, "dbInstanceClass", text(node, "DBInstanceClass"));
        put(facts, "endpoint", node.path("Endpoint").path("Address").asText(null));
        final JsonNode port = node.path("Endpoint").path("Port");
        if (port.isInt()) {
            facts.put("port", port.asInt());
        }
        put(facts, "availabilityZone", text(node, "AvailabilityZone"));
        facts.put("multiAz", node.path("MultiAZ").asBoolean(false));
        putMap(facts, "tags", tagsOf(node, "TagList"));
        return new DiscoveredResource(RDS_INSTANCE, text(node, "DBInstanceIdentifier"), facts);
    }

    /**
     * Containers (and, if enabled, host processes) inside each running EC2 instance, via SSM. Hosts are
     * scanned concurrently with a small bounded pool so one slow or unreachable host neither blocks nor
     * slows the others; each scan is fail-open (a failure yields no resources for that host, never throws).
     */
    private List<DiscoveredResource> collectContainers(final String region, final Map<String, String> env,
            final List<DiscoveredResource> ec2) {
        final boolean withProcesses = config.discovery().hostProcesses();
        final List<String> hosts = ec2.stream()
                .filter(h -> EC2_INSTANCE.equals(h.getType()) && "running".equals(h.getFacts().get("state")))
                .map(DiscoveredResource::getId).toList();
        if (hosts.isEmpty()) {
            return List.of();
        }
        final ExecutorService pool = Executors.newFixedThreadPool(Math.min(hosts.size(), MAX_SCAN_CONCURRENCY), r -> {
            final Thread t = new Thread(r, "oak-discovery-scan");
            t.setDaemon(true);
            return t;
        });
        try {
            return gather(hosts.stream().map(id -> pool.submit(() -> containers.scan(id, region, env, withProcesses))).toList());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Collect every host scan's results; a task that failed contributes nothing (never fails the batch). */
    private static List<DiscoveredResource> gather(final List<Future<List<DiscoveredResource>>> futures) {
        final List<DiscoveredResource> out = new ArrayList<>();
        for (final Future<List<DiscoveredResource>> future : futures) {
            try {
                out.addAll(future.get());
            } catch (ExecutionException e) {
                LOG.warn("discovery: a host scan failed: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return out;
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

    private static Map<String, String> tagsOf(final JsonNode node, final String field) {
        final Map<String, String> tags = new LinkedHashMap<>();
        for (final JsonNode tag : node.path(field)) {
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

    private static void put(final Map<String, Object> facts, final String key, final Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            facts.put(key, value);
        }
    }

    private static void putMap(final Map<String, Object> facts, final String key, final Map<String, String> value) {
        if (value != null && !value.isEmpty()) {
            facts.put(key, value);
        }
    }

    private static String text(final JsonNode node, final String field) {
        final String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }
}
