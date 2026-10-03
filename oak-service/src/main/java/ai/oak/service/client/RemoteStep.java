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

import java.util.Map;

/**
 * One unit of work handed to this service: run {@code capability} with {@code input}, then report the
 * result against {@code workflowId} + {@code taskId}, echoing {@code executionId} unchanged. The incident
 * reference is carried so a tool can enrich its own logs; no other orchestration state crosses the
 * boundary. {@code executionId} is the platform's opaque correlation token for this execution — never
 * parsed or generated here, only carried back on the result.
 */
public record RemoteStep(Long workflowId, Long taskId, String capability, Map<String, Object> input, String referenceType,
        String referenceId, Integer sequenceOrder, String executionId) {
}
