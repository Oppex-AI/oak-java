package ai.oak.tools.aws;

import ai.oak.tools.ToolRegistry;

/** Registers the generic AWS CloudWatch (metrics, alarms, logs) tools. */
public final class CloudWatchTools {

    private CloudWatchTools() {
    }

    public static void registerAll(final ToolRegistry registry) {
        registry.register(new CloudWatchGetMetricStatisticsTool()).register(new CloudWatchDescribeAlarmsTool())
                .register(new CloudWatchLogsFilterTool()).register(new CloudWatchLogsTailTool());
    }
}
