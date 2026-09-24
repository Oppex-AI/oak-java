package ai.oak.service.client;

import java.util.List;

/**
 * Sent on start-up: who this service is and what it can run. The declared set replaces the previous
 * one wholesale, so a tool dropped from this build disappears from the platform's view of what this
 * service can do. The caller is identified by its API key, not by {@code name}.
 */
public record ToolServiceRegistrationRequest(String name, String version, List<CapabilityDeclaration> capabilities) {
}
