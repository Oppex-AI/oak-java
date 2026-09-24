package ai.oak.tools;

/**
 * What a tool does to the system it touches.
 *
 * <p>It gates the "confirm before running a risky step" decision — a {@link #DESTRUCTIVE} step (and,
 * by policy, {@link #WRITE}) is what a runbook pauses on for human confirmation. It is the tool
 * author's declared claim about their own tool, useful for display, planning and that gate, and
 * <b>never a security control on its own</b>.
 */
public enum ToolPermission {

    /** Observes only — describe, list, get, read a metric. Safe to run without confirmation. */
    READ,

    /** Changes state reversibly — start/stop an instance, scale, restart. */
    WRITE,

    /** Changes state irreversibly — delete, terminate, drop. Always confirm. */
    DESTRUCTIVE
}
