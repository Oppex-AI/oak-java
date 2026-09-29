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

import java.util.Collection;
import java.util.Map;

/**
 * A tiny, dependency-free JSON writer for the values a step's input carries — maps, lists, strings,
 * numbers, booleans and null (exactly what a parsed JSON input map holds).
 *
 * <p>It exists so a structured CLI argument (an AWS {@code --filters} array, {@code --dimensions}) is
 * serialised as real JSON — {@code [{"Name":"tag:Name","Values":["x"]}]} — rather than Java's
 * {@code toString()} form ({@code [{Name=tag:Name, Values=[x]}]}), which the AWS CLI rejects. Keeping
 * it in-module preserves oak-tools' no-external-dependency rule; JSON here is only ever produced, never
 * parsed (parsing is the calling service's concern).
 */
final class Json {

    private Json() {
    }

    /** Serialise {@code value} to a compact JSON string. */
    static String write(final Object value) {
        final StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(final StringBuilder out, final Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map);
        } else if (value instanceof Collection<?> collection) {
            writeArray(out, collection);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else {
            writeString(out, value.toString());
        }
    }

    private static void writeObject(final StringBuilder out, final Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            writeValue(out, entry.getValue());
        }
        out.append('}');
    }

    private static void writeArray(final StringBuilder out, final Collection<?> collection) {
        out.append('[');
        boolean first = true;
        for (final Object element : collection) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(out, element);
        }
        out.append(']');
    }

    private static void writeString(final StringBuilder out, final String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
