package ai.oak.tools.docker;

import ai.oak.tools.ToolRegistry;

/**
 * Registers the generic Docker tools into a {@link ToolRegistry}. Same shape as {@code AwsTools} —
 * one entry point a consumer (a platform layer, or a customer's tool service) calls to add the set.
 */
public final class DockerTools {

    private DockerTools() {
    }

    public static void registerAll(final ToolRegistry registry) {
        registry.register(new DockerPsTool()).register(new DockerLogsTool()).register(new DockerInspectTool())
                .register(new DockerRestartTool()).register(new DockerStopTool()).register(new DockerStartTool());
    }
}
