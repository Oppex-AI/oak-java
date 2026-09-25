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

import ai.oak.service.client.CapabilityDeclaration;
import ai.oak.service.client.RemoteStep;
import ai.oak.service.client.RemoteStepResultRequest;
import ai.oak.service.client.TaskStatus;
import ai.oak.service.client.ToolServiceClient;
import ai.oak.service.client.ToolServiceRegistrationRequest;
import ai.oak.service.config.PlatformConfig;
import ai.oak.service.config.RuntimeSettings;
import ai.oak.tools.Tool;
import ai.oak.tools.ToolRegistry;
import ai.oak.tools.ToolResult;
import ai.oak.tools.aws.AwsTools;
import ai.oak.tools.docker.DockerTools;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The executor: it registers this service with the platform, then loops
 * register → poll → execute → report. Every connection it uses is one it opened itself, outbound —
 * the service listens on nothing.
 *
 * <p>Execution is {@link ToolRegistry#execute}, the same call the platform side makes, so a step runs
 * identically wherever it lands. A tool that fails is reported FAILED with its output; only a genuine
 * inability to attempt the work (an unknown capability, a thrown exception) is turned into a FAILED
 * report too — nothing here stops the loop.
 */
@ApplicationScoped
public class ExecutorAgent {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorAgent.class);

    @Inject
    PlatformConfig config;

    @Inject
    RuntimeSettings settings;

    @Inject
    AgentStatus status;

    /** Any customer-supplied {@link Tool} CDI beans, registered after the built-ins so theirs win. */
    @Inject
    Instance<Tool> customTools;

    private ToolServiceClient client;
    private ToolRegistry registry;
    private ScheduledExecutorService poller;
    private ExecutorService workers;

    void onStart(@Observes final StartupEvent event) {
        registry = buildRegistry();
        status.wired(registry.all().stream().map(Tool::capability).toList());
        connect();
    }

    void onStop(@Observes final ShutdownEvent event) {
        teardown();
    }

    /**
     * Re-read the platform settings (base URL + API key) and reconnect. Called after the UI updates them,
     * so a key pasted into the management page takes effect without a restart.
     */
    public synchronized void reconnect() {
        LOG.info("Reconnecting executor with updated settings");
        teardown();
        connect();
    }

    private synchronized void connect() {
        final String baseUrl = trimToNull(settings.baseUrl());
        final String apiKey = trimToNull(settings.apiKey());
        if (baseUrl == null || apiKey == null) {
            LOG.warn("Executor idle: set the platform base URL and API key (management page or config) to " +
                    "connect. {} tool(s) are wired and ready.", registry.all().size());
            status.disconnected("not configured (missing base URL or API key)");
            return;
        }

        client = new ToolServiceClient(baseUrl, apiKey, config.platform().name());
        if (!register(baseUrl)) {
            return;
        }

        workers = Executors.newFixedThreadPool(Math.max(1, config.poll().workerThreads()));
        poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "oak-poller");
            thread.setDaemon(true);
            return thread;
        });
        final long millis = Math.max(1000, config.poll().interval().toMillis());
        poller.scheduleWithFixedDelay(this::pollOnce, millis, millis, TimeUnit.MILLISECONDS);
        LOG.info("Executor started against {} as '{}', polling every {}", baseUrl, config.service().name(),
                config.poll().interval());
    }

    private void teardown() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        if (workers != null) {
            workers.shutdown();
            workers = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private static String trimToNull(final String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private ToolRegistry buildRegistry() {
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
        LOG.info("Tools: {} total ({} built-in set(s), {} custom bean(s))", built.all().size(),
                (config.tools().awsEnabled() ? 1 : 0) + (config.tools().dockerEnabled() ? 1 : 0), custom);
        return built;
    }

    private boolean register(final String baseUrl) {
        final List<CapabilityDeclaration> declared = registry.all().stream()
                .map(tool -> new CapabilityDeclaration(tool.capability(), tool.permission(), tool.description())).toList();
        try {
            client.register(new ToolServiceRegistrationRequest(config.service().name(), config.service().version(), declared));
            status.connected(config.platform().name(), baseUrl);
            LOG.info("Registered with {}: {} capabilities declared", config.platform().name(), declared.size());
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Registration with {} failed; executor will not poll: {}", config.platform().name(), e.getMessage());
            status.disconnected(e.getMessage());
            return false;
        }
    }

    private void pollOnce() {
        try {
            client.nextStep().ifPresent(step -> workers.submit(() -> handle(step)));
        } catch (IOException e) {
            LOG.warn("Poll failed (will retry): {}", e.getMessage());
            status.disconnected(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(final RemoteStep step) {
        String statusLabel = "FAILED";
        String note = null;
        try {
            final ToolResult result = registry.execute(step.capability(), step.input()).orElse(null);
            if (result == null) {
                note = "no tool registered for capability " + step.capability();
                client.reportResult(failed(step, note));
            } else {
                final boolean ok = result.success();
                statusLabel = ok ? "SUCCESS" : "FAILED";
                note = ok ? null : (result.timedOut() ? "timed out" : "exit " + result.exitCode());
                client.reportResult(new RemoteStepResultRequest(step.workflowId(), step.taskId(),
                        ok ? TaskStatus.SUCCESS : TaskStatus.FAILED, outputOf(result), note));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            note = "could not report result: " + e.getMessage();
            LOG.warn("Step {}/{} {} but reporting failed: {}", step.workflowId(), step.taskId(), statusLabel, e.getMessage());
        } catch (RuntimeException e) {
            note = "execution error: " + e.getMessage();
            LOG.warn("Step {}/{} threw: {}", step.workflowId(), step.taskId(), e.getMessage());
            reportSafely(failed(step, note));
        }
        status.recordStep(new AgentStatus.StepRecord(Instant.now(), step.workflowId(), step.taskId(), step.capability(),
                statusLabel, note));
    }

    private static RemoteStepResultRequest failed(final RemoteStep step, final String note) {
        return new RemoteStepResultRequest(step.workflowId(), step.taskId(), TaskStatus.FAILED, Map.of(), note);
    }

    private void reportSafely(final RemoteStepResultRequest report) {
        try {
            client.reportResult(report);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Could not report failure for {}/{}: {}", report.workflowId(), report.taskId(), e.getMessage());
        }
    }

    private static Map<String, Object> outputOf(final ToolResult result) {
        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("exitCode", result.exitCode());
        output.put("stdout", result.stdout());
        output.put("stderr", result.stderr());
        if (result.timedOut()) {
            output.put("timedOut", true);
        }
        return output;
    }
}
