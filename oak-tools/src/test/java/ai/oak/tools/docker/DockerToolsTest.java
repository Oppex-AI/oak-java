package ai.oak.tools.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Docker tools mix a boolean flag ({@code --all}) and a positional container arg — both are pinned here. */
class DockerToolsTest {

    private static ToolRegistry registry() {
        final ToolRegistry registry = new ToolRegistry();
        DockerTools.registerAll(registry);
        return registry;
    }

    @Test
    void registrarWiresEverySixCapabilities() {
        assertEquals(6, registry().all().size());
        assertTrue(registry().has("DOCKER_RESTART"));
    }

    @Test
    void logsRendersOptionalTailThenPositionalContainer() {
        final String command = registry().render("DOCKER_LOGS", Map.of("container", "svc-incidents", "tail", "200"))
                .orElseThrow();
        assertEquals("docker logs --tail 200 svc-incidents", command);
    }

    @Test
    void psFlagAppearsOnlyWhenTruthy() {
        assertEquals("docker ps", registry().render("DOCKER_PS", Map.of()).orElseThrow());
        assertEquals("docker ps --all", registry().render("DOCKER_PS", Map.of("all", true)).orElseThrow());
    }

    @Test
    void restartEmitsPlaceholderForMissingContainerAndIsWrite() {
        assertEquals("docker restart <container>", registry().render("DOCKER_RESTART", Map.of()).orElseThrow());
        assertEquals(ToolPermission.WRITE, registry().find("DOCKER_RESTART").orElseThrow().permission());
    }
}
