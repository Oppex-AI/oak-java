package ai.oak.tools.aws;

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Read the health/state of the given EC2 instances. */
public final class Ec2DescribeInstanceStatusTool implements Tool {

    @Override
    public String capability() {
        return "AWS_EC2_DESCRIBE_INSTANCE_STATUS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Check the status (health/state) of the given EC2 instances.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("instanceIds", "region");
    }

    @Override
    public String render(final Map<String, Object> input) {
        return Cli.aws("ec2", "describe-instance-status").required("--instance-ids", input.get("instanceIds"), "<instanceIds>")
                .opt("--region", input.get("region")).build();
    }
}
