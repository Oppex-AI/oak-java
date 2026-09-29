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

import java.util.List;
import java.util.Map;

/**
 * A generic, framework-free unit of work a runbook step can invoke.
 *
 * <p>The same implementation runs whether the step executes on the orchestration platform's infrastructure or a customer's
 * — that is the whole point of a shared tool core, and why placement (who runs it) is a deployment
 * concern the caller resolves, not something a Tool declares. Keep implementations dependency-free
 * (JDK only) so this module lifts into the open-source oak-java repo unchanged.
 *
 * <p>A Tool does two things from one definition: {@link #render} prints the command it would run — the
 * preview used to verify a generated runbook before anything happens — and {@link #execute} actually
 * runs it and reports the outcome. Both sides (platform and customer) call the same {@code execute},
 * so their execution is identical by construction. Most tools are command-line tools; extend
 * {@link CommandTool} to get both from a single command definition rather than implementing this
 * interface directly.
 */
public interface Tool {

    /** Stable capability id a runbook step references verbatim, e.g. {@code AWS_EC2_START_INSTANCES}. */
    String capability();

    /** What this tool does to its target — drives the confirmation gate for risky steps. */
    ToolPermission permission();

    /** One-line human description for the tool catalog and the runbook preview. */
    String description();

    /**
     * The input keys this tool reads from a step's input map. Used to validate a step and to tell the
     * planner what a step of this capability needs — the model's only source for what to supply.
     */
    List<String> inputKeys();

    /**
     * Build the exact command this step would run, from its resolved inputs. A print/preview only —
     * nothing executes. A required input that is absent renders as a {@code <placeholder>} so the
     * command still reads as a template.
     */
    String render(Map<String, Object> input);

    /**
     * Actually run the work and report its outcome. Unlike {@link #render}, this touches the target.
     *
     * <p>It does not throw for a command that fails (a non-zero exit, a missing binary): those are
     * outcomes carried in the {@link ToolResult} for the runbook to act on. It throws only for a
     * genuine failure to attempt the work — e.g. an input that cannot be used to form the command.
     */
    ToolResult execute(Map<String, Object> input);

    /**
     * Run with extra environment variables applied to the execution (e.g. an AWS profile/region, a docker
     * host — the per-deployment settings the caller holds). The default ignores them; command-line tools
     * ({@link CommandTool}) pass them to the process.
     */
    default ToolResult execute(Map<String, Object> input, Map<String, String> env) {
        return execute(input);
    }
}
