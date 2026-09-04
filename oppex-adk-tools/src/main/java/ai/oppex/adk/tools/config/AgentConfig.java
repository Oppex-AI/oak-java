package ai.oppex.adk.tools.config;

import java.time.Duration;

/**
 * How this agent behaves. Every value has a working default except the vendor.
 */
public class AgentConfig {

    private VendorConfig vendor;

    /**
     * Reported at registration and used by Oppex to key the registration, together with the
     * workspace. Keep it <strong>stable across restarts</strong> — a name containing a hostname is
     * fine, one containing a random id or a pod suffix means every restart looks like a new agent
     * and old registrations pile up looking dead.
     */
    private String agentName = defaultAgentName();

    private String agentVersion = "0.1.0";

    private TransportMode transportMode = TransportMode.AUTO;

    /** Only used when polling. Short enough to feel responsive, long enough not to be noise. */
    private Duration pollInterval = Duration.ofSeconds(10);

    /** How many steps may run at once. Steps from different incidents can arrive together. */
    private int workerThreads = 4;

    /**
     * How long to wait before re-dialling after the connection drops. Doubles up to
     * {@link #maxReconnectDelay}, so a platform outage is not made worse by every agent in every
     * customer estate retrying in a tight loop.
     */
    private Duration reconnectDelay = Duration.ofSeconds(2);

    private Duration maxReconnectDelay = Duration.ofMinutes(2);

    private static String defaultAgentName() {
        final String host = System.getenv("HOSTNAME");
        return (host == null || host.isBlank()) ? "oppex-adk-tools" : "oppex-adk-tools@" + host;
    }

    public VendorConfig getVendor() {
        return vendor;
    }

    public AgentConfig setVendor(VendorConfig vendor) {
        this.vendor = vendor;
        return this;
    }

    public String getAgentName() {
        return agentName;
    }

    public AgentConfig setAgentName(String agentName) {
        this.agentName = agentName;
        return this;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public AgentConfig setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
        return this;
    }

    public TransportMode getTransportMode() {
        return transportMode;
    }

    public AgentConfig setTransportMode(TransportMode transportMode) {
        this.transportMode = transportMode;
        return this;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public AgentConfig setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
        return this;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public AgentConfig setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }

    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    public AgentConfig setReconnectDelay(Duration reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
        return this;
    }

    public Duration getMaxReconnectDelay() {
        return maxReconnectDelay;
    }

    public AgentConfig setMaxReconnectDelay(Duration maxReconnectDelay) {
        this.maxReconnectDelay = maxReconnectDelay;
        return this;
    }
}
