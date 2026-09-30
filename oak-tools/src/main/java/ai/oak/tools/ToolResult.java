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

import java.util.Map;

/**
 * The outcome of actually running a {@link Tool} — the counterpart to {@link Tool#render} once a step
 * really executes.
 *
 * <p>It carries what a runbook needs to decide what happens next and to record what happened: the
 * process exit code, and everything the command wrote to stdout and stderr. It deliberately does not
 * interpret the output — the next step and the incident record read the raw text — and it does not
 * throw for a non-zero exit: a command that fails is a normal, reportable outcome, not an error in
 * the machinery that ran it.
 *
 * @param exitCode the process exit status; {@code 0} conventionally means success. {@code -1} is used
 *                 when the command could not be run at all (not found) or was killed on timeout.
 * @param stdout   everything the command wrote to standard output, verbatim.
 * @param stderr   everything the command wrote to standard error, verbatim; also carries the reason
 *                 when {@code exitCode} is {@code -1}.
 * @param timedOut true when the command was killed for exceeding its time budget.
 * @param data     optional extra structured facts about the run that are not the command's own output —
 *                 e.g. an SSM {@code ssmCommandId} and invocation {@code status} for a step executed on a
 *                 remote host. Empty for an ordinary local command. Reported verbatim alongside the output.
 */
public record ToolResult(int exitCode, String stdout, String stderr, boolean timedOut, Map<String, Object> data) {

    public ToolResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** An ordinary command result with no extra structured data. */
    public ToolResult(final int exitCode, final String stdout, final String stderr, final boolean timedOut) {
        this(exitCode, stdout, stderr, timedOut, Map.of());
    }

    /** True when the command ran to completion with a zero exit status. */
    public boolean success() {
        return !timedOut && exitCode == 0;
    }
}
