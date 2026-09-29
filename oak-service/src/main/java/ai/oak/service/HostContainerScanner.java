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

    /** Marker separating the docker-ps section from the ps -ef section in one command's output. */
    private static final String PS_MARKER = "===OAK_PS===";
    /**
     * One SSM command: list containers as per-line JSON (primary), then a marker, then {@code ps -ef}
     * (secondary — bare processes, used only when the host runs no containers). {@code 2>/dev/null} keeps a
     * missing docker binary from polluting the output; the ps section still follows.
     */
    private static final String SCAN_SCRIPT = "docker ps --format '{{json .}}' 2>/dev/null; echo '" + PS_MARKER +
            "'; ps -ef 2>/dev/null";
    private static final long POLL_MS = 2_000;
    private static final long MAX_WAIT_MS = 30_000;
    /** A safety cap on processes reported per host, so a busy non-docker host can't bloat the snapshot. */
    private static final int MAX_PROCESSES = 500;
    private static final Set<String> TERMINAL = Set.of("Success", "Failed", "Cancelled", "TimedOut", "Undeliverable",
            "Terminated", "Delivery Timed Out");

    /**
     * The services on {@code instanceId}: its Docker containers and, when {@code includeProcesses} is set,
     * its host processes too (from {@code ps -ef}) — so apps running directly on the host are captured
     * alongside containerised ones. Empty if the host can't be scanned (never throws).
     */
    public List<DiscoveredResource> scan(final String instanceId, final String region, final Map<String, String> env,
            final boolean includeProcesses) {
        try {
            final String commandId = sendScan(instanceId, region, env);
            if (commandId == null) {
                return List.of();
            }
            final String status = pollStatus(commandId, instanceId, region, env);
            if (!"Success".equals(status)) {
                LOG.warn("discovery: host scan on {} did not succeed ({}) — skipping it", instanceId, status);
                return List.of();
            }
            return parseScan(invocationOutput(commandId, instanceId, region, env), instanceId, region, includeProcesses);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** Split the combined output into containers plus, when asked, the host's bare processes. */
    static List<DiscoveredResource> parseScan(final String output, final String instanceId, final String region,
            final boolean includeProcesses) {
        final int marker = output.indexOf(PS_MARKER);
        final String dockerPart = marker >= 0 ? output.substring(0, marker) : output;
        final List<DiscoveredResource> resources = new ArrayList<>(parseContainers(dockerPart, instanceId, region));
        if (includeProcesses) {
            final String psPart = marker >= 0 ? output.substring(marker + PS_MARKER.length()) : "";
            resources.addAll(parseProcesses(psPart, instanceId, region));
        }
        return resources;
    }

    private String sendScan(final String instanceId, final String region, final Map<String, String> env) {
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
            LOG.warn("discovery: could not scan {} via SSM (not SSM-managed?): {}", instanceId,
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

    /**
     * Parse {@code ps -ef} output into HOST_PROCESS facts — the userspace processes on the host, so an app
     * running directly on the box (not in Docker) is captured. Each is reported verbatim; the id is {@code
     * <instanceId>:<pid>} so pids stay unique across hosts. Columns: UID PID PPID C STIME TTY TIME CMD — CMD
     * (the full command) is kept whole. Kernel threads (command in {@code [brackets]}) are skipped: they are
     * kernel machinery, never a service a runbook acts on — dropping them is denoising, not app selection.
     */
    static List<DiscoveredResource> parseProcesses(final String psOutput, final String instanceId, final String region) {
        final List<DiscoveredResource> out = new ArrayList<>();
        for (final String line : psOutput.split("\n")) {
            final String trimmed = line.strip();
            if (trimmed.isBlank() || trimmed.startsWith("UID ") || trimmed.startsWith("UID\t")) {
                continue;
            }
            final String[] parts = trimmed.split("\\s+", 8);
            if (parts.length < 8 || parts[7].startsWith("[")) {
                continue;
            }
            final Map<String, Object> facts = new LinkedHashMap<>();
            put(facts, "pid", parts[1]);
            put(facts, "user", parts[0]);
            put(facts, "ppid", parts[2]);
            put(facts, "command", parts[7]);
            put(facts, "hostInstanceId", instanceId);
            put(facts, "region", region);
            out.add(new DiscoveredResource("HOST_PROCESS", instanceId + ":" + parts[1], facts));
            if (out.size() >= MAX_PROCESSES) {
                LOG.warn("discovery: {} reported over {} processes — truncating", instanceId, MAX_PROCESSES);
                break;
            }
        }
        return out;
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
            return MAPPER.writeValueAsString(Map.of("commands", List.of(SCAN_SCRIPT)));
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
