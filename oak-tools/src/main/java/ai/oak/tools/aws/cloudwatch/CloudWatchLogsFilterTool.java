package ai.oak.tools.aws.cloudwatch;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Search a CloudWatch Logs group for matching events ({@code aws logs filter-log-events}). */
public final class CloudWatchLogsFilterTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_CLOUDWATCH_LOGS_FILTER";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Filter a CloudWatch Logs group for matching events over a window.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("logGroupName", "filterPattern", "startTime", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("logs", "filter-log-events").required("--log-group-name", input.get("logGroupName"), "<logGroupName>")
                .opt("--filter-pattern", input.get("filterPattern")).opt("--start-time", input.get("startTime"))
                .opt("--region", input.get("region"));
    }
}
