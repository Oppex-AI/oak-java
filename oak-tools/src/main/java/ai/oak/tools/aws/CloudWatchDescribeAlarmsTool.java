package ai.oak.tools.aws;

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Read CloudWatch alarms, optionally by name or by state (e.g. only ALARM). */
public final class CloudWatchDescribeAlarmsTool implements Tool {

    @Override
    public String capability() {
        return "AWS_CLOUDWATCH_DESCRIBE_ALARMS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Describe CloudWatch alarms, optionally filtered by name or state.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("alarmNames", "stateValue", "region");
    }

    @Override
    public String render(final Map<String, Object> input) {
        return Cli.aws("cloudwatch", "describe-alarms").optList("--alarm-names", input.get("alarmNames"))
                .opt("--state-value", input.get("stateValue")).opt("--region", input.get("region")).build();
    }
}
