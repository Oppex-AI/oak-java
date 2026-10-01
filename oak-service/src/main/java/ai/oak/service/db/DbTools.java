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
package ai.oak.service.db;

import ai.oak.tools.ToolResult;
import java.util.Map;

/** Small shared helpers for the DB tools: envelope results and lenient input coercion. */
final class DbTools {

    private DbTools() {
    }

    /** A success envelope carrying the structured output in the result's data. */
    static ToolResult ok(final Map<String, Object> output) {
        return new ToolResult(0, "", "", false, output);
    }

    /** A failure envelope with a contract error code + message, carried for the result mapper. */
    static ToolResult error(final String code, final String message) {
        return new ToolResult(1, "", "", false, Map.of("errorCode", code, "errorMessage", message == null ? code : message));
    }

    static String str(final Object value) {
        return value == null ? null : value.toString();
    }

    static int intOf(final Object value, final int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && !value.toString().isBlank()) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }

    static boolean boolOf(final Object value) {
        return value != null && ("true".equalsIgnoreCase(value.toString()) || Boolean.TRUE.equals(value));
    }
}
