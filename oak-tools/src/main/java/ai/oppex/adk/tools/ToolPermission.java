package ai.oppex.adk.tools;

/**
 * How much damage a capability could do, declared by whoever wrote it.
 *
 * <p>This is <strong>your</strong> claim about <strong>your own</strong> tool. Oppex records it and
 * uses it for display and planning; it is not a security control, and nothing on the Oppex side can
 * verify it. A capability declared READ that deletes a database will delete the database. The
 * boundary that actually protects you is the IAM role, database grant, or credential you give this
 * process — not this enum.
 */
public enum ToolPermission {

    /** Reads state. Safe to run repeatedly, safe to run during an incident. */
    READ,

    /** Changes state, reversibly. Scaling a group, restarting a process. */
    WRITE,

    /** Changes state in a way that cannot be undone. Deleting, terminating, dropping. */
    DESTRUCTIVE
}
