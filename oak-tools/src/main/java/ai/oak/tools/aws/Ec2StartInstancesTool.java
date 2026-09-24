package ai.oak.tools.aws;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Start the given EC2 instances. */
public final class Ec2StartInstancesTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_EC2_START_INSTANCES";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Start the given (stopped) EC2 instances.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("instanceIds", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("ec2", "start-instances").required("--instance-ids", input.get("instanceIds"), "<instanceIds>")
                .opt("--region", input.get("region"));
    }
}
