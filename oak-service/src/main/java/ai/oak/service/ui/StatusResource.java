package ai.oak.service.ui;

import ai.oak.service.AgentStatus;
import ai.oak.service.config.PlatformConfig;
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

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public StatusView status() {
        final List<StepView> recent = status.getRecent().stream()
                .map(r -> new StepView(r.at(), r.workflowId(), r.taskId(), r.capability(), r.status(), r.note()))
                .toList();
        return new StatusView(
                status.isConnected(),
                config.platform().name(),
                config.platform().baseUrl().filter(u -> !u.isBlank()).orElse(null),
                config.service().name(),
                config.service().version(),
                config.platform().apiKey().filter(k -> !k.isBlank()).isPresent(),
                config.poll().interval().toString(),
                config.poll().workerThreads(),
                config.tools().awsEnabled(),
                config.tools().dockerEnabled(),
                status.getRegisteredAt(),
                status.getLastError(),
                status.getAdvertised(),
                recent);
    }

    /** Everything the page renders. The API key is represented only as {@code apiKeyConfigured}. */
    public record StatusView(boolean connected, String platformName, String baseUrl, String serviceName,
                             String serviceVersion, boolean apiKeyConfigured, String pollInterval, int workerThreads,
                             boolean awsEnabled, boolean dockerEnabled, Instant registeredAt, String lastError,
                             List<String> advertised, List<StepView> recent) {
    }

    /** One executed step for the recent-activity table. */
    public record StepView(Instant at, Long workflowId, Long taskId, String capability, String status, String note) {
    }
}
