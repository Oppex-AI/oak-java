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

/**
 * Result of {@code POST /v1/tools/pair}.
 *
 * <p>A CREATE returns {@link PairStatus#PENDING_APPROVAL} with a {@code pairingId} (no secret — the
 * service already holds the pre-shared one). A later POLL returns {@link PairStatus#APPROVED} with a
 * {@code connectionId} and the dedicated {@code token} — the token is present only on the first approved
 * poll, so persist it at once — or a terminal status with neither.
 */
public record PairResponse(PairStatus status, String pairingId, String connectionId, String token) {
}
