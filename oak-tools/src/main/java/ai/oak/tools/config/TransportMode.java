package ai.oak.tools.config;

/** Which transport to use to receive steps. */
public enum TransportMode {

    /**
     * Try the WebSocket; fall back to polling if the upgrade cannot be established. The default,
     * and what you want unless you are debugging.
     */
    AUTO,

    /** WebSocket only. Fail loudly rather than degrading — useful in a test, wrong in production. */
    WEBSOCKET,

    /** Polling only. For an environment whose proxy refuses the upgrade, or to rule the socket out. */
    POLL
}
