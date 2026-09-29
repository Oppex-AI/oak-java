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
package ai.oak.tools.aws.ssm;

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolResult;
import ai.oak.tools.cli.Cli;
import ai.oak.tools.cli.CommandRunner;
import ai.oak.tools.cli.Json;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run shell commands <em>inside</em> EC2 instances via AWS Systems Manager (SSM) Run Command — the way
 * OAK reaches a process on a host it cannot log into (e.g. restarting a docker container that runs on an
 * AWS instance). Unlike the render-only AWS tools, this is a multi-step tool: it dispatches the command
 * with {@code ssm send-command}, then polls {@code ssm list-command-invocations} until every targeted
 * instance finishes, and reports each instance's status and output.
 *
 * <p>It executes an arbitrary shell script on the target, so it declares {@link ToolPermission#DESTRUCTIVE}.
 *
 * <p>Targeting today is by explicit {@code instanceIds}. Tag-based targeting is already wired: pass a
 * {@code targets} input (an SSM {@code --targets} list, JSON-serialised) and it is used instead of
 * instance ids — so extending the advertised contract to tags later is a one-line change to
 * {@link #inputKeys()}, no change to the execution path.
 */
public final class SsmRunCommandTool implements Tool {

    private static final String CAPABILITY = "AWS_SSM_RUN_COMMAND";
    private static final String DEFAULT_DOCUMENT = "AWS-RunShellCommand";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);
    private static final Duration MAX_WAIT = Duration.ofMinutes(5);

    /** Invocation statuses that mean the command is finished on that instance (success or not). */
    private static final Set<String> TERMINAL = Set.of("Success", "Failed", "Cancelled", "TimedOut", "Undeliverable",
            "Terminated", "Delivery Timed Out");

    @Override
    public String capability() {
        return CAPABILITY;
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.DESTRUCTIVE;
    }

    @Override
    public String description() {
        return "Run shell commands inside EC2 instances via SSM Run Command, then report each instance's output.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("instanceIds", "commands", "region");
    }

    @Override
    public String render(final Map<String, Object> input) {
        return sendCommand(input).build();
    }

    @Override
    public ToolResult execute(final Map<String, Object> input) {
        return execute(input, Map.of());
    }

    @Override
    public ToolResult execute(final Map<String, Object> input, final Map<String, String> env) {
        final List<String> dispatch = sendCommand(input).opt("--query", "Command.CommandId").opt("--output", "text").argv();
        final ToolResult sent = CommandRunner.run(dispatch, env);
        if (!sent.success()) {
            return sent;
        }
        final String commandId = sent.stdout().trim();
        if (commandId.isEmpty()) {
            return new ToolResult(-1, sent.stdout(), "SSM send-command returned no CommandId", false);
        }
        return pollUntilDone(commandId, input.get("region"), env);
    }

    /** The {@code ssm send-command} for this step. Also the render preview (without the execution-only flags). */
    private Cli sendCommand(final Map<String, Object> input) {
        final Cli cli = Cli.aws("ssm", "send-command").opt("--document-name", documentName(input))
                .opt("--comment", input.get("comment")).opt("--parameters", Map.of("commands", commands(input)))
                .opt("--region", input.get("region"));
        return applyTargets(cli, input);
    }

    private static String documentName(final Map<String, Object> input) {
        final Object doc = input.get("documentName");
        return doc == null || doc.toString().isBlank() ? DEFAULT_DOCUMENT : doc.toString();
    }

    /** The shell commands to run, always non-empty so the preview reads as a template. */
    private static List<Object> commands(final Map<String, Object> input) {
        final Object value = input.get("commands");
        final List<Object> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(out::add);
        } else if (value != null && !value.toString().isBlank()) {
            out.add(value);
        }
        if (out.isEmpty()) {
            out.add("<commands>");
        }
        return out;
    }

    /** Tag-based {@code --targets} wins when supplied; otherwise target explicit {@code --instance-ids}. */
    private static Cli applyTargets(final Cli cli, final Map<String, Object> input) {
        final Object targets = input.get("targets");
        if (targets != null && !targets.toString().isBlank()) {
            return cli.opt("--targets", targets);
        }
        return cli.required("--instance-ids", input.get("instanceIds"), "<instanceIds>");
    }

    private ToolResult pollUntilDone(final String commandId, final Object region, final Map<String, String> env) {
        final List<String> list = Cli.aws("ssm", "list-command-invocations").opt("--command-id", commandId)
                .flag("--details", Boolean.TRUE).opt("--region", region).opt("--output", "json").argv();
        final long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            final ToolResult listed = CommandRunner.run(list, env);
            if (!listed.success()) {
                return listed;
            }
            final List<Map<?, ?>> invocations = invocations(listed.stdout());
            if (!invocations.isEmpty() && invocations.stream().allMatch(SsmRunCommandTool::isTerminal)) {
                return aggregate(invocations);
            }
            if (!sleepPoll()) {
                return new ToolResult(-1, "", "interrupted while awaiting SSM command " + commandId, false);
            }
        }
        return new ToolResult(-1, "", "SSM command " + commandId + " did not complete within " + MAX_WAIT, true);
    }

    /** Parse the {@code CommandInvocations} array from a list-command-invocations JSON response. */
    private static List<Map<?, ?>> invocations(final String json) {
        final List<Map<?, ?>> out = new ArrayList<>();
        final Object root;
        try {
            root = Json.read(json);
        } catch (IllegalArgumentException e) {
            return out; // partial/blank output — treat as "not ready yet" and keep polling
        }
        if (root instanceof Map<?, ?> map && map.get("CommandInvocations") instanceof List<?> list) {
            for (final Object element : list) {
                if (element instanceof Map<?, ?> inv) {
                    out.add(inv);
                }
            }
        }
        return out;
    }

    private static boolean isTerminal(final Map<?, ?> invocation) {
        return TERMINAL.contains(String.valueOf(invocation.get("Status")));
    }

    /** Combine every instance's status and output into one result; exit 0 only when all succeeded. */
    private static ToolResult aggregate(final List<Map<?, ?>> invocations) {
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        boolean allSuccess = true;
        for (final Map<?, ?> inv : invocations) {
            final String instanceId = String.valueOf(inv.get("InstanceId"));
            final String status = String.valueOf(inv.get("Status"));
            out.append('[').append(instanceId).append(' ').append(status).append("]\n").append(output(inv)).append('\n');
            if (!"Success".equals(status)) {
                allSuccess = false;
                err.append(instanceId).append(": ").append(status).append('\n');
            }
        }
        return new ToolResult(allSuccess ? 0 : 1, out.toString().strip(), err.toString().strip(), false);
    }

    /** The first command plugin's output for an invocation (the combined stdout/stderr SSM captured). */
    private static String output(final Map<?, ?> invocation) {
        if (invocation.get("CommandPlugins") instanceof List<?> plugins && !plugins.isEmpty() &&
                plugins.get(0) instanceof Map<?, ?> plugin && plugin.get("Output") != null) {
            return plugin.get("Output").toString();
        }
        return "";
    }

    /** Sleep one poll interval; returns false if the thread was interrupted (caller should stop). */
    private static boolean sleepPoll() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
