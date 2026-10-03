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
package ai.oak.tools.cli;

import ai.oak.tools.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs an argv and captures its result. The JDK-only executor behind every command-line
 * {@link ai.oak.tools.Tool}: {@link ProcessBuilder}, no shell, so nothing in an input is ever
 * interpreted as a shell metacharacter — the tokens are passed to the OS exactly as given.
 *
 * <p>stdout and stderr are drained on separate threads while the process runs. A child that fills a
 * pipe buffer while the parent waits on the other pipe is the classic {@code ProcessBuilder} deadlock;
 * draining both concurrently is what avoids it.
 *
 * <p>Nothing here throws for how the command <em>ran</em>: a missing binary, a non-zero exit, or a
 * timeout all come back as a {@link ToolResult}, because to a runbook those are outcomes to report,
 * not failures of the agent. Only a genuinely empty command is rejected, as a programming error.
 */
public final class CommandRunner {

    /** Long enough for a describe/reboot to return, short enough not to wedge a worker forever. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private CommandRunner() {
    }

    /** Runs {@code argv} with the {@link #DEFAULT_TIMEOUT} and no extra environment. */
    public static ToolResult run(final List<String> argv) {
        return run(argv, Map.of(), DEFAULT_TIMEOUT);
    }

    /** Runs {@code argv} with extra environment variables merged onto the process environment. */
    public static ToolResult run(final List<String> argv, final Map<String, String> env) {
        return run(argv, env, DEFAULT_TIMEOUT);
    }

    /**
     * Runs {@code argv} (program first) with {@code env} merged onto the inherited environment, killing it
     * and returning a timed-out result past {@code timeout}. {@code env} is how per-deployment settings —
     * an AWS profile/region, docker host — reach the CLI the tool shells out to.
     */
    public static ToolResult run(final List<String> argv, final Map<String, String> env, final Duration timeout) {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        final Process process;
        try {
            process = start(argv, env);
        } catch (IOException e) {
            // The binary is not installed or not on PATH. That is a real, reportable outcome for the
            // step (e.g. "docker: command not found"), not a crash of the executor.
            return new ToolResult(-1, "", String.valueOf(e.getMessage()), false);
        }

        final CompletableFuture<String> stdout = drain(process.getInputStream());
        final CompletableFuture<String> stderr = drain(process.getErrorStream());
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                final String err = join("timed out after " + timeout, await(stderr));
                return new ToolResult(-1, await(stdout), err, true);
            }
            return new ToolResult(process.exitValue(), await(stdout), await(stderr), false);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new ToolResult(-1, await(stdout), "interrupted while waiting for the command", false);
        }
    }

    private static Process start(final List<String> argv, final Map<String, String> env) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder(argv);
        if (env != null) {
            env.forEach((k, v) -> {
                if (k != null && v != null) {
                    builder.environment().put(k, v);
                }
            });
        }
        return builder.start();
    }

    private static CompletableFuture<String> drain(final InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String await(final CompletableFuture<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            return "";
        }
    }

    private static String join(final String reason, final String captured) {
        return captured.isEmpty() ? reason : reason + "\n" + captured;
    }
}
