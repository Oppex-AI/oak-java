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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny, dependency-free JSON codec for the values a step's input and a tool's output carry — maps,
 * lists, strings, numbers, booleans and null (exactly what a parsed JSON document holds).
 *
 * <p>{@link #write} exists so a structured CLI argument (an AWS {@code --filters} array,
 * {@code --dimensions}) is serialised as real JSON — {@code [{"Name":"tag:Name","Values":["x"]}]} —
 * rather than Java's {@code toString()} form ({@code [{Name=tag:Name, Values=[x]}]}), which the AWS CLI
 * rejects. {@link #read} parses a JSON response back to those same Java types, so a multi-step tool
 * (e.g. SSM Run Command polling {@code list-command-invocations}) can read a CLI's JSON output without
 * pulling in Jackson — keeping oak-tools' no-external-dependency rule intact.
 */
public final class Json {

    private Json() {
    }

    /**
     * Parse a JSON document into Java values: {@link Map} for an object (insertion-ordered), {@link List}
     * for an array, {@link String}, {@link Long}/{@link Double} for a number, {@link Boolean}, or null.
     *
     * @throws IllegalArgumentException if the text is not a single well-formed JSON value.
     */
    public static Object read(final String json) {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        final Reader reader = new Reader(json);
        final Object value = reader.readValue();
        reader.skipWs();
        if (!reader.atEnd()) {
            throw new IllegalArgumentException("trailing content in JSON at index " + reader.pos);
        }
        return value;
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

    /**
     * A minimal recursive-descent JSON parser over a single string, tracking one cursor. Small on
     * purpose — it accepts the well-formed JSON an AWS CLI emits, and rejects malformed input with an
     * {@link IllegalArgumentException} rather than guessing.
     */
    private static final class Reader {

        private final String s;
        private int pos;

        Reader(final String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipWs();
            if (atEnd()) {
                throw err("unexpected end of input");
            }
            final char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            pos++; // consume '{'
            final Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (consumeIf('}')) {
                return map;
            }
            while (true) {
                skipWs();
                final String key = readString();
                skipWs();
                expect(':');
                map.put(key, readValue());
                skipWs();
                if (consumeIf('}')) {
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> readArray() {
            pos++; // consume '['
            final List<Object> list = new ArrayList<>();
            skipWs();
            if (consumeIf(']')) {
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                if (consumeIf(']')) {
                    return list;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            final StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                final char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    sb.append(readEscape());
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }

        private char readEscape() {
            if (atEnd()) {
                throw err("dangling escape");
            }
            final char e = s.charAt(pos++);
            return switch (e) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'u' -> readUnicode();
                default -> throw err("invalid escape \\" + e);
            };
        }

        private char readUnicode() {
            if (pos + 4 > s.length()) {
                throw err("truncated \\u escape");
            }
            final String hex = s.substring(pos, pos + 4);
            pos += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException ex) {
                throw err("invalid \\u escape " + hex);
            }
        }

        private Object readNumber() {
            final int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            final String num = s.substring(start, pos);
            if (num.isEmpty()) {
                throw err("expected a value");
            }
            if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException ignore) {
                    // falls through to double for values that overflow a long
                }
            }
            return Double.parseDouble(num);
        }

        private Boolean readBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw err("invalid literal");
        }

        private Object readNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw err("invalid literal");
        }

        private boolean consumeIf(final char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(final char c) {
            if (!consumeIf(c)) {
                throw err("expected '" + c + "'");
            }
        }

        private IllegalArgumentException err(final String message) {
            return new IllegalArgumentException(message + " at index " + pos);
        }
    }
}
