package ai.oak.service.client;

import java.util.Map;

/**
 * Reports what happened when this service ran a {@link RemoteStep}. {@code workflowId} + {@code taskId}
 * identify the step; {@code output} is merged into the workflow context for later steps and analysis.
 */
public record RemoteStepResultRequest(Long workflowId, Long taskId, TaskStatus status,
                                      Map<String, Object> output, String errorMessage) {
}
