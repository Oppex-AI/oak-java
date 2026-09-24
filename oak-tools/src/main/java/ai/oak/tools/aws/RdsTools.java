package ai.oak.tools.aws;

import ai.oak.tools.ToolRegistry;

/** Registers the generic AWS RDS tools. */
public final class RdsTools {

    private RdsTools() {
    }

    public static void registerAll(final ToolRegistry registry) {
        registry.register(new RdsDescribeDbInstancesTool()).register(new RdsDescribeEventsTool())
                .register(new RdsRebootDbInstanceTool()).register(new RdsStartDbInstanceTool())
                .register(new RdsStopDbInstanceTool()).register(new RdsCreateDbSnapshotTool());
    }
}
