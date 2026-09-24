package ai.oak.tools.aws.rds;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Stop a running RDS DB instance. */
public final class RdsStopDbInstanceTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_RDS_STOP_DB_INSTANCE";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Stop a running RDS DB instance.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("dbInstanceIdentifier", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("rds", "stop-db-instance")
                .required("--db-instance-identifier", input.get("dbInstanceIdentifier"), "<dbInstanceIdentifier>")
                .opt("--region", input.get("region"));
    }
}
