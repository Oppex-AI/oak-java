package ai.oak.tools.aws;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Get an MSK cluster's bootstrap broker connection string. */
public final class MskGetBootstrapBrokersTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_MSK_GET_BOOTSTRAP_BROKERS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Get an MSK cluster's bootstrap broker connection string.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("clusterArn", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("kafka", "get-bootstrap-brokers").required("--cluster-arn", input.get("clusterArn"), "<clusterArn>")
                .opt("--region", input.get("region"));
    }
}
