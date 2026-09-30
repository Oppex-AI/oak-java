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

import ai.oak.service.client.StepError;
import ai.oak.tools.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a generic {@link ToolResult} to the platform's result envelope: a structured {@code output} map and,
 * on failure, a machine {@link StepError}. This is where "no stdout-as-result" is enforced — when a tool's
 * stdout is JSON (an AWS CLI response), it is parsed into real fields so the platform never parses text; the
 * keys are passed through verbatim (facts, not renamed — eyes+hands). Non-JSON output is returned as text
 * under {@code stdout}, and a no-output action yields an empty map.
 */
final class StepResultMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MESSAGE = 500;

    private StepResultMapper() {
    }

    /** The tool's structured result. Empty map when there is nothing to report. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> output(final ToolResult result) {
        final Map<String, Object> output = new LinkedHashMap<>();
        // Extra structured facts first (e.g. ssmCommandId, status for a host-executed step), verbatim.
        output.putAll(result.data());
        final String stdout = result.stdout();
        if (stdout != null && !stdout.isBlank()) {
            final JsonNode node = tryJson(stdout);
            if (node != null && node.isObject()) {
                output.putAll(MAPPER.convertValue(node, Map.class));
            } else if (node != null && node.isArray()) {
                output.put("items", MAPPER.convertValue(node, List.class));
            } else {
                output.put("stdout", stdout);
            }
        }
        if (result.stderr() != null && !result.stderr().isBlank()) {
            output.put("stderr", result.stderr());
        }
        return output;
    }

    /** The failure detail, or null when the result succeeded. */
    static StepError error(final ToolResult result) {
        return result.success() ? null : new StepError(code(result), message(result));
    }

    private static String code(final ToolResult result) {
        if (result.timedOut()) {
            return "TIMEOUT";
        }
        final String stderr = result.stderr() == null ? "" : result.stderr();
        if (stderr.contains("AccessDenied") || stderr.contains("not authorized")) {
            return "ACCESS_DENIED";
        }
        if (result.exitCode() == -1) {
            return "EXECUTION_ERROR";
        }
        return "EXIT_NONZERO";
    }

    private static String message(final ToolResult result) {
        String text = notBlank(result.stderr())
                ? result.stderr()
                : notBlank(result.stdout()) ? result.stdout() : "exit " + result.exitCode();
        text = text.strip().replace('\n', ' ');
        return text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE) + "…" : text;
    }

    private static boolean notBlank(final String s) {
        return s != null && !s.isBlank();
    }

    private static JsonNode tryJson(final String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
