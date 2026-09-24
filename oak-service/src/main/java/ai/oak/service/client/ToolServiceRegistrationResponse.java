package ai.oak.service.client;

import java.time.Instant;
import java.util.List;

/** What the platform confirms it now believes this service can execute. */
public record ToolServiceRegistrationResponse(Long id, String name, String version, Instant lastSeenAt,
                                              List<String> capabilities) {
}
