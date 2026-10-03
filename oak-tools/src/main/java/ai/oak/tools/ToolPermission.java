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

/**
 * What a tool does to the system it touches.
 *
 * <p>It gates the "confirm before running a risky step" decision — a {@link #DESTRUCTIVE} step (and,
 * by policy, {@link #WRITE}) is what a runbook pauses on for human confirmation. It is the tool
 * author's declared claim about their own tool, useful for display, planning and that gate, and
 * <b>never a security control on its own</b>.
 */
public enum ToolPermission {

    /** Observes only — describe, list, get, read a metric. Safe to run without confirmation. */
    READ,

    /** Changes state reversibly — start/stop an instance, scale, restart. */
    WRITE,

    /** Changes state irreversibly — delete, terminate, drop. Always confirm. */
    DESTRUCTIVE
}
