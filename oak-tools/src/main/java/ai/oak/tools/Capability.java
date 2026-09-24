package ai.oak.tools;

import java.util.Map;

/**
 * A single thing this agent can do. Implement this to add your own tool.
 *
 * <p>Minimal example:
 *
 * <pre>{@code
 * public class CountOrders implements Capability {
 *     public String name() { return "COUNT_PENDING_ORDERS"; }
 *     public ToolPermission permission() { return ToolPermission.READ; }
 *
 *     public Map<String, Object> execute(Map<String, Object> input) throws Exception {
 *         try (var conn = dataSource.getConnection();
 *              var st = conn.prepareStatement("select count(*) from orders where status = ?")) {
 *             st.setString(1, (String) input.get("status"));
 *             var rs = st.executeQuery();
 *             rs.next();
 *             return Map.of("count", rs.getInt(1));
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>Then {@code registry.register(new CountOrders())} and reference {@code COUNT_PENDING_ORDERS}
 * from a runbook step.
 *
 * <h2>Things worth knowing before you write one</h2>
 *
 * <ul>
 *   <li><strong>Validate the input.</strong> It arrives from outside your network. Treat it as you
 *       would an HTTP request body — never interpolate a value straight into SQL or a shell
 *       command.
 *   <li><strong>Throwing is fine and is the right way to fail.</strong> The exception is caught,
 *       reported to Oppex as a FAILED step with its message, and the runbook decides what to do.
 *       Do not swallow an error and return a "successful" empty result: a step that lies about
 *       succeeding is worse than one that fails, because the runbook carries on regardless.
 *   <li><strong>Return facts, not prose.</strong> The output map is read by the next step and by
 *       an LLM. {@code {"asgName": "payment-asg", "instanceCount": 4}} is useful;
 *       {@code {"summary": "found the ASG, looks fine"}} is not.
 *   <li><strong>Assume it can run more than once.</strong> Steps can be retried. A READ capability
 *       gets this for free; a WRITE one should be written so a second run is harmless.
 *   <li><strong>Do not block forever.</strong> Several steps may run at once on a shared pool. Set
 *       timeouts on whatever you call.
 * </ul>
 */
public interface Capability {

    /**
     * The name a runbook step uses to ask for this. Conventionally SCREAMING_SNAKE_CASE.
     *
     * <p>Names are free strings rather than an Oppex-owned list, precisely so you can add tools we
     * have never heard of. Nothing stops you naming one {@code FIND_ASG} and implementing it
     * differently from our built-in — yours wins, because registration is last-one-in for a given
     * name and your registry is the only place a name is resolved.
     */
    String name();

    /** How much damage this could do. See {@link ToolPermission} for what this does and does not buy you. */
    ToolPermission permission();

    /**
     * Do the work.
     *
     * @param input parameters from the runbook step. Never null; may be empty.
     * @return facts for the next step and for the incident record. Never null; may be empty.
     * @throws Exception to fail the step. The message reaches Oppex, so make it diagnostic.
     */
    Map<String, Object> execute(Map<String, Object> input) throws Exception;
}
