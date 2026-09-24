package ai.oak.service.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/**
 * Everything this service reads from configuration, under the {@code oak} prefix.
 *
 * <p>The platform is a <b>configured backend</b>, not a hardcoded one: {@code oak.platform.name} and
 * {@code base-url} decide who this service talks to. Today that is Oppex; the client itself is not
 * tied to it. Per-tool credentials (an AWS profile, the docker socket) are not here — the {@code aws}
 * and {@code docker} CLIs read those from their own environment where this service runs.
 */
@ConfigMapping(prefix = "oak")
public interface PlatformConfig {

    Platform platform();

    Service service();

    Poll poll();

    Tools tools();

    /** The remote orchestration platform this service registers with and pulls steps from. */
    interface Platform {

        /** Which backend this is, for logs and the UI. Configuration, not code. */
        @WithDefault("oppex")
        String name();

        /**
         * Base URL of the platform, e.g. {@code https://api.oppex.example}. The {@code /v1/tools} path
         * is appended by the client. Absent means "not configured" — the executor stays idle and says so
         * rather than failing the whole service to boot.
         */
        Optional<String> baseUrl();

        /** The workspace API key for this tool service. Sent as {@code X-API-KEY}. */
        Optional<String> apiKey();
    }

    /** How this service identifies itself at registration. */
    interface Service {

        /** Reported to the platform and used to key the registration. Keep it stable across restarts. */
        @WithDefault("oak-service")
        String name();

        @WithDefault("0.1.0")
        String version();
    }

    /** The poll loop that pulls the next step. */
    interface Poll {

        @WithDefault("10s")
        Duration interval();

        /** How many steps may run at once. Steps within one runbook are sequential upstream. */
        @WithDefault("4")
        int workerThreads();
    }

    /** Which built-in tool sets to advertise. Turn a set off if you only want your own tools. */
    interface Tools {

        @WithDefault("true")
        boolean awsEnabled();

        @WithDefault("true")
        boolean dockerEnabled();
    }
}
