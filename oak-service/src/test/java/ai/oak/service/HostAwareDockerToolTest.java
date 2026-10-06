/*
 * Copyright 2026 Oak Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.oak.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The host-aware docker tool must route to SSM when a step carries an instanceId (the container runs on a
 * remote host), and to local docker otherwise — and it must advertise instanceId so the platform sends it.
 */
class HostAwareDockerToolTest {

    private static final Supplier<Map<String, String>> ENV = Map::of;

    /** Captures what would be sent to SSM instead of actually calling AWS. */
    private static final class CapturingSsm extends SsmRunner {
        private String instanceId;
        private String region;
        private String command;

        @Override
        public ToolResult runShell(final String instanceId, final String region, final String command,
                final Map<String, String> awsEnv) {
            this.instanceId = instanceId;
            this.region = region;
            this.command = command;
            return new ToolResult(0, "ok", "", false);
        }
    }

    @Test
    void routesToSsmWithADockerCommandWhenInstanceIdIsGiven() {
        final CapturingSsm ssm = new CapturingSsm();
        final var tool = new HostAwareDockerTool("DOCKER_RESTART", "restart", ToolPermission.WRITE, "Restart a container.", false,
                ssm, ENV);
        final ToolResult result = tool
                .execute(Map.of("container", "opx-client-service", "instanceId", "i-0e0b", "region", "us-west-2"));
        assertTrue(result.success());
        assertEquals("i-0e0b", ssm.instanceId);
        assertEquals("us-west-2", ssm.region);
        assertEquals("docker restart 'opx-client-service'", ssm.command);
    }

    @Test
    void logsPassesTailThroughToTheHostCommand() {
        final CapturingSsm ssm = new CapturingSsm();
        final var logs = new HostAwareDockerTool("DOCKER_LOGS", "logs", ToolPermission.READ, "Show a container's logs.", true,
                ssm, ENV);
        logs.execute(Map.of("container", "svc", "instanceId", "i-1", "tail", "100"));
        assertEquals("docker logs --tail 100 'svc'", ssm.command);
        assertEquals(List.of("container", "instanceId", "tail"), logs.inputKeys());
        assertEquals(ToolPermission.READ, logs.permission());
    }

    @Test
    void renderShowsTheHostWhenTargetingRemotelyAndPlaceholdersWhenAbsent() {
        final var tool = new HostAwareDockerTool("DOCKER_RESTART", "restart", ToolPermission.WRITE, "Restart a container.", false,
                new CapturingSsm(), ENV);
        assertEquals("docker restart opx (on i-1 via SSM)", tool.render(Map.of("container", "opx", "instanceId", "i-1")));
        assertEquals("docker restart <container>", tool.render(Map.of()));
        assertEquals(List.of("container", "instanceId"), tool.inputKeys());
    }

    @Test
    void shellQuotingNeutralisesTrickyContainerNames() {
        final CapturingSsm ssm = new CapturingSsm();
        final var tool = new HostAwareDockerTool("DOCKER_STOP", "stop", ToolPermission.WRITE, "Stop a container.", false, ssm,
                ENV);
        tool.execute(Map.of("container", "a b; rm -rf /", "instanceId", "i-1"));
        assertEquals("docker stop 'a b; rm -rf /'", ssm.command, "spaces/metacharacters stay one quoted argument");
    }
}
