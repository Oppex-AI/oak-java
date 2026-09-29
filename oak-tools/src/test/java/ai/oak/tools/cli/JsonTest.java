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
package ai.oak.tools.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The JSON reader is what lets a multi-step tool parse an AWS CLI response without a JSON library. */
class JsonTest {

    @Test
    void parsesScalars() {
        assertEquals("a\nb", Json.read("\"a\\nb\""));
        assertEquals(123L, Json.read("123"));
        assertEquals(1.5, Json.read("1.5"));
        assertEquals(Boolean.TRUE, Json.read("true"));
        assertNull(Json.read("null"));
    }

    @Test
    void parsesSsmListCommandInvocationsShape() {
        final Object root = Json.read("{\"CommandInvocations\":[{\"InstanceId\":\"i-1\",\"Status\":\"Success\"," +
                "\"CommandPlugins\":[{\"Output\":\"hello\\nworld\"}]}]}");
        assertTrue(root instanceof Map<?, ?>);
        final Object invocations = ((Map<?, ?>) root).get("CommandInvocations");
        assertTrue(invocations instanceof List<?>);
        final Map<?, ?> first = (Map<?, ?>) ((List<?>) invocations).get(0);
        assertEquals("i-1", first.get("InstanceId"));
        assertEquals("Success", first.get("Status"));
        final Map<?, ?> plugin = (Map<?, ?>) ((List<?>) first.get("CommandPlugins")).get(0);
        assertEquals("hello\nworld", plugin.get("Output"));
    }

    @Test
    void emptyContainersRoundTrip() {
        assertEquals(Map.of(), Json.read("{}"));
        assertEquals(List.of(), Json.read("[]"));
    }

    @Test
    void writeThenReadPreservesStructure() {
        final String json = Json.write(Map.of("commands", List.of("uptime")));
        final Object back = Json.read(json);
        assertEquals(List.of("uptime"), ((Map<?, ?>) back).get("commands"));
    }

    @Test
    void malformedInputThrows() {
        assertThrows(IllegalArgumentException.class, () -> Json.read("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("nul"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("{} extra"));
    }
}
