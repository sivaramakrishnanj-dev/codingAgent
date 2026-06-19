package com.srk.codingagent.tool;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.persistence.OperationClass;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code run_command} tool (component C10, 04-apis § 3, ADR-0003): runs a command
 * string as a subprocess and returns its {@link CommandResult}. It is Class X
 * ({@link OperationClass#SIDE_EFFECTING}) — the generic escape hatch the permission mode
 * gates (T-0.7 owns authorization; this task owns execution).
 *
 * <p>Input: {@code command} (required, a single command string). The handler enforces
 * the configured per-command timeout (NFR-CMD-TIMEOUT, from
 * {@link ResolvedConfig#commandTimeoutSeconds()}) by delegating to the
 * {@link CommandExecutor}, which captures the full {@code stdout}/{@code stderr}, the real
 * {@code exitCode} (the verification signal, RD-10/INV-17), the duration, and the
 * timed-out flag. The {@code CommandResult} this returns becomes the {@code content} of
 * the tool result the registry produces.
 */
public final class RunCommandTool implements ToolHandler {

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "run_command";

    private final CommandExecutor executor;
    private final Duration timeout;

    /**
     * Creates the tool with the executor that runs the subprocess and the resolved config
     * that pins the timeout.
     *
     * @param executor the command executor (rooted at the workspace); must not be
     *                 {@code null}.
     * @param config   the resolved config supplying {@code commandTimeoutSeconds}
     *                 (NFR-CMD-TIMEOUT); must not be {@code null}.
     * @throws NullPointerException     if {@code executor} or {@code config} is
     *                                  {@code null}.
     */
    public RunCommandTool(CommandExecutor executor, ResolvedConfig config) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Duration.ofSeconds(
                Objects.requireNonNull(config, "config").commandTimeoutSeconds());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Run a command in the workspace and return its exit code, stdout, stderr, "
                + "duration, and whether it timed out.";
    }

    @Override
    public Document inputSchema() {
        return ToolSchemas.runCommand();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.SIDE_EFFECTING;
    }

    /**
     * Runs the {@code command} input and returns its structured result.
     *
     * @param input the {@code toolUse.input}: requires {@code command}.
     * @return the {@link CommandResult} of the execution.
     * @throws ToolInvocationException   if {@code command} is missing or blank.
     * @throws CommandExecutionException if the subprocess cannot be started or is
     *                                   interrupted (an infrastructure failure, distinct
     *                                   from a non-zero exit).
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        String command = ToolInputs.requireString(input, "command");
        return executor.run(command, timeout);
    }
}
