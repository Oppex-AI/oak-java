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
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * membership, and deterministic ordering the platform relies on.
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
                "\"Tags\":[{\"Key\":\"Name\",\"Value\":\"product-svc\"},{\"Key\":\"Service\",\"Value\":\"product\"}]}," +
                "{\"InstanceId\":\"i-001\",\"State\":{\"Name\":\"stopped\"}}]}]}");
        final JsonNode asgs = json("{\"AutoScalingGroups\":[{\"AutoScalingGroupName\":\"product-svc-asg\"," +
                "\"Instances\":[{\"InstanceId\":\"i-002\"}]}]}");

        final Map<String, String> asg = DiscoveryCollector.parseAsgMembership(asgs);
        assertEquals(Map.of("i-002", "product-svc-asg"), asg);

        final List<DiscoveredResource> resources = DiscoveryCollector.parseInstances(instances, "us-west-2", asg);
        resources.sort(Comparator.comparing(DiscoveredResource::type).thenComparing(DiscoveredResource::id));

        assertEquals(2, resources.size());
        final DiscoveredResource first = resources.get(0);
        assertEquals("i-001", first.id());
        assertEquals("EC2_INSTANCE", first.type());
        assertEquals("stopped", first.state());
        assertNull(first.privateIp(), "no private IP → null, not empty string");
        assertTrue(first.tags().isEmpty());
        assertNull(first.asg());

        final DiscoveredResource second = resources.get(1);
        assertEquals("i-002", second.id());
        assertEquals("us-west-2", second.region());
        assertEquals("running", second.state());
        assertEquals("10.0.1.5", second.privateIp());
        assertEquals(Map.of("Name", "product-svc", "Service", "product"), second.tags());
        assertEquals("product-svc-asg", second.asg());
    }

    @Test
    void orderingIsDeterministicRegardlessOfInputOrder() {
        final String body = "{\"Reservations\":[{\"Instances\":[" +
                "{\"InstanceId\":\"i-c\"},{\"InstanceId\":\"i-a\"},{\"InstanceId\":\"i-b\"}]}]}";
        final List<String> ids = DiscoveryCollector.parseInstances(json(body), "us-east-1", Map.of()).stream()
                .sorted(Comparator.comparing(DiscoveredResource::type).thenComparing(DiscoveredResource::id))
                .map(DiscoveredResource::id).toList();
        assertEquals(List.of("i-a", "i-b", "i-c"), ids);
    }

    @Test
    void emptyResponsesYieldNoResources() {
        assertTrue(DiscoveryCollector.parseInstances(json("{}"), "us-east-1", Map.of()).isEmpty());
        assertTrue(DiscoveryCollector.parseAsgMembership(json("{}")).isEmpty());
    }
}
