package com.srk.codingagent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Command Executor (component C10, ADR-0003): the single path that runs a command
 * as a subprocess and captures its outcome as a structured {@link CommandResult}. It is
 * the verification + safety spine — every command the agent runs goes through here, so
 * the loop can reason on a captured {@code exitCode} (RD-10) and the timeout / tree-kill
 * is enforced in one place.
 *
 * <p><b>Execution context (ADR-0003).</b> Commands run with the injected workspace
 * directory as the working directory and inherit the caller's environment; the executor
 * adds nothing implicitly. The workspace root is supplied at construction rather than
 * derived from git here — later wiring provides it.
 *
 * <p><b>Mechanism (ADR-0003, NFR-PLAT-JAVA).</b> Each command runs via
 * {@link ProcessBuilder} with {@code stdout} and {@code stderr} redirected
 * <em>separately</em>. The two streams are drained on dedicated threads so a command that
 * floods one pipe cannot deadlock against a full pipe buffer. The timeout is enforced
 * with {@link Process#waitFor(long, TimeUnit)}; on expiry the whole process tree is
 * killed via {@link ProcessHandle#descendants()} (Maven and other build tools fork child
 * processes), {@code timedOut} is set, and the command surfaces as a tool failure.
 *
 * <p><b>Shell semantics (ADR-0003).</b> There is no shell interpolation: the command is
 * handed to {@code sh -c} as a single argument so a generic command string (which may
 * contain pipes, redirects, and shell built-ins) executes as the user typed it, without
 * the executor re-tokenizing it. This matches the {@code run_command(command: string)}
 * contract (04-apis § 3) where {@code command} is one opaque string.
 *
 * <p><b>Output disposal (NFR-OUTPUT-MAX-INLINE, ADR-0006).</b> This executor captures the
 * full {@code stdout}/{@code stderr}; the truncate/summarize strategy that keeps oversize
 * output out of the model context is a later task (T-1.5). Every result here therefore
 * has {@code truncated == false}.
 */
public final class CommandExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandExecutor.class);

    /** Grace period to let a tree-killed process collect its exit status. */
    private static final Duration KILL_GRACE = Duration.ofSeconds(5);

    private final Path workspaceDir;

    /**
     * Creates an executor rooted at the given workspace directory.
     *
     * @param workspaceDir the working directory commands run in (the repo root,
     *                     ADR-0003); must not be {@code null} and must be an existing
     *                     directory.
     * @throws NullPointerException     if {@code workspaceDir} is {@code null}.
     * @throws IllegalArgumentException if {@code workspaceDir} is not an existing
     *                                  directory.
     */
    public CommandExecutor(Path workspaceDir) {
        this.workspaceDir = Objects.requireNonNull(workspaceDir, "workspaceDir");
        if (!Files.isDirectory(workspaceDir)) {
            throw new IllegalArgumentException(
                    "workspaceDir must be an existing directory: " + workspaceDir);
        }
    }

    /**
     * Runs {@code command} as a subprocess and captures its structured result, enforcing
     * the supplied timeout.
     *
     * <p>The returned {@link CommandResult} carries the subprocess's real exit status
     * (the verification signal, RD-10/INV-17), the full captured streams, and the
     * wall-clock duration. If the command does not finish within {@code timeout}, its
     * whole process tree is killed and the result has {@code timedOut == true}.
     *
     * @param command the command line to run as a single shell argument; must not be
     *                {@code null} or blank.
     * @param timeout the maximum wall-clock duration before the process tree is killed
     *                (NFR-CMD-TIMEOUT, from
     *                {@link com.srk.codingagent.config.ResolvedConfig#commandTimeoutSeconds()});
     *                must not be {@code null} and must be positive.
     * @return the structured {@link CommandResult}.
     * @throws NullPointerException     if {@code command} or {@code timeout} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if {@code command} is blank or {@code timeout} is
     *                                  not positive.
     * @throws CommandExecutionException if the subprocess cannot be started or the
     *                                   capturing thread is interrupted.
     */
    public CommandResult run(String command, Duration timeout) {
        if (Objects.requireNonNull(command, "command").isBlank()) {
            throw new IllegalArgumentException("command must be non-blank");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive (was " + timeout + ")");
        }

        LOGGER.info("Executing command in {} with timeout {}s: {}",
                workspaceDir, timeout.toSeconds(), command);
        long start = System.nanoTime();
        Process process = startProcess(command);
        return capture(command, process, timeout, start);
    }

    private Process startProcess(String command) {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", command)
                .directory(workspaceDir.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        try {
            return builder.start();
        } catch (IOException e) {
            LOGGER.error("Failed to start command: {}", command, e);
            throw new CommandExecutionException("failed to start command: " + command, e);
        }
    }

    private CommandResult capture(String command, Process process, Duration timeout, long start) {
        // Drain stdout and stderr concurrently so neither pipe buffer fills and deadlocks
        // the subprocess against a writer that is blocked on a full pipe (ADR-0003).
        ExecutorService drainPool = Executors.newFixedThreadPool(2);
        try {
            Future<String> stdoutFuture = drainPool.submit(() -> readStream(process.getInputStream()));
            Future<String> stderrFuture = drainPool.submit(() -> readStream(process.getErrorStream()));

            boolean finished = waitFor(process, timeout);
            if (!finished) {
                killTree(process);
                long durationMs = elapsedMillis(start);
                String stdout = drain(stdoutFuture);
                String stderr = drain(stderrFuture);
                LOGGER.warn("Command timed out after {}s and was tree-killed: {}",
                        timeout.toSeconds(), command);
                return CommandResult.timedOut(command, stdout, stderr, durationMs);
            }

            int exitCode = process.exitValue();
            long durationMs = elapsedMillis(start);
            String stdout = drain(stdoutFuture);
            String stderr = drain(stderrFuture);
            LOGGER.info("Command exited {} in {}ms: {}", exitCode, durationMs, command);
            return CommandResult.completed(command, exitCode, stdout, stderr, durationMs);
        } finally {
            drainPool.shutdownNow();
        }
    }

    private static boolean waitFor(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new CommandExecutionException("interrupted while waiting for command", e);
        }
    }

    /**
     * Kills the process and every descendant (Maven and other build tools fork children;
     * destroying only the {@code sh} parent would orphan them). Uses
     * {@link ProcessHandle#descendants()} (Java 9+, verified on Java 21 — NFR-PLAT-JAVA).
     */
    private static void killTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        for (ProcessHandle descendant : descendants) {
            descendant.destroyForcibly();
        }
        try {
            process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readStream(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The stream closes when the process dies; a read interrupted by that is not a
            // command failure, so capture what the partial read implies rather than failing
            // the whole execution.
            LOGGER.debug("Stopped reading a process stream early", e);
            return "";
        }
    }

    private static String drain(Future<String> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommandExecutionException("interrupted while draining command output", e);
        } catch (ExecutionException e) {
            throw new CommandExecutionException("failed to drain command output", e.getCause());
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
