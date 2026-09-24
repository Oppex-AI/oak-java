package ai.oak.service;

import ai.oak.service.client.CapabilityDeclaration;
import ai.oak.service.client.RemoteStep;
import ai.oak.service.client.RemoteStepResultRequest;
import ai.oak.service.client.TaskStatus;
import ai.oak.service.client.ToolServiceClient;
import ai.oak.service.client.ToolServiceRegistrationRequest;
import ai.oak.service.config.PlatformConfig;
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

    private static final Logger log = LoggerFactory.getLogger(ExecutorAgent.class);

    @Inject
    PlatformConfig config;

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

        final String baseUrl = config.platform().baseUrl().filter(u -> !u.isBlank()).orElse(null);
        final String apiKey = config.platform().apiKey().filter(k -> !k.isBlank()).orElse(null);
        if (baseUrl == null || apiKey == null) {
            log.warn("Executor idle: set oak.platform.base-url and oak.platform.api-key to connect. "
                    + "{} tool(s) are wired and ready.", registry.all().size());
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
        log.info("Executor started against {} as '{}', polling every {}", baseUrl, config.service().name(),
                config.poll().interval());
    }

    void onStop(@Observes final ShutdownEvent event) {
        if (poller != null) {
            poller.shutdownNow();
        }
        if (workers != null) {
            workers.shutdown();
        }
        if (client != null) {
            client.close();
        }
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
        log.info("Tools: {} total ({} built-in set(s), {} custom bean(s))", built.all().size(),
                (config.tools().awsEnabled() ? 1 : 0) + (config.tools().dockerEnabled() ? 1 : 0), custom);
        return built;
    }

    private boolean register(final String baseUrl) {
        final List<CapabilityDeclaration> declared = registry.all().stream()
                .map(tool -> new CapabilityDeclaration(tool.capability(), tool.permission(), tool.description()))
                .toList();
        try {
            client.register(new ToolServiceRegistrationRequest(config.service().name(), config.service().version(), declared));
            status.connected(config.platform().name(), baseUrl,
                    declared.stream().map(CapabilityDeclaration::capability).toList());
            log.info("Registered with {}: {} capabilities declared", config.platform().name(), declared.size());
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Registration with {} failed; executor will not poll: {}", config.platform().name(), e.getMessage());
            status.disconnected(e.getMessage());
            return false;
        }
    }

    private void pollOnce() {
        try {
            client.nextStep().ifPresent(step -> workers.submit(() -> handle(step)));
        } catch (IOException e) {
            log.warn("Poll failed (will retry): {}", e.getMessage());
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
            final RemoteStepResultRequest report;
            if (result == null) {
                note = "no tool registered for capability " + step.capability();
                report = new RemoteStepResultRequest(step.workflowId(), step.taskId(), TaskStatus.FAILED, Map.of(), note);
            } else {
                final boolean ok = result.success();
                statusLabel = ok ? "SUCCESS" : "FAILED";
                note = ok ? null : (result.timedOut() ? "timed out" : "exit " + result.exitCode());
                report = new RemoteStepResultRequest(step.workflowId(), step.taskId(),
                        ok ? TaskStatus.SUCCESS : TaskStatus.FAILED, outputOf(result), note);
            }
            client.reportResult(report);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            note = "could not report result: " + e.getMessage();
            log.warn("Step {}/{} {} but reporting failed: {}", step.workflowId(), step.taskId(), statusLabel, e.getMessage());
        } catch (RuntimeException e) {
            note = "execution error: " + e.getMessage();
            log.warn("Step {}/{} threw: {}", step.workflowId(), step.taskId(), e.getMessage());
            reportSafely(new RemoteStepResultRequest(step.workflowId(), step.taskId(), TaskStatus.FAILED, Map.of(), note));
        }
        status.recordStep(new AgentStatus.StepRecord(
                Instant.now(), step.workflowId(), step.taskId(), step.capability(), statusLabel, note));
    }

    private void reportSafely(final RemoteStepResultRequest report) {
        try {
            client.reportResult(report);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not report failure for {}/{}: {}", report.workflowId(), report.taskId(), e.getMessage());
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
