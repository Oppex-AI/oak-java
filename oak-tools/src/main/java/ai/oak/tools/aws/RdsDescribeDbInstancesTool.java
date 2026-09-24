package ai.oak.tools.aws;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Read RDS DB instances (state, endpoint, class), optionally scoped to one identifier. */
public final class RdsDescribeDbInstancesTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_RDS_DESCRIBE_DB_INSTANCES";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Describe RDS DB instances (status, endpoint, class), optionally one identifier.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("dbInstanceIdentifier", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("rds", "describe-db-instances").opt("--db-instance-identifier", input.get("dbInstanceIdentifier"))
                .opt("--region", input.get("region"));
    }
}
