package com.srk.codingagent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.CommandResult;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the {@link CommandRunner#over(CommandExecutor, String, Duration)} factory —
 * the production wiring that runs the configured test command through the real
 * {@link CommandExecutor} with the configured per-command timeout (AC-20.1, RD-10, the same
 * config-timeout pattern {@link com.srk.codingagent.tool.RunCommandTool} uses).
 *
 * <p>The SUT is the runner the factory returns; the only external dependency is the real
 * {@link CommandExecutor} running trivial shell commands in a temp workspace (no scripted
 * double — the point of these tests is to verify the seam actually delegates to the executor
 * and faithfully carries the exit code, the verification signal of RD-10/INV-17).
 */
class CommandRunnerTest {

    @Test
    @DisplayName("RD-10: the production runner delegates to the executor and carries a zero exit")
    void overRunsCommandAndCarriesZeroExit(@TempDir Path workspace) {
        // Oracle: AC-20.1/RD-10 — the runner runs the configured command via the executor;
        // a command that exits 0 ("true") yields a CommandResult with exitCode 0 (the success
        // signal). Verifies the seam is a real delegation, not a stub.
        CommandRunner runner = CommandRunner.over(
                new CommandExecutor(workspace), "true", Duration.ofSeconds(30));

        CommandResult result = runner.run();

        assertEquals(0, result.exitCode(), "RD-10: 'true' exits 0 — the verification success signal");
    }

    @Test
    @DisplayName("CT-INV-14: the production runner carries a non-zero exit faithfully (failure direction)")
    void overCarriesNonZeroExit(@TempDir Path workspace) {
        // Oracle: CT-INV-14/INV-17 (failure direction) — the runner faithfully carries a
        // non-zero exit. "false" exits 1, so the result's exitCode is the real non-zero failure
        // signal, not a swallowed/normalised 0.
        CommandRunner runner = CommandRunner.over(
                new CommandExecutor(workspace), "false", Duration.ofSeconds(30));

        CommandResult result = runner.run();

        assertTrue(result.exitCode() != 0,
                "CT-INV-14: a failing command's non-zero exit is carried through, not normalised");
    }

    @Test
    @DisplayName("over rejects a null executor, a blank command, and a non-positive timeout")
    void overValidatesArguments(@TempDir Path workspace) {
        CommandExecutor executor = new CommandExecutor(workspace);
        assertThrows(NullPointerException.class,
                () -> CommandRunner.over(null, "mvn test", Duration.ofSeconds(30)));
        assertThrows(NullPointerException.class,
                () -> CommandRunner.over(executor, null, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> CommandRunner.over(executor, "   ", Duration.ofSeconds(30)),
                "a blank command is rejected (mirrors CommandExecutor.run)");
        assertThrows(NullPointerException.class,
                () -> CommandRunner.over(executor, "mvn test", null));
        assertThrows(IllegalArgumentException.class,
                () -> CommandRunner.over(executor, "mvn test", Duration.ZERO),
                "a non-positive timeout is rejected");
    }
}
