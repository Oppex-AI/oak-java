package ai.oak.tools.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.oak.tools.ToolResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the execution mechanics against a POSIX shell, since it is present wherever these tests
 * run and lets us script an exact exit code, stdout and stderr.
 */
class CommandRunnerTest {

    private static boolean posixShell() {
        return !System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @BeforeEach
    void requireShell() {
        assumeTrue(posixShell(), "POSIX shell tests");
    }

    @Test
    void capturesStdoutAndZeroExit() {
        final ToolResult result = CommandRunner.run(List.of("sh", "-c", "printf hello; exit 0"));
        assertEquals(0, result.exitCode());
        assertEquals("hello", result.stdout());
        assertTrue(result.success());
        assertFalse(result.timedOut());
    }

    @Test
    void capturesStderrAndNonZeroExit() {
        final ToolResult result = CommandRunner.run(List.of("sh", "-c", "printf oops 1>&2; exit 7"));
        assertEquals(7, result.exitCode());
        assertEquals("oops", result.stderr());
        assertFalse(result.success());
    }

    @Test
    void missingBinaryIsAReportedOutcomeNotAThrow() {
        final ToolResult result = CommandRunner.run(List.of("oak-no-such-binary-xyz"));
        assertEquals(-1, result.exitCode());
        assertFalse(result.success());
        assertFalse(result.stderr().isBlank());
    }

    @Test
    void aCommandOverItsTimeBudgetIsKilledAndReportedTimedOut() {
        final ToolResult result = CommandRunner.run(List.of("sh", "-c", "sleep 5"), Duration.ofMillis(200));
        assertTrue(result.timedOut());
        assertFalse(result.success());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void emptyCommandIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> CommandRunner.run(List.of()));
    }
}
