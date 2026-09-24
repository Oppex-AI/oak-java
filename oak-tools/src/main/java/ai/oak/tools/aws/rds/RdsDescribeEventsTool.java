package ai.oak.tools.aws.rds;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Read recent RDS events (failovers, restarts, storage) for diagnosis. */
public final class RdsDescribeEventsTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_RDS_DESCRIBE_EVENTS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Describe recent RDS events for a source (e.g. failovers, restarts, storage).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("sourceIdentifier", "sourceType", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("rds", "describe-events").opt("--source-identifier", input.get("sourceIdentifier"))
                .opt("--source-type", input.get("sourceType")).opt("--region", input.get("region"));
    }
}
