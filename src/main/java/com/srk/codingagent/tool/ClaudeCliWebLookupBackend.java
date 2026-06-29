package com.srk.codingagent.tool;

import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The v1 {@link WebLookupBackend} (C11, ADR-0008): a web lookup is performed by a CONSTRAINED headless
 * Claude CLI subprocess. A headless {@code claude} is a full agent, so for a search-and-summarize job
 * it must be constrained to do only that and hand back text (ADR-0008 Context). This backend builds a
 * constrained delegate prompt, spawns {@code claude -p "<task>" --output-format text} via the reused
 * {@link CommandExecutor} subprocess machinery (ADR-0003), and returns the delegate's text as a
 * summarized {@link WebLookupResult}.
 *
 * <p><b>The constrain-the-delegate discipline (ADR-0008 Notes — the load-bearing safety property).</b>
 * <ul>
 *   <li><b>Print mode.</b> {@code -p}/{@code --output-format text} runs the delegate headless and makes
 *       it return plain text, not an interactive session.</li>
 *   <li><b>Scratch CWD, not the workspace.</b> The injected {@link CommandExecutor} is rooted at a
 *       scratch/temp directory (NOT the workspace), so nothing the delegate does touches the repo —
 *       a headless agent with repo write access would bypass our permission gate entirely.</li>
 *   <li><b>Hard timeout (NFR-NET-WEBLOOKUP-TIMEOUT).</b> The executor tree-kills the delegate past the
 *       configured timeout (120 s default); a timed-out lookup is a failure result, not a hang.</li>
 *   <li><b>Web-only intent.</b> The prompt instructs the delegate to use only its web search/fetch
 *       capability and to return a concise summary, never to write files or run repo commands.</li>
 * </ul>
 *
 * <p><b>Failure path (AC-11.3).</b> If {@code claude} is absent on PATH (the shell reports exit
 * {@value #COMMAND_NOT_FOUND_EXIT}), the delegate errors (non-zero exit), or it times out, this
 * backend returns {@link WebLookupResult#failure} carrying a report — it never fabricates a result and
 * never throws for the absent/error/timeout cases. The {@code web_search}/{@code web_fetch} tools
 * surface that report so the agent reports the failure.
 */
public final class ClaudeCliWebLookupBackend implements WebLookupBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClaudeCliWebLookupBackend.class);

    /** POSIX {@code sh} exit code for a command that cannot be found (e.g. {@code claude} not on PATH). */
    static final int COMMAND_NOT_FOUND_EXIT = 127;

    /** The default delegate program — the headless Claude CLI (ADR-0008). */
    static final String DEFAULT_PROGRAM = "claude";

    private final CommandExecutor executor;
    private final Duration timeout;
    private final String program;

    /**
     * Creates the backend over the scratch-rooted executor and the web-lookup timeout, spawning the
     * default headless Claude CLI ({@code claude}, ADR-0008).
     *
     * @param scratchExecutor a {@link CommandExecutor} rooted at a scratch/temp directory (NOT the
     *                        workspace, ADR-0008) used to spawn the delegate; must not be {@code null}.
     * @param timeout         the hard wall-clock timeout for the delegate
     *                        (NFR-NET-WEBLOOKUP-TIMEOUT, 120 s default); must not be {@code null} and
     *                        must be positive.
     * @throws NullPointerException     if {@code scratchExecutor} or {@code timeout} is {@code null}.
     * @throws IllegalArgumentException if {@code timeout} is not positive.
     */
    public ClaudeCliWebLookupBackend(CommandExecutor scratchExecutor, Duration timeout) {
        this(scratchExecutor, timeout, DEFAULT_PROGRAM);
    }

    /**
     * Creates the backend with an explicit delegate program name. Production uses
     * {@link #DEFAULT_PROGRAM} via the public constructor; this seam lets a test point the delegate at
     * a deterministic stub (a {@code claude}-shaped script) or a guaranteed-absent program without
     * depending on a real {@code claude} being installed, while still exercising the real
     * {@link CommandExecutor} subprocess machinery (ADR-0003).
     *
     * @param scratchExecutor the scratch-rooted executor; must not be {@code null}.
     * @param timeout         the hard wall-clock timeout; must not be {@code null} and positive.
     * @param program         the delegate program to spawn; non-blank.
     * @throws NullPointerException     if {@code scratchExecutor}, {@code timeout}, or {@code program}
     *                                  is {@code null}.
     * @throws IllegalArgumentException if {@code timeout} is not positive or {@code program} is blank.
     */
    ClaudeCliWebLookupBackend(CommandExecutor scratchExecutor, Duration timeout, String program) {
        this.executor = Objects.requireNonNull(scratchExecutor, "scratchExecutor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive (was " + timeout + ")");
        }
        this.program = Objects.requireNonNull(program, "program");
        if (program.isBlank()) {
            throw new IllegalArgumentException("program must be non-blank");
        }
    }

    @Override
    public WebLookupResult lookup(WebLookupRequest request) {
        Objects.requireNonNull(request, "request");
        String command = buildCommand(request);
        LOGGER.info("Web lookup ({}) via constrained claude -p, timeout {}s",
                request.kind(), timeout.toSeconds());

        CommandResult result;
        try {
            result = executor.run(command, timeout);
        } catch (CommandExecutionException e) {
            // The delegate subprocess could not be started or was interrupted — an infrastructure
            // failure distinct from a non-zero exit. AC-11.3: report, never fabricate.
            LOGGER.warn("Web lookup ({}) could not run the delegate: {}",
                    request.kind(), e.getMessage());
            return WebLookupResult.failure(
                    "web lookup failed: the delegate subprocess could not run (" + e.getMessage() + ")");
        }

        if (result.timedOut()) {
            LOGGER.warn("Web lookup ({}) timed out after {}s", request.kind(), timeout.toSeconds());
            return WebLookupResult.failure(
                    "web lookup failed: the delegate timed out after " + timeout.toSeconds()
                            + "s (NFR-NET-WEBLOOKUP-TIMEOUT)");
        }
        if (result.exitCode() == COMMAND_NOT_FOUND_EXIT) {
            LOGGER.warn("Web lookup ({}) unavailable: the claude CLI is not on PATH", request.kind());
            return WebLookupResult.failure(
                    "web lookup unavailable: the headless claude CLI was not found on PATH");
        }
        if (result.exitCode() != 0) {
            LOGGER.warn("Web lookup ({}) failed: the delegate exited {}",
                    request.kind(), result.exitCode());
            return WebLookupResult.failure(
                    "web lookup failed: the delegate exited " + result.exitCode()
                            + (result.stderr().isBlank() ? "" : " (" + result.stderr().strip() + ")"));
        }

        String summary = result.stdout().strip();
        if (summary.isEmpty()) {
            LOGGER.warn("Web lookup ({}) returned no text", request.kind());
            return WebLookupResult.failure("web lookup failed: the delegate returned no result text");
        }
        return WebLookupResult.success(summary);
    }

    /**
     * Builds the constrained {@code claude -p} command line. The lookup task is embedded as a
     * single-quoted prompt and the delegate is told to use only web search/fetch and return a concise
     * summary (ADR-0008 — a headless agent must be constrained to search-and-summarize and hand back
     * text). {@code --output-format text} makes the delegate emit plain text.
     */
    private String buildCommand(WebLookupRequest request) {
        String task = lookupTask(request);
        return program + " -p " + singleQuote(task) + " --output-format text";
    }

    private static String lookupTask(WebLookupRequest request) {
        String intent = switch (request.kind()) {
            case SEARCH -> "Search the web for: " + request.argument();
            case FETCH -> "Fetch and read this URL: " + request.argument();
        };
        return intent
                + ". Use only your web search and fetch capability — do not write files or run "
                + "commands. Return a concise, factual summary of what you find as plain text.";
    }

    /**
     * Wraps {@code value} in POSIX single quotes for {@code sh -c}, escaping embedded single quotes
     * with the {@code '\''} idiom so the prompt cannot break out of the quoting or inject shell
     * syntax. (The executor hands the whole command to {@code sh -c} as one argument, ADR-0003.)
     */
    private static String singleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
