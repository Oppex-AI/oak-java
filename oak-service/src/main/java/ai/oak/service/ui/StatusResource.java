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
package ai.oak.service.ui;

import ai.oak.service.AgentStatus;
import ai.oak.service.config.PlatformConfig;
import ai.oak.service.config.RuntimeSettings;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;

/**
 * Read-only JSON behind the management page: is this executor healthy, what did it advertise, and what
 * has it run. Answers "is my executor healthy and what has it run" without reaching back to the platform.
 *
 * <p>It never exposes the API key — only whether one is configured. The base URL and tool credentials
 * live in the process's own config, not here.
 */
@Path("/api")
public class StatusResource {

    @Inject
    AgentStatus status;

    @Inject
    PlatformConfig config;

    @Inject
    RuntimeSettings settings;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public StatusView status() {
        final List<StepView> recent = status.getRecent().stream()
                .map(r -> new StepView(r.at(), r.workflowId(), r.taskId(), r.capability(), r.status(), r.note())).toList();
        return new StatusView(status.isConnected(), config.platform().name(), settings.baseUrl(), config.service().name(),
                config.service().version(), settings.apiKeyConfigured(), config.poll().interval().toString(),
                config.poll().workerThreads(), config.tools().awsEnabled(), config.tools().dockerEnabled(),
                status.getRegisteredAt(), status.getLastError(), status.getAdvertised(), recent);
    }

    /** Everything the page renders. The API key is represented only as {@code apiKeyConfigured}. */
    public record StatusView(boolean connected, String platformName, String baseUrl, String serviceName, String serviceVersion,
            boolean apiKeyConfigured, String pollInterval, int workerThreads, boolean awsEnabled, boolean dockerEnabled,
            Instant registeredAt, String lastError, List<String> advertised, List<StepView> recent) {
    }

    /** One executed step for the recent-activity table. */
    public record StepView(Instant at, Long workflowId, Long taskId, String capability, String status, String note) {
    }
}
