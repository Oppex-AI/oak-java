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
package ai.oak.service.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The generic result envelope for any tool, reported after this service ran a {@link RemoteStep}. One shape
 * for every tool — no per-tool response types, no stdout-as-result.
 *
 * <p>{@code workflowId} + {@code taskId} identify the step and {@code executionId} is the platform's opaque
 * correlation token, echoed unchanged from the step. {@code success} says whether it worked; {@code output}
 * is the tool's <b>structured</b> result (an empty map for a no-result action) merged into the workflow
 * context. {@code error} is present only when {@code success} is false and carries a machine code + message;
 * it is omitted otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemoteStepResultRequest(Long workflowId, Long taskId, String executionId, boolean success,
        Map<String, Object> output, StepError error) {
}
