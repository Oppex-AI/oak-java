package ai.oak.tools.aws;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** List MSK (Kafka) clusters, optionally by name filter. */
public final class MskListClustersTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_MSK_LIST_CLUSTERS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "List MSK (Kafka) clusters, optionally filtered by name.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("clusterNameFilter", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("kafka", "list-clusters").opt("--cluster-name-filter", input.get("clusterNameFilter"))
                .opt("--region", input.get("region"));
    }
}
