package ai.oak.tools.aws;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** List the broker nodes of an MSK cluster. */
public final class MskListNodesTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_MSK_LIST_NODES";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "List the broker nodes of an MSK cluster.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("clusterArn", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("kafka", "list-nodes").required("--cluster-arn", input.get("clusterArn"), "<clusterArn>")
                .opt("--region", input.get("region"));
    }
}
