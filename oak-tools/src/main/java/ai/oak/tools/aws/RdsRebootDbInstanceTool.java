package ai.oak.tools.aws;

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Reboot an RDS DB instance. */
public final class RdsRebootDbInstanceTool implements Tool {

    @Override
    public String capability() {
        return "AWS_RDS_REBOOT_DB_INSTANCE";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Reboot an RDS DB instance (optionally with failover).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("dbInstanceIdentifier", "region");
    }

    @Override
    public String render(final Map<String, Object> input) {
        return Cli.aws("rds", "reboot-db-instance")
                .required("--db-instance-identifier", input.get("dbInstanceIdentifier"), "<dbInstanceIdentifier>")
                .opt("--region", input.get("region")).build();
    }
}
