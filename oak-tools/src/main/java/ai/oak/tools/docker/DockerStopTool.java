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

/** Stop a container ({@code docker stop <container>}). */
public final class DockerStopTool extends CommandTool {

    @Override
    public String capability() {
        return "DOCKER_STOP";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Stop a running container.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("container");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.docker("stop").positional(input.get("container"), "<container>");
    }
}
