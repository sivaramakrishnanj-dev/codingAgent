package com.srk.codingagent.cli;

import java.util.Objects;
import java.util.Optional;

/**
 * The parsed command-line arguments this task's CLI scope understands: the one-shot
 * prompt ({@code -p} / {@code --prompt}) and the two standard informational flags
 * ({@code --help}, {@code --version}). Parsing is deliberately narrow — the full proposed
 * flag set ({@code --mode} / {@code --model} / {@code --permission-mode} / {@code --profile}
 * / {@code --region} / {@code --attach} / {@code --debug}) belongs to later tasks (04-apis
 * § 1.3) — but an unrecognized flag is rejected as a usage error so a bad invocation fails
 * fast with exit {@code 2} (04-apis § 1.1, cli-exit-codes {@code 2}) rather than being
 * silently ignored.
 *
 * <p>The result is one of three shapes, distinguished by {@link #kind()}:
 * <ul>
 *   <li>{@link Kind#ONE_SHOT} — a {@code -p "<prompt>"} invocation; {@link #prompt()} holds
 *       the (non-blank) prompt text. Runs to {@code end_turn} then exits (US-6).</li>
 *   <li>{@link Kind#INFO} — {@code --help} or {@code --version}; the CLI prints the
 *       requested information and exits {@code 0}.</li>
 *   <li>{@link Kind#INTERACTIVE} — no {@code -p}; the interactive REPL would start. The REPL
 *       itself is T-1.1, so this task only recognizes the shape.</li>
 * </ul>
 *
 * <p>Parsing never starts the agent and never touches configuration; it is the pure
 * arg-shape decision the launcher acts on. A malformed invocation throws
 * {@link UsageException}, which the launcher maps to exit {@code 2} naming the offending
 * argument (G2).
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

    /** Which interaction shape the parsed arguments select. */
    public enum Kind {

        /** A one-shot {@code -p "<prompt>"} run (US-6). */
        ONE_SHOT,

        /** An informational request ({@code --help} / {@code --version}); print and exit 0. */
        INFO,

        /** No {@code -p}; the interactive REPL shape (the REPL is T-1.1). */
        INTERACTIVE
    }

    private final Kind kind;
    private final String prompt;
    private final String infoFlag;

    private CliArguments(Kind kind, String prompt, String infoFlag) {
        this.kind = kind;
        this.prompt = prompt;
        this.infoFlag = infoFlag;
    }

    /**
     * Parses the raw argument array into the recognized CLI shape.
     *
     * @param args the raw command-line arguments; {@code null} is treated as no arguments
     *             (the interactive shape) so the launcher's {@code main}-style signature
     *             stays robust.
     * @return the parsed arguments; never {@code null}.
     * @throws UsageException if {@code -p} / {@code --prompt} is given without a (non-blank)
     *                        value, or an unrecognized flag is supplied (bad invocation →
     *                        exit {@code 2}).
     */
    public static CliArguments parse(String[] args) {
        if (args == null || args.length == 0) {
            return new CliArguments(Kind.INTERACTIVE, null, null);
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (HELP.equals(arg) || VERSION.equals(arg)) {
                return new CliArguments(Kind.INFO, null, arg);
            }
            if (PROMPT_SHORT.equals(arg) || PROMPT_LONG.equals(arg)) {
                return new CliArguments(Kind.ONE_SHOT, requirePromptValue(args, i, arg), null);
            }
            if (arg.startsWith("-")) {
                throw new UsageException(arg, "unknown flag: " + arg);
            }
            // A bare positional argument is not part of this task's scope; reject it as a
            // usage error rather than silently dropping it (fail fast, exit 2).
            throw new UsageException(arg, "unexpected argument: " + arg);
        }
        return new CliArguments(Kind.INTERACTIVE, null, null);
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
}
