package ai.oak.tools.docker;

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Restart a container ({@code docker restart <container>}). */
public final class DockerRestartTool implements Tool {

    @Override
    public String capability() {
        return "DOCKER_RESTART";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Restart a container.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("container");
    }

    @Override
    public String render(final Map<String, Object> input) {
        return Cli.docker("restart").positional(input.get("container"), "<container>").build();
    }
}
