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

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * The state of a pairing attempt, as the platform reports it from {@code POST /v1/tools/pair}.
 *
 * <p>{@link #APPROVED} is the one terminal-success state (it carries the dedicated token). The rest are
 * either "keep waiting" ({@link #PENDING_APPROVAL}) or "give up this attempt and start a fresh pairing"
 * ({@link #EXPIRED}, {@link #REJECTED}, {@link #DISCONNECTED}, {@link #UNKNOWN}). An unrecognised wire
 * value is treated as {@link #UNKNOWN} so a new server status can never strand the client.
 */
public enum PairStatus {
    PENDING_APPROVAL, APPROVED, EXPIRED, REJECTED, DISCONNECTED,

    @JsonEnumDefaultValue
    UNKNOWN
}
