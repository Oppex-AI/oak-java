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

import ai.oak.service.ToolCatalog;
import ai.oak.service.ToolExecutor;
import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs a single tool on demand from the management UI so an operator can see what the linked account
 * actually returns — describe/list an AWS service (EC2, RDS, MSK, ElastiCache, CloudWatch, S3) and read
 * the live JSON, without waiting for the platform to send a step.
 *
 * <p>Strictly read-only and enforced server-side, not just hidden in the UI: only {@link
 * ToolPermission#READ} tools may run here, and only when the tool is active (its provider is configured).
 * A WRITE/DESTRUCTIVE tool is refused — those run only through an approved platform step.
 */
@Path("/api")
public class ToolRunResource {

    @Inject
    ToolCatalog catalog;

    @Inject
    ToolExecutor executor;

    @POST
    @Path("/tools/run")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> run(final RunRequest req) {
        if (req == null || req.capability() == null || req.capability().isBlank()) {
            return error("capability is required");
        }
        final String capability = req.capability().trim();
        final Tool tool = catalog.registry().find(capability).orElse(null);
        if (tool == null) {
            return error("unknown capability: " + capability);
        }
        if (tool.permission() != ToolPermission.READ) {
            return error("only READ tools can be run here; " + capability + " is " + tool.permission() +
                    " and runs only through an approved platform step");
        }
        if (!catalog.isActive(capability)) {
            return error(catalog.inactiveReason(capability));
        }
        final Map<String, Object> input = req.input() == null ? Map.of() : req.input();
        final ToolResult result = executor.run(capability, input).orElse(null);
        if (result == null) {
            return error("could not run " + capability);
        }
        return ok(capability, tool.render(input), result);
    }

    private static Map<String, Object> ok(final String capability, final String command, final ToolResult result) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("capability", capability);
        out.put("command", command);
        out.put("success", result.success());
        out.put("exitCode", result.exitCode());
        out.put("timedOut", result.timedOut());
        out.put("stdout", result.stdout());
        out.put("stderr", result.stderr());
        return out;
    }

    private static Map<String, Object> error(final String message) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }

    /** A request to run one tool: its capability id and the input map it reads. */
    public record RunRequest(String capability, Map<String, Object> input) {
    }
}
