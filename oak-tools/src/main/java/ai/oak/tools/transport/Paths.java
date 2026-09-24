package ai.oak.tools.transport;

/**
 * The Oppex endpoints this agent calls.
 *
 * <p>These are <strong>restated</strong> here rather than shared, because the SDK cannot depend on
 * Oppex's internal modules across the repository boundary — that separation is the whole point of
 * shipping a jar into someone else's process. The cost is that these constants can drift from the
 * server's own. Verified against {@code RemoteToolClient} on 2026-09-04. If they ever diverge, the
 * fix is to generate this client from the service's OpenAPI document, not to hand-sync harder.
 */
public final class Paths {

    public static final String BASE = "/v1/tools";
    public static final String REGISTER = BASE + "/register";
    public static final String CONNECT = BASE + "/connect";
    public static final String NEXT_STEP = BASE + "/steps/next";
    public static final String STEP_RESULT = BASE + "/steps/result";

    /** The header Oppex's AuthFilter resolves a client and workspace from. */
    public static final String API_KEY_HEADER = "X-API-KEY";

    /**
     * Carries the short-lived ticket on the WebSocket handshake.
     *
     * <p>A header rather than a query parameter, so the credential does not end up in proxy access
     * logs — which is the same reason the handshake trades the long-lived API key for a ticket in
     * the first place.
     */
    public static final String TICKET_HEADER = "X-OPPEX-TICKET";

    private Paths() {
    }
}
