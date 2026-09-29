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
package ai.oak.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A plain, framework-free catalog of {@link Tool}s keyed by capability.
 *
 * <p><b>Last registration wins.</b> A layer (a platform, or a customer's) overrides a generic tool by
 * registering its own under the same capability — the layered-tools model, where the core does the
 * generic thing and a layer overrides only where its specifics matter. No CDI: a consumer builds one
 * of these once (register the generic tools, then its own) and holds it.
 */
public final class ToolRegistry {

    private final Map<String, Tool> byCapability = new LinkedHashMap<>();

    public ToolRegistry register(final Tool tool) {
        byCapability.put(tool.capability(), tool);
        return this;
    }

    public Optional<Tool> find(final String capability) {
        return Optional.ofNullable(byCapability.get(capability));
    }

    public boolean has(final String capability) {
        return byCapability.containsKey(capability);
    }

    /** Every registered tool, in registration order (later overrides replace in place). */
    public List<Tool> all() {
        return List.copyOf(byCapability.values());
    }

    /** The command a capability would run, or empty when no registered tool provides it. */
    public Optional<String> render(final String capability, final Map<String, Object> input) {
        return find(capability).map(tool -> tool.render(input));
    }

    /** Runs a capability and returns its outcome, or empty when no registered tool provides it. */
    public Optional<ToolResult> execute(final String capability, final Map<String, Object> input) {
        return find(capability).map(tool -> tool.execute(input));
    }

    /** Runs a capability with extra environment applied, or empty when no registered tool provides it. */
    public Optional<ToolResult> execute(final String capability, final Map<String, Object> input, final Map<String, String> env) {
        return find(capability).map(tool -> tool.execute(input, env));
    }
}
