/*
 * Copyright 2026 Oak Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.oak.service.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything this service reads from configuration, under the {@code oak} prefix.
 *
 * <p>A platform is a <b>configured backend</b>, never a hardcoded one, and there can be more than one:
 * {@code oak.platforms.<name>.*} defines each. Oppex is simply the first — anything that implements the
 * same {@code /v1/tools} pairing + step protocol can be added under another name with no code change,
 * and the same handshake runs for every one of them.
 *
 * <p>Per-tool credentials (an AWS profile, the docker socket) are not here — the {@code aws} and
 * {@code docker} CLIs read those from their own environment where this service runs.
 */
@ConfigMapping(prefix = "oak")
public interface OakConfig {

    /** The backends this service connects to, keyed by name (e.g. {@code oppex}). May be empty. */
    Map<String, Platform> platforms();

    /** Where encrypted connection state is persisted. Default {@code ~/.oak}. */
    Optional<String> dataDir();

    Service service();

    Poll poll();

    Tools tools();

    Discovery discovery();

    /** One remote orchestration platform this service pairs with and pulls steps from. */
    interface Platform {

        /**
         * Base URL, e.g. {@code https://api.oppex.example}. The {@code /v1/tools} path is appended. Optional
         * so a platform slot can be declared empty and filled later from the environment or the UI; while
         * absent the connection stays idle.
         */
        Optional<String> baseUrl();

        /**
         * Workspace client id (e.g. {@code oak_0cd990…}), shown on the platform's OAK/Tools page. Its
         * presence, with {@link #pairingSecret()}, turns on approval-based pairing.
         */
        Optional<String> clientId();

        /**
         * The pre-shared, long-lived pairing secret the admin issued alongside the client id. Sent on every
         * pairing call and verified by the platform; the service proves its identity with it. Rotate only
         * when the admin regenerates it. Distinct from the runtime API token, and never sent on register/steps.
         */
        Optional<String> pairingSecret();
    }

    /** How this service identifies itself at registration. */
    interface Service {

        /** Reported at registration and used to key it; keep it stable across restarts. */
        @WithDefault("oak-service")
        String name();

        @WithDefault("0.1.0")
        String version();
    }

    /** The poll loop that pulls the next step. */
    interface Poll {

        @WithDefault("10s")
        Duration interval();

        /** How many steps may run at once, across all platforms. */
        @WithDefault("4")
        int workerThreads();
    }

    /** Which built-in tool sets to advertise. Turn a set off to advertise only your own tools. */
    interface Tools {

        @WithDefault("true")
        boolean awsEnabled();

        @WithDefault("true")
        boolean dockerEnabled();
    }

    /**
     * Infrastructure discovery: enumerate what exists (facts only) so the platform can decide what to do.
     * The collector is fact-gathering; the periodic reporting loop to the platform is wired once the
     * platform's discovery endpoint contract is finalised.
     */
    interface Discovery {

        /** Whether discovery collection is available. */
        @WithDefault("true")
        boolean enabled();

        /** How often to refresh the snapshot once reporting is wired. */
        @WithDefault("5m")
        Duration interval();

        /** Regions to scan. Empty falls back to the AWS setting's region, else one unspecified scan. */
        Optional<List<String>> regions();

        /** Which resource types to include. Extend as collectors are added (ElastiCache, MSK, ECS, ...). */
        @WithDefault("EC2_INSTANCE,RDS_INSTANCE,DOCKER_CONTAINER")
        List<String> resourceTypes();
    }
}
