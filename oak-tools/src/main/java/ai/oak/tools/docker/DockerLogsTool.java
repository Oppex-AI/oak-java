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

/** Read a container's logs ({@code docker logs [--tail N] <container>}). */
public final class DockerLogsTool extends CommandTool {

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
        return "Read a container's logs (optionally the last N lines).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("container", "tail");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.docker("logs").opt("--tail", input.get("tail")).positional(input.get("container"), "<container>");
    }
}
