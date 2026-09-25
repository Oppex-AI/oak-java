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

import java.util.List;

/**
 * Sent on start-up: who this service is and what it can run. The declared set replaces the previous
 * one wholesale, so a tool dropped from this build disappears from the platform's view of what this
 * service can do. The caller is identified by its API key, not by {@code name}.
 */
public record ToolServiceRegistrationRequest(String name, String version, List<CapabilityDeclaration> capabilities) {
}
