package ai.oppex.adk.tools.server;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/** Everything this service reads from configuration, under the {@code oppex.adk} prefix. */
@ConfigMapping(prefix = "oppex.adk")
public interface AgentSettings {

    /** Base URL of the Oppex API, e.g. {@code https://api.oppex.example}. */
    String baseUrl();

    /**
     * The API key, if you are supplying it directly.
     *
     * <p>Prefer {@link #credentialsFile()}: a key given here arrives through an environment
     * variable or a properties file, and both tend to end up somewhere they should not — a process
     * listing, a diagnostic bundle, a config file in version control.
     */
    Optional<String> apiKey();

    /**
     * Path to a credentials file written by {@code CredentialStore}. Decrypted at startup using the
     * passphrase in {@code OPPEX_ADK_PASSPHRASE}.
     */
    Optional<String> credentialsFile();

    /**
     * Name reported to Oppex. Keep it stable across restarts — Oppex keys the registration on it,
     * so a name that changes every boot leaves dead registrations behind.
     */
    @WithDefault("oppex-adk-tools-server")
    String agentName();

    /** AUTO (default), WEBSOCKET, or POLL. */
    @WithDefault("AUTO")
    String transportMode();

    @WithDefault("10s")
    Duration pollInterval();

    @WithDefault("4")
    int workerThreads();

    /** Register the built-in AWS capabilities. Turn off if you only want your own tools. */
    @WithDefault("true")
    boolean awsCapabilitiesEnabled();
}
