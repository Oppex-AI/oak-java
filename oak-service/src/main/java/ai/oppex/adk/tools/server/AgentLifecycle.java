package ai.oppex.adk.tools.server;

import ai.oppex.adk.tools.OppexAgent;
import ai.oppex.adk.tools.ToolRegistry;
import ai.oppex.adk.tools.capabilities.FindAsgCapability;
import ai.oppex.adk.tools.config.AgentConfig;
import ai.oppex.adk.tools.config.CredentialStore;
import ai.oppex.adk.tools.config.TransportMode;
import ai.oppex.adk.tools.config.VendorConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import ai.oppex.adk.tools.Capability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the agent when the service boots and stops it cleanly on shutdown.
 *
 * <p><strong>To add your own tool:</strong> make it a CDI bean implementing
 * {@link Capability} and it is picked up here automatically — no change to this class.
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class CountOrders implements Capability { ... }
 * }</pre>
 *
 * <p>Beans are registered after the built-ins, so naming yours {@code FIND_ASG} overrides ours.
 */
@ApplicationScoped
public class AgentLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentLifecycle.class);

    @Inject
    AgentSettings settings;

    /** Every Capability bean in the application, yours included. */
    @Inject
    Instance<Capability> discovered;

    private OppexAgent agent;

    void onStart(@Observes StartupEvent event) {
        final ToolRegistry registry = new ToolRegistry();

        if (settings.awsCapabilitiesEnabled()) {
            // registerIfAvailable, not register: the AWS SDK is optional in the core, and a
            // NoClassDefFoundError here would stop the service booting over a capability the
            // operator may not even want.
            registry.registerIfAvailable(FindAsgCapability.NAME, FindAsgCapability::new);
        }

        int injected = 0;
        for (Capability capability : discovered) {
            registry.register(capability);
            injected++;
        }
        log.info("Capabilities: {} total ({} from CDI beans, AWS built-ins {})",
                registry.size(), injected, settings.awsCapabilitiesEnabled() ? "enabled" : "disabled");

        final AgentConfig config = new AgentConfig()
                .setVendor(new VendorConfig("oppex", settings.baseUrl(), resolveApiKey()))
                .setAgentName(settings.agentName())
                .setTransportMode(TransportMode.valueOf(settings.transportMode().toUpperCase()))
                .setPollInterval(settings.pollInterval())
                .setWorkerThreads(settings.workerThreads());

        agent = new OppexAgent(config, registry);
        agent.start();
        log.info("Agent started against {} as '{}'", settings.baseUrl(), settings.agentName());
    }

    void onStop(@Observes ShutdownEvent event) {
        if (agent != null) {
            agent.close();
        }
    }

    /**
     * Prefers the encrypted credentials file over a configured key.
     *
     * <p>Fails at startup rather than at the first request if neither is present. An agent that
     * boots healthy and only reveals it has no credential when an incident fires is the worst
     * possible time to find out.
     */
    private String resolveApiKey() {
        if (settings.credentialsFile().isPresent()) {
            final Path path = Path.of(settings.credentialsFile().get());
            try {
                return CredentialStore.load(path, CredentialStore.passphraseFromEnv());
            } catch (IOException e) {
                throw new IllegalStateException("Could not read the credentials file " + path, e);
            }
        }
        return settings.apiKey().filter(key -> !key.isBlank()).orElseThrow(() -> new IllegalStateException(
                "No credential configured. Set oppex.adk.credentials-file (preferred) "
                        + "or oppex.adk.api-key."));
    }
}
