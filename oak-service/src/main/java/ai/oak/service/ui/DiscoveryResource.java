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
package ai.oak.service.ui;

import ai.oak.service.AgentStatus;
import ai.oak.service.DiscoveryCollector;
import ai.oak.service.ToolCatalog;
import ai.oak.service.config.OakConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A local, on-demand preview of the infrastructure snapshot the discovery collector produces — so an
 * operator can see exactly what this service would report (facts only), without waiting for or depending
 * on the platform. Read-only and purely local: it runs the same collector but does not send anything.
 */
@Path("/api")
public class DiscoveryResource {

    /** A representative AWS read capability whose active-state gates whether discovery can enumerate. */
    private static final String AWS_PROBE = "AWS_EC2_DESCRIBE_INSTANCES";

    @Inject
    DiscoveryCollector collector;

    @Inject
    ToolCatalog catalog;

    @Inject
    OakConfig config;

    @Inject
    AgentStatus status;

    @GET
    @Path("/discovery")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> discovery() {
        final Map<String, Object> out = new LinkedHashMap<>();
        if (!config.discovery().enabled()) {
            out.put("ok", false);
            out.put("error", "discovery is disabled (oak.discovery.enabled=false)");
            return out;
        }
        if (!catalog.isActive(AWS_PROBE)) {
            out.put("ok", false);
            out.put("error", catalog.inactiveReason(AWS_PROBE));
            return out;
        }
        out.put("ok", true);
        out.put("snapshot", collector.collect());
        return out;
    }

    /**
     * What discovery actually reported to each platform last — the resources OAK posted, not a fresh scan.
     * This is the "what has it discovered and sent" view; empty until the first snapshot has been reported.
     */
    @GET
    @Path("/discovery/last")
    @Produces(MediaType.APPLICATION_JSON)
    public List<AgentStatus.DiscoveryReport> last() {
        return status.getDiscovery();
    }
}
