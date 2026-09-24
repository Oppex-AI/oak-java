package ai.oak.tools.aws;

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Take a manual RDS DB snapshot (e.g. before a risky remediation). */
public final class RdsCreateDbSnapshotTool implements Tool {

    @Override
    public String capability() {
        return "AWS_RDS_CREATE_DB_SNAPSHOT";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Create a manual RDS DB snapshot before a risky change.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("dbInstanceIdentifier", "dbSnapshotIdentifier", "region");
    }

    @Override
    public String render(final Map<String, Object> input) {
        return Cli.aws("rds", "create-db-snapshot")
                .required("--db-instance-identifier", input.get("dbInstanceIdentifier"), "<dbInstanceIdentifier>")
                .required("--db-snapshot-identifier", input.get("dbSnapshotIdentifier"), "<dbSnapshotIdentifier>")
                .opt("--region", input.get("region")).build();
    }
}
