package ai.oak.service.client;

import ai.oak.tools.ToolPermission;

/**
 * One capability this service advertises at registration: the id a runbook step references, what it
 * does to its target, and a one-line description. {@code permission} serialises to its name
 * (READ/WRITE/DESTRUCTIVE), which matches the platform's enum.
 */
public record CapabilityDeclaration(String capability, ToolPermission permission, String description) {
}
