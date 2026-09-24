package ai.oak.tools.docker;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Start a stopped container ({@code docker start <container>}). */
public final class DockerStartTool extends CommandTool {

    @Override
    public String capability() {
        return "DOCKER_START";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Start a stopped container.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("container");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.docker("start").positional(input.get("container"), "<container>");
    }
}
