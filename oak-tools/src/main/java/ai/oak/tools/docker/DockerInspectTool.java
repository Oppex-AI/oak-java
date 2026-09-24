package ai.oak.tools.docker;

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Inspect a container's config/state ({@code docker inspect <container>}). */
public final class DockerInspectTool implements Tool {

    @Override
    public String capability() {
        return "DOCKER_INSPECT";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Inspect a container's low-level config and state.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("container");
    }

    @Override
    public String render(final Map<String, Object> input) {
        return Cli.docker("inspect").positional(input.get("container"), "<container>").build();
    }
}
