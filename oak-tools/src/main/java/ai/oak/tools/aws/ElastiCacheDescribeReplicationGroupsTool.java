package ai.oak.tools.aws;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Read ElastiCache (Redis) replication groups — status, endpoints, node groups. */
public final class ElastiCacheDescribeReplicationGroupsTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_ELASTICACHE_DESCRIBE_REPLICATION_GROUPS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Describe ElastiCache (Redis) replication groups, optionally one id.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("replicationGroupId", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("elasticache", "describe-replication-groups")
                .opt("--replication-group-id", input.get("replicationGroupId")).opt("--region", input.get("region"));
    }
}
