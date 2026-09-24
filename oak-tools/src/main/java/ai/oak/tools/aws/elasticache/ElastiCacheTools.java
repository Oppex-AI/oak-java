package ai.oak.tools.aws.elasticache;

import ai.oak.tools.ToolRegistry;

/** Registers the generic AWS ElastiCache (Redis) tools. */
public final class ElastiCacheTools {

    private ElastiCacheTools() {
    }

    public static void registerAll(final ToolRegistry registry) {
        registry.register(new ElastiCacheDescribeReplicationGroupsTool()).register(new ElastiCacheDescribeCacheClustersTool())
                .register(new ElastiCacheDescribeEventsTool()).register(new ElastiCacheRebootCacheClusterTool());
    }
}
