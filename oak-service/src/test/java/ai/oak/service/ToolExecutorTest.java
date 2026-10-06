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

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Region is OAK connection context: it is injected into every step input, overriding anything sent. */
class ToolExecutorTest {

    @Test
    void withRegionFillsWhenAbsent() {
        assertEquals("us-west-2", ToolExecutor.withRegion(Map.of(), "us-west-2").get("region"));
    }

    @Test
    void withRegionOverridesWhatArrived() {
        assertEquals("us-west-2", ToolExecutor.withRegion(Map.of("region", "eu-central-1"), "us-west-2").get("region"));
    }

    @Test
    void withRegionLeavesInputUntouchedWhenRegionUnknown() {
        assertFalse(ToolExecutor.withRegion(Map.of("a", "b"), null).containsKey("region"));
        assertFalse(ToolExecutor.withRegion(Map.of("a", "b"), "  ").containsKey("region"));
    }
}
