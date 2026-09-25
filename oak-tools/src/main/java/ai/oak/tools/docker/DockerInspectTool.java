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

/** Inspect a container's config/state ({@code docker inspect <container>}). */
public final class DockerInspectTool extends CommandTool {

    @Override
    public String capability() {
        return "DOCKER_INSPECT";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Inspect a container's low-level config and state.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("container");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.docker("inspect").positional(input.get("container"), "<container>");
    }
}
