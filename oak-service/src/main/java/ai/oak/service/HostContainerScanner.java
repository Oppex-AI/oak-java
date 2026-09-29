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
import ai.oak.tools.ToolResult;
import ai.oak.tools.cli.CommandRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists the Docker containers running inside one EC2 instance by executing {@code docker ps} on it via
 * SSM Run Command, and reports each container as a {@link DiscoveredResource} fact (linked to its host by
 * {@code hostInstanceId}). Facts only — container name, image and labels are reported verbatim; nothing is
 * mapped to a logical service or picked as "primary".
 *
 * <p>Strictly fail-open: a host with no SSM agent, no Docker, missing permissions, or a slow command is
 * skipped with a warning and yields no containers — it never fails the wider discovery. OAK's role needs
 * {@code ssm:SendCommand} + {@code ssm:GetCommandInvocation}.
 */
@ApplicationScoped
public class HostContainerScanner {

    private static final Logger LOG = LoggerFactory.getLogger(HostContainerScanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One JSON object per line, one line per container — the shape this scanner parses. */
    private static final String DOCKER_PS = "docker ps --format '{{json .}}'";
    private static final long POLL_MS = 2_000;
    private static final long MAX_WAIT_MS = 30_000;
    private static final Set<String> TERMINAL = Set.of("Success", "Failed", "Cancelled", "TimedOut", "Undeliverable",
            "Terminated", "Delivery Timed Out");

    /** Containers running on {@code instanceId}, or an empty list if the host can't be scanned (never throws). */
    public List<DiscoveredResource> scan(final String instanceId, final String region, final Map<String, String> env) {
        try {
            final String commandId = sendDockerPs(instanceId, region, env);
            if (commandId == null) {
                return List.of();
            }
            final String status = pollStatus(commandId, instanceId, region, env);
            if (!"Success".equals(status)) {
                LOG.warn("discovery: docker ps on {} did not succeed ({}) — skipping its containers", instanceId, status);
                return List.of();
            }
            return parseContainers(invocationOutput(commandId, instanceId, region, env), instanceId, region);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private String sendDockerPs(final String instanceId, final String region, final Map<String, String> env) {
        final String parameters = parameters();
        if (parameters == null) {
            return null;
        }
        final List<String> argv = new ArrayList<>(List.of("aws", "ssm", "send-command", "--instance-ids", instanceId,
                "--document-name", "AWS-RunShellScript", "--parameters", parameters));
        withRegion(argv, region);
        argv.add("--query");
        argv.add("Command.CommandId");
        argv.add("--output");
        argv.add("text");
        final ToolResult r = CommandRunner.run(argv, env);
        if (!r.success()) {
            LOG.warn("discovery: could not send docker ps to {} (not SSM-managed?): {}", instanceId,
                    r.stderr().isBlank() ? r.stdout() : r.stderr().strip());
            return null;
        }
        final String id = r.stdout().trim();
        return id.isEmpty() ? null : id;
    }

    private String pollStatus(final String cmd, final String instanceId, final String region, final Map<String, String> env)
            throws InterruptedException {
        final List<String> argv = invocationArgv(cmd, instanceId, region, "Status");
        final long deadline = System.nanoTime() + MAX_WAIT_MS * 1_000_000;
        while (System.nanoTime() < deadline) {
            final ToolResult r = CommandRunner.run(argv, env);
            final String status = r.success() ? r.stdout().trim() : "";
            if (TERMINAL.contains(status)) {
                return status;
            }
            Thread.sleep(POLL_MS);
        }
        return "TimedOut";
    }

    private String invocationOutput(final String cmd, final String instanceId, final String region,
            final Map<String, String> env) {
        final ToolResult r = CommandRunner.run(invocationArgv(cmd, instanceId, region, "StandardOutputContent"), env);
        return r.success() ? r.stdout() : "";
    }

    private static List<String> invocationArgv(final String cmd, final String instanceId, final String region,
            final String query) {
        final List<String> argv = new ArrayList<>(
                List.of("aws", "ssm", "get-command-invocation", "--command-id", cmd, "--instance-id", instanceId));
        withRegion(argv, region);
        argv.add("--query");
        argv.add(query);
        argv.add("--output");
        argv.add("text");
        return argv;
    }

    /** Parse {@code docker ps --format '{{json .}}'} output (one JSON object per line) into container facts. */
    static List<DiscoveredResource> parseContainers(final String output, final String instanceId, final String region) {
        final List<DiscoveredResource> out = new ArrayList<>();
        for (final String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            final JsonNode node = tryParse(line);
            if (node == null || !node.isObject()) {
                continue;
            }
            out.add(toContainer(node, instanceId, region));
        }
        return out;
    }

    private static DiscoveredResource toContainer(final JsonNode node, final String instanceId, final String region) {
        final Map<String, Object> facts = new LinkedHashMap<>();
        put(facts, "name", text(node, "Names"));
        put(facts, "image", text(node, "Image"));
        put(facts, "state", text(node, "State"));
        put(facts, "status", text(node, "Status"));
        put(facts, "ports", text(node, "Ports"));
        put(facts, "hostInstanceId", instanceId);
        put(facts, "region", region);
        final Map<String, String> labels = parseLabels(text(node, "Labels"));
        if (!labels.isEmpty()) {
            facts.put("labels", labels);
        }
        return new DiscoveredResource("DOCKER_CONTAINER", text(node, "ID"), facts);
    }

    /** docker ps reports labels as a flat {@code k=v,k2=v2} string; report them verbatim as a map. */
    private static Map<String, String> parseLabels(final String labels) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (labels == null) {
            return out;
        }
        for (final String pair : labels.split(",")) {
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return out;
    }

    private String parameters() {
        try {
            return MAPPER.writeValueAsString(Map.of("commands", List.of(DOCKER_PS)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static JsonNode tryParse(final String line) {
        try {
            return MAPPER.readTree(line);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static void withRegion(final List<String> argv, final String region) {
        if (region != null && !region.isBlank()) {
            argv.add("--region");
            argv.add(region);
        }
    }

    private static void put(final Map<String, Object> map, final String key, final Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            map.put(key, value);
        }
    }

    private static String text(final JsonNode node, final String field) {
        final String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }
}
