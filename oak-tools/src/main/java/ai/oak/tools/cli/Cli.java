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
import java.util.List;
import java.util.Map;

/**
 * Tiny fluent builder for a shell command line (AWS CLI, docker, kubectl, ...), assembled from a
 * runbook step's resolved inputs.
 *
 * <p>Optional flags are omitted when their input is absent; a required flag or positional emits a
 * {@code <placeholder>} so a print/preview command still reads as a template when the plan left the
 * value unresolved. A list-valued input (e.g. several instance ids) becomes several arguments.
 * Framework-free and shared by every tool package.
 *
 * <p>The builder keeps the command as a list of argv tokens, so it can serve both purposes from one
 * definition: {@link #build()} joins them into the human/LLM preview string, and {@link #argv()}
 * hands the exact tokens to a process — no re-parsing of the preview, which would mis-split a value
 * that contains a space (an S3 key, a tag value) and is why the two must not be derived from each
 * other by string surgery.
 */
public final class Cli {

    private final List<String> tokens = new ArrayList<>();

    private Cli(final String... head) {
        for (final String token : head) {
            tokens.add(token);
        }
    }

    /** {@code aws <service> <action>}. */
    public static Cli aws(final String service, final String action) {
        return new Cli("aws", service, action);
    }

    /** {@code docker <action>}. */
    public static Cli docker(final String action) {
        return new Cli("docker", action);
    }

    /**
     * Optional flag — omitted when absent/blank. A structured value (an object or array, e.g. an AWS
     * {@code --filters} list) is serialised as a single JSON argument; a scalar as its plain string.
     */
    public Cli opt(final String flag, final Object value) {
        if (present(value)) {
            tokens.add(flag);
            tokens.add(arg(value));
        }
        return this;
    }

    /** Optional list flag — one argument per value, omitted when absent/blank. */
    public Cli optList(final String flag, final Object value) {
        final List<String> values = valuesOf(value);
        if (!values.isEmpty()) {
            tokens.add(flag);
            tokens.addAll(values);
        }
        return this;
    }

    /** Required list flag — one argument per value, or {@code placeholder} when absent. */
    public Cli required(final String flag, final Object value, final String placeholder) {
        final List<String> values = valuesOf(value);
        tokens.add(flag);
        if (values.isEmpty()) {
            tokens.add(placeholder);
        } else {
            tokens.addAll(values);
        }
        return this;
    }

    /** Bare boolean flag (e.g. {@code --all}) — appended only when the input is truthy. */
    public Cli flag(final String name, final Object enabled) {
        if (truthy(enabled)) {
            tokens.add(name);
        }
        return this;
    }

    /** Required positional argument (e.g. a docker container) — {@code placeholder} when absent. */
    public Cli positional(final Object value, final String placeholder) {
        tokens.add(present(value) ? arg(value) : placeholder);
        return this;
    }

    /** The command as a preview string. A template, not something to hand to a shell verbatim. */
    public String build() {
        return String.join(" ", tokens);
    }

    /** The exact argv (program first) to hand to a process. Never re-parse {@link #build()} for this. */
    public List<String> argv() {
        return List.copyOf(tokens);
    }

    private static boolean present(final Object value) {
        return value != null && !value.toString().isBlank();
    }

    private static boolean truthy(final Object value) {
        return value != null && ("true".equalsIgnoreCase(value.toString()) || Boolean.TRUE.equals(value));
    }

    private static List<String> valuesOf(final Object value) {
        final List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (final Object element : collection) {
                if (element != null && !String.valueOf(element).isBlank()) {
                    out.add(arg(element));
                }
            }
        } else if (present(value)) {
            out.add(arg(value));
        }
        return out;
    }

    /** A single argv token: JSON for a structured value (object/array), the plain string for a scalar. */
    private static String arg(final Object value) {
        return (value instanceof Map<?, ?> || value instanceof Collection<?>) ? Json.write(value) : String.valueOf(value);
    }
}
