package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the Command Executor (C10, ADR-0003). The executor is the SUT and runs real
 * short subprocesses — the spec's mechanism (ProcessBuilder, separate stream capture,
 * timeout + tree-kill) is exactly what these exercise, so mocking the subprocess would
 * test nothing. Commands use POSIX utilities available on macOS/Linux; the suite is
 * guarded to those OSes (the project targets a POSIX dev/runtime environment).
 *
 * <p>Oracles trace to: AC-20.2 (capture exit code, stdout, stderr as a structured
 * result), INV-17/CT-INV-14 (exitCode is the verification signal — captured faithfully),
 * ADR-0003 (separate streams, concurrent drain so large output does not deadlock,
 * tree-kill on NFR-CMD-TIMEOUT with timedOut=true surfaced as a failure, workspace as the
 * working directory), and NFR-OUTPUT-MAX-INLINE (full output captured, truncated=false —
 * disposal is T-1.5).
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class CommandExecutorTest {

    private static final Duration GENEROUS_TIMEOUT = Duration.ofSeconds(30);

    @Test
    @DisplayName("AC-20.2: a zero-exit command captures exitCode 0 and its stdout")
    void zeroExitCapturesStdout(@TempDir Path workspace) {
        // Oracle: AC-20.2 — capture exit code and stdout as a structured result.
        CommandResult result = new CommandExecutor(workspace).run("echo hello", GENEROUS_TIMEOUT);

        assertEquals(0, result.exitCode(), "a successful command exits 0 (AC-20.2)");
        assertTrue(result.stdout().contains("hello"), "stdout is captured (AC-20.2)");
    }

    @Test
    @DisplayName("CT-INV-14 / INV-17: exitCode equals the subprocess's real exit status (verification signal)")
    void exitCodeIsFaithfulToSubprocess(@TempDir Path workspace) {
        // Oracle: INV-17 / CT-INV-14 — a unit of work's success is a zero exit from the
        // test command; the result must carry the subprocess's REAL exit status so that
        // exitCode == 0 is exactly the success signal and a non-zero is the real failure
        // code. 'exit 0' and 'exit 7' assert both directions.
        CommandExecutor executor = new CommandExecutor(workspace);

        assertEquals(0, executor.run("exit 0", GENEROUS_TIMEOUT).exitCode(),
                "a 0-exit command yields exitCode 0 — the success signal (INV-17)");
        assertEquals(7, executor.run("exit 7", GENEROUS_TIMEOUT).exitCode(),
                "a non-zero exit is reported as the real code, not coerced (CT-INV-14)");
    }

    @Test
    @DisplayName("AC-20.2 / ADR-0003: stdout and stderr are captured on separate channels")
    void stdoutAndStderrCapturedSeparately(@TempDir Path workspace) {
        // Oracle: ADR-0003 — stdout/stderr are redirected SEPARATELY; AC-20.2 — both are
        // captured. A command that writes a distinct token to each stream must land each
        // token in the matching field, not interleaved.
        CommandResult result = new CommandExecutor(workspace)
                .run("echo OUTLINE; echo ERRLINE 1>&2", GENEROUS_TIMEOUT);

        assertTrue(result.stdout().contains("OUTLINE"), "stdout carries the stdout token");
        assertFalse(result.stdout().contains("ERRLINE"), "stderr token does not leak into stdout");
        assertTrue(result.stderr().contains("ERRLINE"), "stderr carries the stderr token");
    }

    @Test
    @DisplayName("ADR-0003: a command exceeding the timeout is tree-killed and marked timedOut")
    void timeoutTreeKillsAndFlagsTimedOut(@TempDir Path workspace) {
        // Oracle: ADR-0003 — on timeout the whole process tree is killed, timedOut=true,
        // and it surfaces as a tool failure (non-zero exit). A 10s sleep against a 200ms
        // timeout fires the kill quickly and hermetically.
        CommandResult result = new CommandExecutor(workspace)
                .run("sleep 10", Duration.ofMillis(200));

        assertTrue(result.timedOut(), "an over-budget command is flagged timedOut (ADR-0003)");
        assertFalse(result.exitCode() == 0,
                "a timed-out command surfaces as a failure (non-zero exit, ADR-0003)");
    }

    @Test
    @DisplayName("ADR-0003: large output drains without deadlocking (concurrent stream drain)")
    void largeOutputDrainsWithoutDeadlock(@TempDir Path workspace) {
        // Oracle: ADR-0003 — read stdout/stderr concurrently to avoid pipe-buffer deadlock
        // on large output. seq emits well past a single pipe buffer (64KB); if draining
        // were not concurrent this would hang and the generous timeout would trip.
        CommandResult result = new CommandExecutor(workspace)
                .run("seq 1 200000", GENEROUS_TIMEOUT);

        assertEquals(0, result.exitCode(), "the large-output command completes (no deadlock)");
        assertTrue(result.stdout().length() > 100_000,
                "the full large output is captured (ADR-0003 concurrent drain)");
    }

    @Test
    @DisplayName("NFR-OUTPUT-MAX-INLINE: output is captured in full with truncated=false (disposal is T-1.5)")
    void outputCapturedInFullNotTruncated(@TempDir Path workspace) {
        // Oracle: NFR-OUTPUT-MAX-INLINE scope note — T-0.6 captures the FULL output and
        // sets truncated=false; the truncate/summarize strategy and fullRef are T-1.5.
        CommandResult result = new CommandExecutor(workspace)
                .run("seq 1 100000", GENEROUS_TIMEOUT);

        assertFalse(result.truncated(), "output is not truncated in T-0.6 (disposal is T-1.5)");
        assertNull(result.fullRef(), "fullRef is unset until disposal wires it (T-1.5)");
    }

    @Test
    @DisplayName("ADR-0003: commands run with the workspace as the working directory")
    void commandsRunInWorkspaceDirectory(@TempDir Path workspace) {
        // Oracle: ADR-0003 — commands run with the WORKSPACE (repo root) as the working
        // directory. 'pwd' must echo the injected workspace, confirming cwd is the
        // workspace, not the JVM's cwd.
        CommandResult result = new CommandExecutor(workspace).run("pwd", GENEROUS_TIMEOUT);

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().trim().endsWith(workspace.getFileName().toString()),
                "the command runs in the injected workspace directory (ADR-0003): " + result.stdout());
    }

    @Test
    @DisplayName("ADR-0003: durationMs is captured as a non-negative wall-clock measure")
    void durationIsCaptured(@TempDir Path workspace) {
        // Oracle: ADR-0003 / command-result.schema.json — durationMs is part of every
        // result with minimum 0.
        CommandResult result = new CommandExecutor(workspace).run("true", GENEROUS_TIMEOUT);

        assertTrue(result.durationMs() >= 0, "durationMs is non-negative (schema minimum 0)");
    }

    @Test
    @DisplayName("Executor rejects a non-directory workspace at construction")
    void rejectsNonDirectoryWorkspace(@TempDir Path workspace) {
        // Defensive: the workspace root must be an existing directory (injected argument).
        Path notADir = workspace.resolve("does-not-exist");

        assertThrows(IllegalArgumentException.class, () -> new CommandExecutor(notADir));
    }

    @Test
    @DisplayName("Executor rejects a blank command and a non-positive timeout")
    void rejectsBlankCommandAndBadTimeout(@TempDir Path workspace) {
        CommandExecutor executor = new CommandExecutor(workspace);

        assertThrows(IllegalArgumentException.class, () -> executor.run("   ", GENEROUS_TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> executor.run("echo hi", Duration.ZERO));
    }
}
