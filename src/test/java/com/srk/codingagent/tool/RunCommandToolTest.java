package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.persistence.OperationClass;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the {@code run_command} tool (C10, 04-apis § 3, ADR-0003). The tool is the
 * SUT, wired to a real {@link CommandExecutor} running real short subprocesses and to a
 * real {@link ResolvedConfig} supplying the timeout.
 *
 * <p>Oracles: 04-apis § 3 (run_command is Class X; input command → CommandResult), and
 * ADR-0003 / NFR-CMD-TIMEOUT (the timeout comes from
 * {@link ResolvedConfig#commandTimeoutSeconds()} and a command exceeding it is tree-killed
 * with timedOut=true).
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class RunCommandToolTest {

    private static ResolvedConfig configWithTimeout(int timeoutSeconds) {
        return new ResolvedConfig(
                "anthropic.claude-opus-4-8", PermissionMode.ASK_EVERY_TIME, "us-east-1",
                null, 1, null, ResolvedConfig.Commands.empty(), 0.85, 16384, 5, timeoutSeconds,
                10, 300);
    }

    @Test
    @DisplayName("04-apis § 3: run_command is Class X (SIDE_EFFECTING)")
    void runCommandIsClassX(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 — run_command Class = X.
        RunCommandTool tool = new RunCommandTool(
                new CommandExecutor(workspace), configWithTimeout(300));

        assertEquals(OperationClass.SIDE_EFFECTING, tool.operationClass(),
                "run_command is Class X (04-apis § 3)");
        assertEquals("run_command", tool.name());
    }

    @Test
    @DisplayName("04-apis § 3: run_command returns a CommandResult carrying the exit code")
    void returnsCommandResult(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 — run_command(command) returns a CommandResult; the result
        // carries exitCode (the verification signal, RD-10).
        RunCommandTool tool = new RunCommandTool(
                new CommandExecutor(workspace), configWithTimeout(300));

        Object result = tool.handle(Map.of("command", "echo hi"));

        CommandResult commandResult = assertInstanceOf(CommandResult.class, result,
                "run_command returns a CommandResult (04-apis § 3)");
        assertEquals(0, commandResult.exitCode(), "the exit code is captured (RD-10)");
        assertTrue(commandResult.stdout().contains("hi"), "stdout is captured");
    }

    @Test
    @DisplayName("NFR-CMD-TIMEOUT: the configured commandTimeoutSeconds bounds the command")
    void usesConfiguredTimeout(@TempDir Path workspace) {
        // Oracle: NFR-CMD-TIMEOUT / ADR-0003 — the per-command timeout comes from
        // ResolvedConfig.commandTimeoutSeconds(). A 1-second configured timeout against a
        // 30s sleep must time out (tree-kill), proving the config value bounds the run.
        RunCommandTool tool = new RunCommandTool(
                new CommandExecutor(workspace), configWithTimeout(1));

        CommandResult result = (CommandResult) tool.handle(Map.of("command", "sleep 30"));

        assertTrue(result.timedOut(),
                "a command past the configured timeout is timed out (NFR-CMD-TIMEOUT)");
    }

    @Test
    @DisplayName("run_command surfaces a missing command input as a tool error")
    void surfacesMissingCommand(@TempDir Path workspace) {
        RunCommandTool tool = new RunCommandTool(
                new CommandExecutor(workspace), configWithTimeout(300));

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of()));
    }
}
