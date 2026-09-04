package ai.oppex.adk.tools.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * What happened when a step ran, reported back over HTTPS.
 *
 * <p>Results go over HTTP rather than back down the WebSocket on purpose. If the socket drops
 * while a step is still running, a result sent as a socket frame is simply lost — whereas an HTTP
 * POST can be retried until it lands. Dispatch needs push; results need durability.
 *
 * <p>Both {@code workflowId} and {@code taskId} are sent because Oppex re-checks that the task
 * really belongs to that workflow before accepting the result.
 */
public class StepResult {

    private Long workflowId;
    private Long taskId;
    private StepStatus status;
    private Map<String, Object> output = new HashMap<>();
    private String errorMessage;

    public static StepResult success(RemoteStep step, Map<String, Object> output) {
        final StepResult result = new StepResult();
        result.workflowId = step.getWorkflowId();
        result.taskId = step.getTaskId();
        result.status = StepStatus.SUCCESS;
        result.output = output == null ? new HashMap<>() : output;
        return result;
    }

    public static StepResult failure(RemoteStep step, String errorMessage) {
        final StepResult result = new StepResult();
        result.workflowId = step.getWorkflowId();
        result.taskId = step.getTaskId();
        result.status = StepStatus.FAILED;
        result.errorMessage = errorMessage;
        return result;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
