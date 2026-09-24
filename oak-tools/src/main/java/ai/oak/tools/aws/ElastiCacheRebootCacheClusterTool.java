package ai.oak.tools.aws;

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Reboot specific nodes of an ElastiCache cache cluster. */
public final class ElastiCacheRebootCacheClusterTool implements Tool {

    @Override
    public String capability() {
        return "AWS_ELASTICACHE_REBOOT_CACHE_CLUSTER";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Reboot specific nodes of an ElastiCache cache cluster.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("cacheClusterId", "cacheNodeIdsToReboot", "region");
    }

    @Override
    public String render(final Map<String, Object> input) {
        return Cli.aws("elasticache", "reboot-cache-cluster")
                .required("--cache-cluster-id", input.get("cacheClusterId"), "<cacheClusterId>")
                .required("--cache-node-ids-to-reboot", input.get("cacheNodeIdsToReboot"), "<cacheNodeIds>")
                .opt("--region", input.get("region")).build();
    }
}
