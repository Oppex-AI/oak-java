package ai.oak.tools.aws.msk;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Describe an MSK cluster (state, broker count, version). */
public final class MskDescribeClusterTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_MSK_DESCRIBE_CLUSTER";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Describe an MSK cluster (state, broker count, version).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("clusterArn", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("kafka", "describe-cluster").required("--cluster-arn", input.get("clusterArn"), "<clusterArn>")
                .opt("--region", input.get("region"));
    }
}
