package ai.oak.service.client;

import java.util.Map;

/**
 * One unit of work handed to this service: run {@code capability} with {@code input}, then report the
 * result against {@code workflowId} + {@code taskId}. The incident reference is carried so a tool can
 * enrich its own logs; no other orchestration state crosses the boundary.
 */
public record RemoteStep(Long workflowId, Long taskId, String capability, Map<String, Object> input,
                         String referenceType, String referenceId, Integer sequenceOrder) {
}
