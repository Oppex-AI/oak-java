package ai.oak.tools.docker;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Read a container's logs ({@code docker logs [--tail N] <container>}). */
public final class DockerLogsTool extends CommandTool {

    @Override
    public String capability() {
        return "DOCKER_LOGS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Read a container's logs (optionally the last N lines).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("container", "tail");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.docker("logs").opt("--tail", input.get("tail")).positional(input.get("container"), "<container>");
    }
}
