package ai.oak.tools.aws.elasticache;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Read recent ElastiCache events (failovers, node replacements) for diagnosis. */
public final class ElastiCacheDescribeEventsTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_ELASTICACHE_DESCRIBE_EVENTS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Describe recent ElastiCache events for a source (e.g. failovers, node replacements).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("sourceIdentifier", "sourceType", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("elasticache", "describe-events").opt("--source-identifier", input.get("sourceIdentifier"))
                .opt("--source-type", input.get("sourceType")).opt("--region", input.get("region"));
    }
}
