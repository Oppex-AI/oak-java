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
package ai.oak.service;

import ai.oak.service.config.OakConfig;
import ai.oak.tools.Tool;
import ai.oak.tools.ToolRegistry;
import ai.oak.tools.aws.AwsTools;
import ai.oak.tools.docker.DockerTools;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@link ToolRegistry} once (the built-in AWS/Docker sets per config, plus any customer
 * {@link Tool} CDI beans) and exposes it — to the executor for running steps, and to the UI for
 * listing what this service can do. One place owns the registry so both see exactly the same tools.
 */
@ApplicationScoped
public class ToolCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCatalog.class);

    @Inject
    OakConfig config;

    /** Any customer-supplied {@link Tool} CDI beans, registered after the built-ins so theirs win. */
    @Inject
    Instance<Tool> customTools;

    private ToolRegistry registry;

    /** One tool, described for the management UI. {@code preview} is {@link Tool#render} with no input. */
    public record ToolInfo(String group, String service, String capability, String permission, String description,
            List<String> inputKeys, String preview) {
    }

    @PostConstruct
    void build() {
        final ToolRegistry built = new ToolRegistry();
        if (config.tools().awsEnabled()) {
            AwsTools.registerAll(built);
        }
        if (config.tools().dockerEnabled()) {
            DockerTools.registerAll(built);
        }
        int custom = 0;
        for (final Tool tool : customTools) {
            built.register(tool);
            custom++;
        }
        registry = built;
        LOG.info("Tools: {} total ({} built-in set(s), {} custom bean(s))", built.all().size(),
                (config.tools().awsEnabled() ? 1 : 0) + (config.tools().dockerEnabled() ? 1 : 0), custom);
    }

    public ToolRegistry registry() {
        return registry;
    }

    /** The catalog for the UI: every tool with its group/service and a render preview. */
    public List<ToolInfo> list() {
        return registry.all().stream().map(t -> new ToolInfo(groupOf(t.capability()), serviceOf(t.capability()), t.capability(),
                t.permission().name(), t.description(), t.inputKeys(), preview(t))).toList();
    }

    /** The provider group a capability belongs to (AWS / Docker / Other), from its id prefix. */
    public static String groupOf(final String capability) {
        if (capability.startsWith("AWS_")) {
            return "AWS";
        }
        if (capability.startsWith("DOCKER_")) {
            return "Docker";
        }
        return "Other";
    }

    /** For AWS, the service segment (EC2, RDS, S3, …); otherwise "General". */
    private static String serviceOf(final String capability) {
        if (capability.startsWith("AWS_")) {
            final String[] parts = capability.split("_");
            return parts.length > 1 ? parts[1] : "General";
        }
        return "General";
    }

    private static String preview(final Tool tool) {
        try {
            return tool.render(Map.of());
        } catch (RuntimeException e) {
            return "";
        }
    }
}
