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

import ai.oak.tools.ApprovalCandidateSource;
import ai.oak.tools.ToolPermission;
import java.util.List;

/**
 * One capability this service advertises at registration: the id a runbook step references, what it
 * does to its target, a one-line description, and the input keys its tool reads. {@code permission}
 * serialises to its name (READ/WRITE/DESTRUCTIVE), which matches the platform's enum.
 *
 * <p>{@code inputKeys} is the tool's own input contract (e.g. {@code container} for DOCKER_RESTART,
 * {@code instanceIds} for EC2). The platform's planner emits exactly these keys instead of guessing
 * them — which is what stops the class of "tool got the wrong parameter name" failures.
 *
 * <p>{@code approvalCandidateSource} is set only for a select-then-approve DESTRUCTIVE capability (e.g.
 * DB_TERMINATE picks one backend from DB_LIST_ACTIVITY); it is null otherwise and omitted from the wire.
 */
public record CapabilityDeclaration(String capability, ToolPermission permission, String description, List<String> inputKeys,
        ApprovalCandidateSource approvalCandidateSource) {
}
