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

/** Read a CloudWatch metric's statistics over a window (e.g. CPU, connections). */
public final class CloudWatchGetMetricStatisticsTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_CLOUDWATCH_GET_METRIC_STATISTICS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Read a CloudWatch metric's statistics over a time window.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("namespace", "metricName", "dimensions", "startTime", "endTime", "period", "statistics");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("cloudwatch", "get-metric-statistics").required("--namespace", input.get("namespace"), "<namespace>")
                .required("--metric-name", input.get("metricName"), "<metricName>").opt("--dimensions", input.get("dimensions"))
                .opt("--start-time", input.get("startTime")).opt("--end-time", input.get("endTime"))
                .opt("--period", input.get("period")).optList("--statistics", input.get("statistics"))
                .opt("--region", input.get("region"));
    }
}
