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
 * The platform's acknowledgement of a reported snapshot (the {@code data} of the APIResponse). Purely
 * informational for this service — it only needs the call to succeed; this is handy for logging whether
 * the snapshot changed. The platform owns change-detection: {@code changed} is false when the snapshot
 * hashed equal to the previous one (normalised resources only; {@code discoveredAt} is excluded).
 *
 * @param changed       whether this snapshot differed from the last one recorded.
 * @param resourceCount how many resources the platform recorded.
 * @param discoveredAt  the timestamp the platform stored (echoed or server-stamped).
 */
public record DiscoverySnapshotResponse(boolean changed, int resourceCount, String discoveredAt) {
}
