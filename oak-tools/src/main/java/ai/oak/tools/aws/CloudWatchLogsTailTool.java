package ai.oak.tools.aws;

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Tail a CloudWatch Logs group ({@code aws logs tail <group> --since ...}). */
public final class CloudWatchLogsTailTool implements Tool {

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
    public String render(final Map<String, Object> input) {
        return Cli.aws("logs", "tail").positional(input.get("logGroupName"), "<logGroupName>").opt("--since", input.get("since"))
                .opt("--region", input.get("region")).build();
    }
}
