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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * DOCKER_LOGS (READ) — fetch a container's logs as structured facts. Runs {@code docker logs} on the
 * container's host via SSM when an {@code instanceId} is given, else locally; applies an optional
 * {@code grep} line filter, and returns the logs as ONE newline-joined string with a line count and a
 * truncation flag. 0 lines is a definite answer (success, {@code lineCount: 0}).
 */
public final class DockerLogsTool implements Tool {

    private static final int MAX_CHARS = 100_000;

    private final SsmRunner ssm;
    private final Supplier<Map<String, String>> awsEnv;

    public DockerLogsTool(final SsmRunner ssm, final Supplier<Map<String, String>> awsEnv) {
        this.ssm = ssm;
        this.awsEnv = awsEnv;
    }

    @Override
    public String capability() {
        return "DOCKER_LOGS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Fetch a container's logs (on its host via SSM when instanceId is given), filtered and line-counted.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("container", "instanceId", "since", "until", "tail", "grep");
    }

    @Override
    public String render(final Map<String, Object> input) {
        final String docker = localCli(input).build();
        final Object instanceId = input.get("instanceId");
        return present(instanceId) ? docker + " (on " + instanceId + " via SSM)" : docker;
    }

    @Override
    public ToolResult execute(final Map<String, Object> input) {
        final String container = str(input.get("container"));
        final Object instanceId = input.get("instanceId");
        final ToolResult raw = present(instanceId)
                ? ssm.runShell(str(instanceId), str(input.get("region")), hostCommand(input) + " 2>&1", awsEnv.get())
                : CommandRunner.run(localCli(input).argv());
        final String combined = combined(raw);
        if (!raw.success()) {
            return fail(classify(combined), combined);
        }
        return logs(input, container, combined, raw);
    }

    private ToolResult logs(final Map<String, Object> input, final String container, final String combined,
            final ToolResult raw) {
        final String grep = str(input.get("grep"));
        final List<String> lines = new ArrayList<>();
        for (final String line : combined.isBlank() ? new String[0] : combined.split("\n", -1)) {
            if (grep == null || line.contains(grep)) {
                lines.add(line);
            }
        }
        String joined = String.join("\n", lines);
        boolean truncated = present(input.get("tail")) && lines.size() >= intOf(input.get("tail"));
        if (joined.length() > MAX_CHARS) {
            joined = joined.substring(joined.length() - MAX_CHARS);
            truncated = true;
        }
        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("container", container);
        output.put("serverTime", Instant.now().toString());
        output.put("lineCount", lines.size());
        output.put("truncated", truncated);
        output.put("since", str(input.get("since")));
        output.put("until", str(input.get("until")));
        output.put("logs", joined);
        if (raw.data().get("ssmCommandId") != null) {
            output.put("ssmCommandId", raw.data().get("ssmCommandId"));
        }
        return new ToolResult(0, "", "", false, output);
    }

    private Cli localCli(final Map<String, Object> input) {
        return Cli.docker("logs").opt("--since", input.get("since")).opt("--until", input.get("until"))
                .opt("--tail", input.get("tail")).positional(input.get("container"), "<container>");
    }

    private String hostCommand(final Map<String, Object> input) {
        final StringBuilder sb = new StringBuilder("docker logs");
        optInto(sb, "--since", input.get("since"));
        optInto(sb, "--until", input.get("until"));
        optInto(sb, "--tail", input.get("tail"));
        return sb.append(' ').append(shellQuote(str(input.get("container")))).toString();
    }

    private static void optInto(final StringBuilder sb, final String flag, final Object value) {
        if (present(value)) {
            sb.append(' ').append(flag).append(' ').append(shellQuote(value.toString()));
        }
    }

    private static String combined(final ToolResult raw) {
        final String out = raw.stdout() == null ? "" : raw.stdout();
        final String err = raw.stderr() == null ? "" : raw.stderr();
        return err.isBlank() ? out : (out.isBlank() ? err : out + "\n" + err);
    }

    private static String classify(final String text) {
        if (text.contains("No such container")) {
            return "CONTAINER_NOT_FOUND";
        }
        if (text.contains("AccessDenied") || text.contains("not authorized")) {
            return "PERMISSION_DENIED";
        }
        return "HOST_UNREACHABLE";
    }

    private static ToolResult fail(final String code, final String message) {
        final String flat = message.strip().replace('\n', ' ');
        return new ToolResult(1, "", "", false,
                Map.of("errorCode", code, "errorMessage", flat.length() > 500 ? flat.substring(0, 500) + "…" : flat));
    }

    private static boolean present(final Object value) {
        return value != null && !value.toString().isBlank();
    }

    private static String str(final Object value) {
        return value == null ? null : value.toString();
    }

    private static int intOf(final Object value) {
        try {
            return Integer.parseInt(str(value).trim());
        } catch (NumberFormatException | NullPointerException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
