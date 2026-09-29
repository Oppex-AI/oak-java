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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.service.client.DiscoveredResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The collector's job is to turn a cloud API's JSON into plain facts — no interpretation. These tests
 * drive the pure parsing with canned AWS output (no live AWS), pinning the shape, verbatim tags, ASG
 * membership, RDS fields, container parsing, and deterministic ordering the platform relies on.
 */
class DiscoveryCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(final String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mapsInstancesToFactsWithVerbatimTagsAndAsg() {
        final JsonNode instances = json("{\"Reservations\":[{\"Instances\":[" +
                "{\"InstanceId\":\"i-002\",\"State\":{\"Name\":\"running\"},\"PrivateIpAddress\":\"10.0.1.5\"," +
                "\"InstanceType\":\"t3.large\",\"Placement\":{\"AvailabilityZone\":\"us-west-2a\"}," +
                "\"Tags\":[{\"Key\":\"Name\",\"Value\":\"product-svc\"},{\"Key\":\"Service\",\"Value\":\"product\"}]}," +
                "{\"InstanceId\":\"i-001\",\"State\":{\"Name\":\"stopped\"}}]}]}");
        final JsonNode asgs = json("{\"AutoScalingGroups\":[{\"AutoScalingGroupName\":\"product-svc-asg\"," +
                "\"Instances\":[{\"InstanceId\":\"i-002\"}]}]}");

        final Map<String, String> asg = DiscoveryCollector.parseAsgMembership(asgs);
        assertEquals(Map.of("i-002", "product-svc-asg"), asg);

        final List<DiscoveredResource> resources = DiscoveryCollector.parseInstances(instances, "us-west-2", asg);
        resources.sort(Comparator.comparing(DiscoveredResource::getType).thenComparing(DiscoveredResource::getId));

        assertEquals(2, resources.size());
        final DiscoveredResource stopped = resources.get(0);
        assertEquals("i-001", stopped.getId());
        assertEquals("EC2_INSTANCE", stopped.getType());
        assertEquals("stopped", stopped.getFacts().get("state"));
        assertFalse(stopped.getFacts().containsKey("privateIp"), "no private IP → key omitted, not blank");
        assertFalse(stopped.getFacts().containsKey("tags"));
        assertFalse(stopped.getFacts().containsKey("asg"));

        final DiscoveredResource running = resources.get(1);
        assertEquals("i-002", running.getId());
        assertEquals("us-west-2", running.getFacts().get("region"));
        assertEquals("running", running.getFacts().get("state"));
        assertEquals("t3.large", running.getFacts().get("instanceType"));
        assertEquals("10.0.1.5", running.getFacts().get("privateIp"));
        assertEquals("us-west-2a", running.getFacts().get("availabilityZone"));
        assertEquals(Map.of("Name", "product-svc", "Service", "product"), running.getFacts().get("tags"));
        assertEquals("product-svc-asg", running.getFacts().get("asg"));
    }

    @Test
    void mapsDbInstancesToFacts() {
        final JsonNode dbs = json("{\"DBInstances\":[{\"DBInstanceIdentifier\":\"payments-db\"," +
                "\"DBInstanceStatus\":\"available\",\"Engine\":\"postgres\",\"EngineVersion\":\"15.4\"," +
                "\"DBInstanceClass\":\"db.r6g.large\",\"MultiAZ\":true," +
                "\"Endpoint\":{\"Address\":\"payments-db.abc.us-west-2.rds.amazonaws.com\",\"Port\":5432}," +
                "\"TagList\":[{\"Key\":\"Service\",\"Value\":\"payments\"}]}]}");
        final List<DiscoveredResource> resources = DiscoveryCollector.parseDbInstances(dbs, "us-west-2");
        assertEquals(1, resources.size());
        final DiscoveredResource db = resources.get(0);
        assertEquals("RDS_INSTANCE", db.getType());
        assertEquals("payments-db", db.getId());
        assertEquals("available", db.getFacts().get("state"));
        assertEquals("postgres", db.getFacts().get("engine"));
        assertEquals("db.r6g.large", db.getFacts().get("dbInstanceClass"));
        assertEquals("payments-db.abc.us-west-2.rds.amazonaws.com", db.getFacts().get("endpoint"));
        assertEquals(5432, db.getFacts().get("port"));
        assertEquals(Boolean.TRUE, db.getFacts().get("multiAz"));
        assertEquals(Map.of("Service", "payments"), db.getFacts().get("tags"));
    }

    @Test
    void parsesDockerPsLinesIntoContainersLinkedToHost() {
        final String output = "{\"ID\":\"9f3c\",\"Names\":\"product-service\",\"Image\":\"repo/product:sha1\"," +
                "\"State\":\"running\",\"Status\":\"Up 2 hours\",\"Ports\":\"0.0.0.0:8080->8080/tcp\"," +
                "\"Labels\":\"com.docker.compose.service=product-service,tier=api\"}\n" + "not-json-line\n";
        final List<DiscoveredResource> containers = HostContainerScanner.parseContainers(output, "i-0e0b", "us-west-2");
        assertEquals(1, containers.size(), "the non-JSON line is skipped, not fatal");
        final DiscoveredResource c = containers.get(0);
        assertEquals("DOCKER_CONTAINER", c.getType());
        assertEquals("9f3c", c.getId());
        assertEquals("product-service", c.getFacts().get("name"));
        assertEquals("repo/product:sha1", c.getFacts().get("image"));
        assertEquals("running", c.getFacts().get("state"));
        assertEquals("i-0e0b", c.getFacts().get("hostInstanceId"));
        assertEquals("us-west-2", c.getFacts().get("region"));
        assertEquals(Map.of("com.docker.compose.service", "product-service", "tier", "api"), c.getFacts().get("labels"));
    }

    @Test
    void orderingIsDeterministicRegardlessOfInputOrder() {
        final String body = "{\"Reservations\":[{\"Instances\":[" +
                "{\"InstanceId\":\"i-c\"},{\"InstanceId\":\"i-a\"},{\"InstanceId\":\"i-b\"}]}]}";
        final List<String> ids = DiscoveryCollector.parseInstances(json(body), "us-east-1", Map.of()).stream()
                .sorted(Comparator.comparing(DiscoveredResource::getType).thenComparing(DiscoveredResource::getId))
                .map(DiscoveredResource::getId).toList();
        assertEquals(List.of("i-a", "i-b", "i-c"), ids);
    }

    @Test
    void emptyResponsesYieldNoResources() {
        assertTrue(DiscoveryCollector.parseInstances(json("{}"), "us-east-1", Map.of()).isEmpty());
        assertTrue(DiscoveryCollector.parseDbInstances(json("{}"), "us-east-1").isEmpty());
        assertTrue(DiscoveryCollector.parseAsgMembership(json("{}")).isEmpty());
        assertTrue(HostContainerScanner.parseContainers("", "i-1", "us-east-1").isEmpty());
    }
}
