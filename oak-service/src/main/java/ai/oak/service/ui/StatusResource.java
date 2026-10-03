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
import ai.oak.service.config.OakConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Read-only JSON behind the management page: per-platform connection phase (incl. "waiting for admin
 * approval"), what this service advertises, and the steps it has run. Never exposes a secret — only
 * whether a token is present, and the client id masked.
 */
@Path("/api")
public class StatusResource {

    @Inject
    AgentStatus status;

    @Inject
    OakConfig config;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public StatusView status() {
        final List<StepView> recent = status.getRecent().stream().map(r -> new StepView(r.at() == null ? null : r.at().toString(),
                r.platform(), r.workflowId(), r.taskId(), r.capability(), r.status(), r.note())).toList();
        final List<PlatformView> platforms = status.getConnections().stream()
                .map(c -> new PlatformView(c.platform(), c.phase().name(), c.detail(), c.baseUrl(), c.clientIdMasked(),
                        c.tokenPresent(), c.connectionId(), c.since() == null ? null : c.since().toString(), c.lastError()))
                .toList();
        return new StatusView(config.service().name(), config.service().version(), config.poll().interval().toString(),
                config.poll().workerThreads(), config.tools().awsEnabled(), config.tools().dockerEnabled(),
                status.getAdvertised(), platforms, recent);
    }

    public record StatusView(String serviceName, String serviceVersion, String pollInterval, int workerThreads,
            boolean awsEnabled, boolean dockerEnabled, List<String> advertised, List<PlatformView> platforms,
            List<StepView> recent) {
    }

    public record PlatformView(String name, String phase, String detail, String baseUrl, String clientIdMasked,
            boolean tokenPresent, String connectionId, String since, String lastError) {
    }

    public record StepView(String at, String platform, Long workflowId, Long taskId, String capability, String status,
            String note) {
    }
}
