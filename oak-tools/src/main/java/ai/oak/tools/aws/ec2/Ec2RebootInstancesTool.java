package ai.oak.tools.aws.ec2;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Reboot the given EC2 instances. */
public final class Ec2RebootInstancesTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_EC2_REBOOT_INSTANCES";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Reboot the given EC2 instances (in-place restart, keeps the instance).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("instanceIds", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("ec2", "reboot-instances").required("--instance-ids", input.get("instanceIds"), "<instanceIds>")
                .opt("--region", input.get("region"));
    }
}
