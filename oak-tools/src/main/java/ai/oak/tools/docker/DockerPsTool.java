package ai.oak.tools.docker;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** List containers ({@code docker ps}), optionally all and/or filtered. */
public final class DockerPsTool extends CommandTool {

    @Override
    public String capability() {
        return "DOCKER_PS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "List docker containers (optionally all, optionally filtered).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("all", "filter");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.docker("ps").flag("--all", input.get("all")).opt("--filter", input.get("filter"));
    }
}
