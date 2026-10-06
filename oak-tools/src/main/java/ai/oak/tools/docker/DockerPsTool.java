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
package ai.oak.tools.docker;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** List containers ({@code docker ps}), optionally all and/or filtered. */
public final class DockerPsTool extends CommandTool {

    @Override
    public String capability() {
        return "DOCKER_PS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "List docker containers (optionally all, optionally filtered).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("all", "filter");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.docker("ps").flag("--all", input.get("all")).opt("--filter", normalizeFilter(input.get("filter")));
    }

    /**
     * Docker's {@code --filter} requires {@code name=value}. A bare value (no {@code =}) is never a valid
     * filter, so treat it as a name filter — the common intent when a step passes just a container or
     * service name. A real filter ({@code name=…}, {@code status=…}, {@code label=k=v}) already has an
     * {@code =} and passes through untouched.
     */
    private static Object normalizeFilter(final Object filter) {
        if (filter == null) {
            return null;
        }
        final String value = filter.toString().trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.contains("=") ? value : "name=" + value;
    }
}
