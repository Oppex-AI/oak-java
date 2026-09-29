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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a shell command <em>inside</em> an EC2 instance via SSM Run Command and returns it as a
 * {@link ToolResult} — the mechanism a host-targeted tool uses to act on a box OAK cannot log into (e.g.
 * restarting a container that runs on an AWS host). It dispatches with {@code ssm send-command}, polls
 * {@code ssm get-command-invocation} until the invocation finishes, and maps the command's own
 * {@code ResponseCode} to the exit code with its captured stdout/stderr.
 *
 * <p>The AWS CLI calls need the assumed-role credentials in {@code awsEnv} (not the docker env), so the
 * caller passes those explicitly.
 */
@ApplicationScoped
public class SsmRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_MS = 2_000;
    private static final long MAX_WAIT_MS = 120_000;
    private static final Set<String> TERMINAL = Set.of("Success", "Failed", "Cancelled", "TimedOut", "Undeliverable",
            "Terminated", "Delivery Timed Out");

    /** Run {@code command} on {@code instanceId} via SSM; the ToolResult carries the command's own exit/output. */
    public ToolResult runShell(final String instanceId, final String region, final String command,
            final Map<String, String> awsEnv) {
        final String parameters = parameters(command);
        if (parameters == null) {
            return new ToolResult(-1, "", "could not build SSM parameters", false);
        }
        final ToolResult sent = CommandRunner.run(sendArgv(instanceId, region, parameters), awsEnv);
        if (!sent.success()) {
            return new ToolResult(-1, sent.stdout(), sent.stderr().isBlank() ? sent.stdout() : sent.stderr(), false);
        }
        final String commandId = sent.stdout().trim();
        if (commandId.isEmpty()) {
            return new ToolResult(-1, "", "SSM send-command returned no CommandId", false);
        }
        return await(commandId, instanceId, region, awsEnv);
    }

    private ToolResult await(final String cmd, final String instanceId, final String region, final Map<String, String> awsEnv) {
        final long deadline = System.nanoTime() + MAX_WAIT_MS * 1_000_000;
        try {
            while (System.nanoTime() < deadline) {
                final JsonNode inv = invocation(cmd, instanceId, region, awsEnv);
                if (inv != null && TERMINAL.contains(inv.path("Status").asText(""))) {
                    return toResult(inv);
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult(-1, "", "interrupted while awaiting SSM command " + cmd, false);
        }
        return new ToolResult(-1, "", "SSM command " + cmd + " did not complete within " + MAX_WAIT_MS + "ms", true);
    }

    private static ToolResult toResult(final JsonNode inv) {
        final int exit = inv.hasNonNull("ResponseCode") ? inv.get("ResponseCode").asInt(-1) : -1;
        final String stdout = inv.path("StandardOutputContent").asText("");
        String stderr = inv.path("StandardErrorContent").asText("");
        final String status = inv.path("Status").asText("");
        if (!"Success".equals(status) && stderr.isBlank()) {
            stderr = "SSM invocation status: " + status;
        }
        return new ToolResult(exit, stdout, stderr, false);
    }

    private JsonNode invocation(final String cmd, final String instanceId, final String region,
            final Map<String, String> awsEnv) {
        final List<String> argv = new ArrayList<>(
                List.of("aws", "ssm", "get-command-invocation", "--command-id", cmd, "--instance-id", instanceId));
        withRegion(argv, region);
        argv.add("--output");
        argv.add("json");
        final ToolResult r = CommandRunner.run(argv, awsEnv);
        if (!r.success()) {
            return null; // invocation not registered yet, or a transient read error — keep polling
        }
        try {
            return MAPPER.readTree(r.stdout());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static List<String> sendArgv(final String instanceId, final String region, final String parameters) {
        final List<String> argv = new ArrayList<>(List.of("aws", "ssm", "send-command", "--instance-ids", instanceId,
                "--document-name", "AWS-RunShellScript", "--parameters", parameters));
        withRegion(argv, region);
        argv.add("--query");
        argv.add("Command.CommandId");
        argv.add("--output");
        argv.add("text");
        return argv;
    }

    private static String parameters(final String command) {
        try {
            return MAPPER.writeValueAsString(Map.of("commands", List.of(command)));
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
}
