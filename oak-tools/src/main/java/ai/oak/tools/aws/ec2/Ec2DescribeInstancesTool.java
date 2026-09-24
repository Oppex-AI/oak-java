package ai.oak.tools.aws.ec2;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Read the EC2 instances, optionally scoped by ids or filters. */
public final class Ec2DescribeInstancesTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_EC2_DESCRIBE_INSTANCES";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Describe EC2 instances (optionally scoped by instanceIds or filters).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("instanceIds", "filters", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("ec2", "describe-instances").optList("--instance-ids", input.get("instanceIds"))
                .opt("--filters", input.get("filters")).opt("--region", input.get("region"));
    }
}
