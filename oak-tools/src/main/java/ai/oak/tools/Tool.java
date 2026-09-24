package ai.oak.tools;

import java.util.List;
import java.util.Map;

/**
 * A generic, framework-free unit of work a runbook step can invoke.
 *
 * <p>The same implementation runs whether the step executes on the orchestration platform's infrastructure or a customer's
 * — that is the whole point of a shared tool core, and why placement (who runs it) is a deployment
 * concern the caller resolves, not something a Tool declares. Keep implementations dependency-free
 * (JDK only) so this module lifts into the open-source oak-java repo unchanged.
 *
 * <p>Today a Tool only <b>renders</b> the command it would run — the print/preview used to verify a
 * generated runbook before anything executes. A real {@code execute(...)} is layered on later without
 * changing this contract or a step's plan.
 */
public interface Tool {

    /** Stable capability id a runbook step references verbatim, e.g. {@code AWS_EC2_START_INSTANCES}. */
    String capability();

    /** What this tool does to its target — drives the confirmation gate for risky steps. */
    ToolPermission permission();

    /** One-line human description for the tool catalog and the runbook preview. */
    String description();

    /**
     * The input keys this tool reads from a step's input map. Used to validate a step and to tell the
     * planner what a step of this capability needs — the model's only source for what to supply.
     */
    List<String> inputKeys();

    /**
     * Build the exact command this step would run, from its resolved inputs. A print/preview only —
     * nothing executes. A required input that is absent renders as a {@code <placeholder>} so the
     * command still reads as a template.
     */
    String render(Map<String, Object> input);
}
