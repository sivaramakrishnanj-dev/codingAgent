package com.srk.codingagent.cli;

import java.util.Objects;
import java.util.Optional;

/**
 * The parsed command-line arguments this task's CLI scope understands: the one-shot
 * prompt ({@code -p} / {@code --prompt}), the two standard informational flags
 * ({@code --help}, {@code --version}), and the session subcommands {@code resume
 * [<session-id>]} and {@code sessions} (04-apis § 1.2). Parsing is deliberately narrow —
 * the full proposed flag set ({@code --mode} / {@code --model} / {@code --permission-mode}
 * / {@code --profile} / {@code --region} / {@code --attach} / {@code --debug}) and the
 * other subcommands ({@code memory}, {@code config}) belong to later tasks — but an
 * unrecognized flag or an unexpected positional is rejected as a usage error so a bad
 * invocation fails fast with exit {@code 2} (04-apis § 1.1, cli-exit-codes {@code 2})
 * rather than being silently ignored.
 *
 * <p>The result is one of five shapes, distinguished by {@link #kind()}:
 * <ul>
 *   <li>{@link Kind#ONE_SHOT} — a {@code -p "<prompt>"} invocation; {@link #prompt()} holds
 *       the (non-blank) prompt text. Runs to {@code end_turn} then exits (US-6).</li>
 *   <li>{@link Kind#INFO} — {@code --help} or {@code --version}; the CLI prints the
 *       requested information and exits {@code 0}.</li>
 *   <li>{@link Kind#INTERACTIVE} — no {@code -p}; the interactive REPL (T-1.1).</li>
 *   <li>{@link Kind#RESUME} — {@code resume [<session-id>]}; list resumable sessions
 *       (no id) or resume one (AC-7.1/7.2/7.4). {@link #sessionId()} holds the optional
 *       id.</li>
 *   <li>{@link Kind#SESSIONS} — {@code sessions}; list past sessions for this repo
 *       (AC-15.2).</li>
 * </ul>
 *
 * <p>The {@code resume}/{@code sessions} subcommands are recognized only as the leading
 * argument (the subcommand position); a {@code resume}/{@code sessions} word anywhere else,
 * or any other bare positional, stays a fail-fast usage error. Parsing never starts the
 * agent and never touches configuration; it is the pure arg-shape decision the launcher
 * acts on. A malformed invocation throws {@link UsageException}, which the launcher maps to
 * exit {@code 2} naming the offending argument (G2).
 */
public final class CliArguments {

    /** The flag that begins a one-shot prompt (short form). */
    static final String PROMPT_SHORT = "-p";

    /** The flag that begins a one-shot prompt (long form). */
    static final String PROMPT_LONG = "--prompt";

    /** The flag that requests usage help. */
    static final String HELP = "--help";

    /** The flag that requests the version. */
    static final String VERSION = "--version";

    /** The subcommand that lists resumable sessions or resumes one (04-apis § 1.2). */
    static final String RESUME = "resume";

    /** The subcommand that lists past sessions for the repo (04-apis § 1.2). */
    static final String SESSIONS = "sessions";

    /** Which interaction shape the parsed arguments select. */
    public enum Kind {

        /** A one-shot {@code -p "<prompt>"} run (US-6). */
        ONE_SHOT,

        /** An informational request ({@code --help} / {@code --version}); print and exit 0. */
        INFO,

        /** No {@code -p}; the interactive REPL shape (the REPL is T-1.1). */
        INTERACTIVE,

        /** {@code resume [<session-id>]}: list resumable sessions or resume one (AC-7.1/7.2/7.4). */
        RESUME,

        /** {@code sessions}: list past sessions for the repository (AC-15.2). */
        SESSIONS
    }

    private final Kind kind;
    private final String prompt;
    private final String infoFlag;
    private final String sessionId;

    private CliArguments(Kind kind, String prompt, String infoFlag, String sessionId) {
        this.kind = kind;
        this.prompt = prompt;
        this.infoFlag = infoFlag;
        this.sessionId = sessionId;
    }

