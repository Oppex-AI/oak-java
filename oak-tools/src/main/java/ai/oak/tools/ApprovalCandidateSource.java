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

/**
 * For a <em>select-then-approve</em> DESTRUCTIVE capability: declares where the single target the
 * operator approves comes from, and what to inject once they pick one. The orchestration platform uses
 * this to auto-wire human approval — show the candidates a preceding READ step listed, let a human pick
 * one, inject the chosen target back into this capability's input — with no per-runbook authoring.
 *
 * <p>Generic by design (nothing platform-specific). A capability that is "approve-and-run" (its target
 * is already fully in its input) advertises nothing and returns {@code null} from
 * {@link Tool#approvalCandidateSource()}.
 *
 * @param fromCapability the READ capability that produces the candidate list.
 * @param candidatesPath the key in that capability's output holding the candidate array.
 * @param identityField  the key on each candidate whose value is the opaque target to inject back.
 * @param idField        the stable id inside {@code identityField} the human selects by (must be unique
 *                       within one result).
 * @param inputField     the key on THIS capability's own input to set with the chosen identity.
 * @param displayFields  top-level candidate fields to show the approver.
 * @param redactFields   subset of {@code displayFields} that are SQL/free text — mask literals first.
 */
public record ApprovalCandidateSource(String fromCapability, String candidatesPath, String identityField, String idField,
        String inputField, List<String> displayFields, List<String> redactFields) {
}
