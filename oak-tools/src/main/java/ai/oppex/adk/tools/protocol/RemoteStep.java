package ai.oppex.adk.tools.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * One step Oppex is asking this agent to run.
 *
 * <p><strong>This carries no code.</strong> {@code capability} is a name and {@code input} is a map
 * of parameters — nothing executable ever crosses the connection. The agent looks the name up in
 * its own {@link ai.oppex.adk.tools.ToolRegistry} and runs its own implementation; a name it does
 * not implement is refused. That is the whole security model, and it holds even if the platform
 * sending the step were compromised: the worst it could ask for is a capability you built, with
 * parameters you can validate.
 *
 * <p>{@code taskId} is the correlation id. Steps may arrive while earlier ones are still running
 * and results may be reported in any order, so never assume the order of arrival means anything.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteStep {

    private Long workflowId;
    private Long taskId;
    private String capability;
    private Map<String, Object> input = new HashMap<>();
    private String referenceType;
    private String referenceId;
    private Integer sequenceOrder;

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

    public String getCapability() {
        return capability;
    }

    public void setCapability(String capability) {
        this.capability = capability;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input == null ? new HashMap<>() : input;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public Integer getSequenceOrder() {
        return sequenceOrder;
    }

    public void setSequenceOrder(Integer sequenceOrder) {
        this.sequenceOrder = sequenceOrder;
    }

    @Override
    public String toString() {
        return "RemoteStep{workflowId=" + workflowId + ", taskId=" + taskId
                + ", capability='" + capability + "', inputKeys=" + input.keySet() + '}';
    }
}
