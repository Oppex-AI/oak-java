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

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolResult;
import ai.oak.tools.cli.Cli;
import ai.oak.tools.cli.CommandRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A container-targeted docker operation ({@code restart}/{@code start}/{@code stop}/{@code logs}/
 * {@code inspect}) that is aware of <em>where</em> the container runs. When the step's input carries an
 * {@code instanceId} (which discovery reports for every container as {@code hostInstanceId}), it runs
 * {@code docker <op> <container>} on that host via SSM; without one it runs against OAK's local docker.
 *
 * <p>This is the customer layer overriding the generic {@link ai.oak.tools.docker} tool of the same
 * capability id — so a runbook step that resolved a container on an AWS host actually acts on that host,
 * instead of looking for the container on the machine OAK happens to run on.
 */
public final class HostAwareDockerTool implements Tool {

    private final String capability;
    private final String action;
    private final ToolPermission permission;
    private final String description;
    private final boolean hasTail;
    private final SsmRunner ssm;
    private final Supplier<Map<String, String>> awsEnv;

    public HostAwareDockerTool(final String capability, final String action, final ToolPermission permission,
            final String description, final boolean hasTail, final SsmRunner ssm, final Supplier<Map<String, String>> awsEnv) {
        this.capability = capability;
        this.action = action;
        this.permission = permission;
        this.description = description;
        this.hasTail = hasTail;
        this.ssm = ssm;
        this.awsEnv = awsEnv;
    }

    @Override
    public String capability() {
        return capability;
    }

    @Override
    public ToolPermission permission() {
        return permission;
    }

    @Override
    public String description() {
        return description + " Runs on the container's host via SSM when instanceId is given, else locally.";
    }

    @Override
    public List<String> inputKeys() {
        final List<String> keys = new ArrayList<>(List.of("container", "instanceId"));
        if (hasTail) {
            keys.add("tail");
        }
        return keys;
    }

    @Override
    public String render(final Map<String, Object> input) {
        final String docker = localCli(input).build();
        final Object instanceId = input.get("instanceId");
        return present(instanceId) ? docker + " (on " + instanceId + " via SSM)" : docker;
    }

    @Override
    public ToolResult execute(final Map<String, Object> input) {
        return execute(input, Map.of());
    }

    @Override
    public ToolResult execute(final Map<String, Object> input, final Map<String, String> env) {
        final Object instanceId = input.get("instanceId");
        if (present(instanceId)) {
            return ssm.runShell(instanceId.toString(), str(input.get("region")), hostCommand(input), awsEnv.get());
        }
        return CommandRunner.run(localCli(input).argv(), env);
    }

    /** The local docker command (also the render preview). */
    private Cli localCli(final Map<String, Object> input) {
        final Cli cli = Cli.docker(action);
        if (hasTail) {
            cli.opt("--tail", input.get("tail"));
        }
        return cli.positional(input.get("container"), "<container>");
    }

    /** The docker command as a shell string to run on the host (container single-quoted). */
    private String hostCommand(final Map<String, Object> input) {
        final StringBuilder sb = new StringBuilder("docker ").append(action);
        if (hasTail && present(input.get("tail"))) {
            sb.append(" --tail ").append(str(input.get("tail")));
        }
        return sb.append(' ').append(shellQuote(str(input.get("container")))).toString();
    }

    private static boolean present(final Object value) {
        return value != null && !value.toString().isBlank();
    }

    private static String str(final Object value) {
        return value == null ? "" : value.toString();
    }

    /** Single-quote a value for a POSIX shell, escaping embedded single quotes. */
    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
