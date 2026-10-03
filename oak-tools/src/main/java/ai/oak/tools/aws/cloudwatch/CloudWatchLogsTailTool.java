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
package ai.oak.tools.aws.cloudwatch;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Tail a CloudWatch Logs group ({@code aws logs tail <group> --since ...}). */
public final class CloudWatchLogsTailTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_CLOUDWATCH_LOGS_TAIL";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Tail a CloudWatch Logs group from a point in time.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("logGroupName", "since", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("logs", "tail").positional(input.get("logGroupName"), "<logGroupName>").opt("--since", input.get("since"))
                .opt("--region", input.get("region"));
    }
}
