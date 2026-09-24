package ai.oak.tools.docker;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Stop a container ({@code docker stop <container>}). */
public final class DockerStopTool extends CommandTool {

    @Override
    public String capability() {
        return "DOCKER_STOP";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Stop a running container.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("container");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.docker("stop").positional(input.get("container"), "<container>");
    }
}
