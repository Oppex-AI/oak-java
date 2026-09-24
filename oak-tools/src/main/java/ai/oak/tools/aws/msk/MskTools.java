package ai.oak.tools.aws.msk;

import ai.oak.tools.ToolRegistry;

/** Registers the generic AWS MSK (Kafka) tools. */
public final class MskTools {

    private MskTools() {
    }

    public static void registerAll(final ToolRegistry registry) {
        registry.register(new MskListClustersTool()).register(new MskDescribeClusterTool()).register(new MskListNodesTool())
                .register(new MskGetBootstrapBrokersTool()).register(new MskRebootBrokerTool());
    }
}
