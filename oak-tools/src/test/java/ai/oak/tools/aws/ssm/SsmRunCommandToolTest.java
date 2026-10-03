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
package ai.oak.tools.aws.ssm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SSM Run Command is a multi-step tool (send → poll → aggregate) that touches AWS, so execution is not
 * exercised here; the render preview — what a runbook reviewer sees before anything runs — is, along with
 * its permission and the tag-based targeting seam.
 */
class SsmRunCommandToolTest {

    private static ToolRegistry registry() {
        final ToolRegistry registry = new ToolRegistry();
        SsmTools.registerAll(registry);
        return registry;
    }

    @Test
    void rendersSendCommandWithInstanceIdsAndCommands() {
        final String command = registry().render("AWS_SSM_RUN_COMMAND", Map.of("instanceIds", List.of("i-1", "i-2"), "commands",
                List.of("systemctl restart nginx"), "region", "us-east-1")).orElseThrow();
        assertEquals(
                "aws ssm send-command --document-name AWS-RunShellCommand " +
                        "--parameters {\"commands\":[\"systemctl restart nginx\"]} --region us-east-1 --instance-ids i-1 i-2",
                command);
    }

    @Test
    void rendersPlaceholdersWhenInputAbsent() {
        final String command = registry().render("AWS_SSM_RUN_COMMAND", Map.of()).orElseThrow();
        assertEquals("aws ssm send-command --document-name AWS-RunShellCommand " +
                "--parameters {\"commands\":[\"<commands>\"]} --instance-ids <instanceIds>", command);
    }

    @Test
    void tagTargetsReplaceInstanceIds() {
        final Map<String, Object> target = new LinkedHashMap<>();
        target.put("Key", "tag:app");
        target.put("Values", List.of("web"));
        final String command = registry()
                .render("AWS_SSM_RUN_COMMAND", Map.of("targets", List.of(target), "commands", List.of("uptime"))).orElseThrow();
        assertTrue(command.contains("--targets [{\"Key\":\"tag:app\",\"Values\":[\"web\"]}]"), command);
        assertFalse(command.contains("--instance-ids"), command);
    }

    @Test
    void runningCommandsInsideAnInstanceIsDestructive() {
        assertEquals(ToolPermission.DESTRUCTIVE, registry().find("AWS_SSM_RUN_COMMAND").orElseThrow().permission());
    }
}
