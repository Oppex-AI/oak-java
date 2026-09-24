package ai.oppex.adk.tools.transport;

/**
 * A way of receiving steps from Oppex.
 *
 * <p>Both implementations are driven the same way and are interchangeable, which is what makes
 * degrading from WebSocket to polling a configuration change rather than a different code path.
 */
public interface Transport {

    /** Human-readable name for logs: "websocket" or "poll". */
    String name();

    /**
     * Receives steps until {@link #stop()} is called or the connection fails unrecoverably.
     * Blocking; run it on its own thread.
     *
     * @throws Exception if the transport cannot continue. The caller decides whether to retry,
     *                   fall back, or give up.
     */
    void run(StepHandler handler) throws Exception;

    /** Asks {@link #run} to return. Safe to call from another thread, and more than once. */
    void stop();
}
