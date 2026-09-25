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
package ai.oak.tools;

import ai.oak.tools.cli.Cli;
import ai.oak.tools.cli.CommandRunner;
import java.util.Map;

/**
 * A {@link Tool} whose work is a single command line. The common case for AWS, docker, kubectl and the
 * like.
 *
 * <p>A subclass defines the command once, in {@link #command}, and gets both halves of the contract for
 * free and consistent with each other: {@link #render} is that command's preview string, and
 * {@link #execute} runs that command's exact argv. Because both come from the one definition, the
 * preview can never drift from what actually runs.
 */
public abstract class CommandTool implements Tool {

    /** Build this tool's command from the step's resolved inputs. Called by both render and execute. */
    public abstract Cli command(Map<String, Object> input);

    @Override
    public String render(final Map<String, Object> input) {
        return command(input).build();
    }

    @Override
    public ToolResult execute(final Map<String, Object> input) {
        return CommandRunner.run(command(input).argv());
    }
}