    /**
     * Parses the raw argument array into the recognized CLI shape.
     *
     * @param args the raw command-line arguments; {@code null} is treated as no arguments
     *             (the interactive shape) so the launcher's {@code main}-style signature
     *             stays robust.
     * @return the parsed arguments; never {@code null}.
     * @throws UsageException if {@code -p} / {@code --prompt} is given without a (non-blank)
     *                        value, an unrecognized flag is supplied, the {@code sessions}
     *                        subcommand is given an unexpected extra argument, or the
     *                        {@code resume} subcommand is given a blank id (bad invocation →
     *                        exit {@code 2}).
     */
    public static CliArguments parse(String[] args) {
        if (args == null || args.length == 0) {
            return new CliArguments(Kind.INTERACTIVE, null, null, null);
        }
        // The session subcommands are bare leading words (not flags); recognize them in the
        // subcommand position before the flag scan, which rejects bare positionals.
        if (RESUME.equals(args[0])) {
            return parseResume(args);
        }
        if (SESSIONS.equals(args[0])) {
            return parseSessions(args);
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (HELP.equals(arg) || VERSION.equals(arg)) {
                return new CliArguments(Kind.INFO, null, arg, null);
            }
            if (PROMPT_SHORT.equals(arg) || PROMPT_LONG.equals(arg)) {
                return new CliArguments(Kind.ONE_SHOT, requirePromptValue(args, i, arg), null, null);
            }
            if (arg.startsWith("-")) {
                throw new UsageException(arg, "unknown flag: " + arg);
            }
            // A bare positional argument is not part of this task's scope; reject it as a
            // usage error rather than silently dropping it (fail fast, exit 2).
            throw new UsageException(arg, "unexpected argument: " + arg);
        }
        return new CliArguments(Kind.INTERACTIVE, null, null, null);
    }

    /** Parses {@code resume} / {@code resume <session-id>} (04-apis § 1.2, AC-7.1/7.2/7.4). */
    private static CliArguments parseResume(String[] args) {
        if (args.length == 1) {
            // Bare `resume`: list resumable sessions (most-recent-first), no id selected.
            return new CliArguments(Kind.RESUME, null, null, null);
        }
        String id = args[1];
        if (id == null || id.isBlank()) {
            throw new UsageException(RESUME, "resume requires a non-blank session id");
        }
        if (args.length > 2) {
            // resume takes at most one id; an extra word is a malformed invocation.
            throw new UsageException(args[2], "unexpected argument after resume id: " + args[2]);
        }
        return new CliArguments(Kind.RESUME, null, null, id);
    }

    /** Parses the {@code sessions} subcommand (04-apis § 1.2, AC-15.2). */
    private static CliArguments parseSessions(String[] args) {
        if (args.length > 1) {
            // `sessions` takes no arguments; an extra word is a malformed invocation.
            throw new UsageException(args[1], "unexpected argument after sessions: " + args[1]);
        }
        return new CliArguments(Kind.SESSIONS, null, null, null);
    }

    private static String requirePromptValue(String[] args, int flagIndex, String flag) {
        if (flagIndex + 1 >= args.length) {
            throw new UsageException(flag, flag + " requires a prompt value");
        }
        String value = args[flagIndex + 1];
        if (value == null || value.isBlank()) {
            throw new UsageException(flag, flag + " requires a non-blank prompt value");
        }
        return value;
    }

    /**
     * The interaction shape these arguments select.
     *
     * @return the kind; never {@code null}.
     */
    public Kind kind() {
        return kind;
    }

    /**
     * The one-shot prompt text, present only for {@link Kind#ONE_SHOT}.
     *
     * @return the non-blank prompt, or {@link Optional#empty()} for any other kind.
     */
    public Optional<String> prompt() {
        return Optional.ofNullable(prompt);
    }

    /**
     * The informational flag requested, present only for {@link Kind#INFO}.
     *
     * @return {@code --help} or {@code --version}, or {@link Optional#empty()} otherwise.
     */
    public Optional<String> infoFlag() {
        return Optional.ofNullable(infoFlag);
    }

    /**
     * The session id to resume, present only for a {@link Kind#RESUME} invocation that
     * named one ({@code resume <session-id>}). A bare {@code resume} (list mode) and every
     * other kind have no id.
     *
     * @return the non-blank session id, or {@link Optional#empty()} when {@code resume} was
     *         given no id (list mode) or the kind is not {@link Kind#RESUME}.
     */
    public Optional<String> sessionId() {
        return Optional.ofNullable(sessionId);
    }
}
