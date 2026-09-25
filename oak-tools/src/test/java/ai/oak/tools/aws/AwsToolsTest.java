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
package ai.oak.tools.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The AWS tools are pure command builders — no SDK, no network — so the render is deterministic and
 * this test is the whole guarantee at the print/verify stage: a step's command is exactly this.
 */
class AwsToolsTest {

    private static ToolRegistry registry() {
        final ToolRegistry registry = new ToolRegistry();
        AwsTools.registerAll(registry);
        return registry;
    }

    @Test
    void registrarWiresEverySupportedAwsService() {
        final ToolRegistry registry = registry();
        assertEquals(28, registry.all().size());
        assertTrue(registry.has("AWS_EC2_START_INSTANCES"));
        assertTrue(registry.has("AWS_RDS_REBOOT_DB_INSTANCE"));
        assertTrue(registry.has("AWS_CLOUDWATCH_DESCRIBE_ALARMS"));
        assertTrue(registry.has("AWS_S3_LIST_OBJECTS"));
        assertTrue(registry.has("AWS_ELASTICACHE_REBOOT_CACHE_CLUSTER"));
        assertTrue(registry.has("AWS_MSK_DESCRIBE_CLUSTER"));
    }

    @Test
    void s3DeleteIsDestructiveAndMskDescribeRenders() {
        assertEquals(ToolPermission.DESTRUCTIVE, registry().find("AWS_S3_DELETE_OBJECT").orElseThrow().permission());
        assertEquals("aws kafka describe-cluster --cluster-arn arn:aws:kafka:x --region us-west-2",
                registry().render("AWS_MSK_DESCRIBE_CLUSTER", Map.of("clusterArn", "arn:aws:kafka:x", "region", "us-west-2"))
                        .orElseThrow());
    }

    @Test
    void rdsRebootRendersRequiredIdentifier() {
        final String command = registry()
                .render("AWS_RDS_REBOOT_DB_INSTANCE", Map.of("dbInstanceIdentifier", "payments-db", "region", "us-west-2"))
                .orElseThrow();
        assertEquals("aws rds reboot-db-instance --db-instance-identifier payments-db --region us-west-2", command);
    }

    @Test
    void cloudwatchLogsTailUsesPositionalGroup() {
        final String command = registry().render("AWS_CLOUDWATCH_LOGS_TAIL", Map.of("logGroupName", "/app/prod", "since", "1h"))
                .orElseThrow();
        assertEquals("aws logs tail /app/prod --since 1h", command);
    }

    @Test
    void startJoinsMultipleInstanceIdsAndRegion() {
        final String command = registry()
                .render("AWS_EC2_START_INSTANCES", Map.of("instanceIds", List.of("i-1", "i-2"), "region", "us-west-2"))
                .orElseThrow();
        assertEquals("aws ec2 start-instances --instance-ids i-1 i-2 --region us-west-2", command);
    }

    @Test
    void requiredFlagEmitsPlaceholderWhenInputAbsent() {
        final String command = registry().render("AWS_EC2_STOP_INSTANCES", Map.of()).orElseThrow();
        assertEquals("aws ec2 stop-instances --instance-ids <instanceIds>", command);
    }

    @Test
    void describeOmitsEveryOptionalFlagWhenNoInputs() {
        final String command = registry().render("AWS_EC2_DESCRIBE_INSTANCES", Map.of()).orElseThrow();
        assertEquals("aws ec2 describe-instances", command);
    }

    @Test
    void readToolsAreReadAndMutatingToolsAreWrite() {
        assertEquals(ToolPermission.READ, registry().find("AWS_EC2_DESCRIBE_INSTANCES").orElseThrow().permission());
        assertEquals(ToolPermission.WRITE, registry().find("AWS_EC2_REBOOT_INSTANCES").orElseThrow().permission());
    }

    @Test
    void unknownCapabilityRendersEmpty() {
        assertTrue(registry().render("AWS_S3_DELETE_EVERYTHING", Map.of()).isEmpty());
        assertFalse(registry().has("AWS_S3_DELETE_EVERYTHING"));
    }
}
