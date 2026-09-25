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
package ai.oak.tools.docker;

import ai.oak.tools.ToolRegistry;

/**
 * Registers the generic Docker tools into a {@link ToolRegistry}. Same shape as {@code AwsTools} —
 * one entry point a consumer (a platform layer, or a customer's tool service) calls to add the set.
 */
public final class DockerTools {

    private DockerTools() {
    }

    public static void registerAll(final ToolRegistry registry) {
        registry.register(new DockerPsTool()).register(new DockerLogsTool()).register(new DockerInspectTool())
                .register(new DockerRestartTool()).register(new DockerStopTool()).register(new DockerStartTool());
    }
}
