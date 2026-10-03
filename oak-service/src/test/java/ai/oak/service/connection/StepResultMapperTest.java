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
package ai.oak.service.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.service.client.StepError;
import ai.oak.tools.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The generic result envelope must carry <b>structured</b> output (never stdout for the platform to parse)
 * and a machine error code on failure. These tests pin the mapping for the common tool shapes.
 */
class StepResultMapperTest {

    @Test
    void jsonStdoutBecomesStructuredOutputWithVerbatimKeys() {
        final var result = new ToolResult(0, "{\"Datapoints\":[{\"Average\":42.1}],\"Label\":\"CPUUtilization\"}", "", false);
        final Map<String, Object> output = StepResultMapper.output(result);
        assertTrue(output.get("Datapoints") instanceof List, "CLI JSON is parsed into real fields, not left as text");
        assertEquals("CPUUtilization", output.get("Label"));
        assertNull(StepResultMapper.error(result), "no error on success");
    }

    @Test
    void nonJsonStdoutIsReturnedAsText() {
        final var result = new ToolResult(0, "opx-client-service", "", false);
        assertEquals("opx-client-service", StepResultMapper.output(result).get("stdout"));
    }

    @Test
    void noOutputYieldsEmptyMap() {
        assertTrue(StepResultMapper.output(new ToolResult(0, "", "", false)).isEmpty());
    }

    @Test
    void reservedErrorKeysDriveTheErrorAndAreKeptOutOfOutput() {
        final var result = new ToolResult(1, "", "", false, Map.of("errorCode", "DB_UNREACHABLE", "errorMessage", "boom"));
        final StepError error = StepResultMapper.error(result);
        assertEquals("DB_UNREACHABLE", error.code());
        assertEquals("boom", error.message());
        assertTrue(StepResultMapper.output(result).isEmpty(), "errorCode/errorMessage are not output facts");
    }

    @Test
    void ssmDataIsCarriedIntoOutput() {
        final var result = new ToolResult(0, "opx-client-service", "", false,
                Map.of("ssmCommandId", "abc-123", "status", "Success"));
        final Map<String, Object> output = StepResultMapper.output(result);
        assertEquals("abc-123", output.get("ssmCommandId"), "links OAK to AWS Run Command history");
        assertEquals("Success", output.get("status"));
        assertEquals("opx-client-service", output.get("stdout"));
    }

    @Test
    void failedResultCarriesAMachineCodeAndMessage() {
        final var result = new ToolResult(1, "", "Error response from daemon: No such container: svc", false);
        final StepError error = StepResultMapper.error(result);
        assertEquals("EXIT_NONZERO", error.code());
        assertTrue(error.message().contains("No such container"));
    }

    @Test
    void accessDeniedAndTimeoutAreClassified() {
        final var denied = new ToolResult(-1, "", "An error occurred (AccessDenied) ... not authorized to perform", false);
        assertEquals("ACCESS_DENIED", StepResultMapper.error(denied).code());
        final var timedOut = new ToolResult(-1, "", "timed out after PT2M", true);
        assertEquals("TIMEOUT", StepResultMapper.error(timedOut).code());
        final var couldNotRun = new ToolResult(-1, "", "aws: command not found", false);
        assertEquals("EXECUTION_ERROR", StepResultMapper.error(couldNotRun).code());
    }
}
