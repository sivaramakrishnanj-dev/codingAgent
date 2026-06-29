package com.srk.codingagent.cli;

import com.srk.codingagent.persistence.SessionMode;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The parsed command-line arguments this task's CLI scope understands: the one-shot
 * prompt ({@code -p} / {@code --prompt}), the working-mode selection
 * ({@code --mode greenfield|brownfield}), the two standard informational flags
 * ({@code --help}, {@code --version}), the {@code --attach <path>} multimodal attachment
 * flag (T-4.2), the {@code --debug} operational-log-level flag (T-4.5, 04-apis § 1.3), and the
 * session/inspection subcommands {@code resume
 * [<session-id>]}, {@code sessions}, {@code memory [list|show <slug>|edit <slug>|rm <slug>]},
 * and {@code config [show|path]} (04-apis § 1.2). Parsing is deliberately narrow —
 * the rest of the proposed flag set ({@code --model} / {@code --permission-mode}
 * / {@code --profile} / {@code --region}) belongs to later tasks — but an
 * unrecognized flag or an unexpected positional is rejected as a usage error so a bad
 * invocation fails fast with exit {@code 2} (04-apis § 1.1, cli-exit-codes {@code 2})
 * rather than being silently ignored.
 *
 * <p><b>{@code --debug} (T-4.5; 04-apis § 1.3, 05-operations § 3).</b> The {@code --debug} flag
 * raises the operational log level to {@code DEBUG} (DEBUG-level internals to stderr); it is
 * orthogonal to {@link Kind} (it continues the scan rather than selecting a kind, like
 * {@code --mode} and {@code --attach}) and {@link #debug()} reports whether it was given. The
 * actual level toggle is applied by {@link DebugLogging} at process startup (before any logger
 * binds); recognizing the flag here keeps {@link Main#run(String[])} from rejecting it as an
 * unknown flag.
 *
 * <p><b>Attachments (T-4.2; § 2.3 multimodal input).</b> The {@code --attach <path>} flag
 * supplies a one-shot multimodal attachment (an image or document the developer shares with the
 * model). Like {@code --mode}, it is orthogonal to {@link Kind} — it continues the scan rather
 * than selecting a kind — and applies to the agent-running shapes ({@link Kind#ONE_SHOT},
 * {@link Kind#INTERACTIVE}). {@link #attachPath()} reports the supplied path; the launcher hands
 * it to the {@link AttachmentResolver} (format inference, INV-18 sanitization, INV-19 capability
 * gate). A {@code --attach} with no value is a fail-fast usage error (exit {@code 2}); the
 * REPL's {@code /attach <path>} slash-command is the interactive counterpart.
 *
 * <p>The result is one of several shapes, distinguished by {@link #kind()}:
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
 *   <li>{@link Kind#MEMORY} — {@code memory [list|show <slug>|edit <slug>|rm <slug>]};
 *       inspect / curate curated memory (US-14, AC-14.1/14.3). {@link #memoryAction()} and
 *       {@link #memorySlug()} hold the action and its optional slug.</li>
 *   <li>{@link Kind#CONFIG} — {@code config [show|path]}; show the resolved config or the
 *       config file locations (US-8). {@link #configAction()} holds the action.</li>
 * </ul>
 *
 * <p><b>Working mode (ADR-0012, T-3.1).</b> The {@code --mode greenfield|brownfield} flag
 * selects the session's workflow driver; it is orthogonal to {@link Kind} (it applies to the
 * one-shot and interactive shapes that run the agent, not to the informational flags or the
 * session subcommands). {@link #mode()} reports the selected {@link SessionMode}; brownfield
 * is the implicit default when {@code --mode} is absent (the one-shot/REPL/brownfield path the
 * earlier milestones built). "Mode is fixed for a session" (02-architecture § 1.2, C3 key
 * invariant), so the flag is parsed once here and never changes mid-session. An unknown mode
 * value is a fail-fast usage error (exit {@code 2}).
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

    /** The flag that selects the session's working mode (04-apis § 1.3, ADR-0012). */
    static final String MODE = "--mode";

    /** The {@code --mode} value selecting the greenfield workflow driver (ADR-0012). */
    static final String MODE_GREENFIELD = "greenfield";

    /** The {@code --mode} value selecting the brownfield workflow driver (the default). */
    static final String MODE_BROWNFIELD = "brownfield";

    /** The flag that supplies a one-shot multimodal attachment path (T-4.2, § 2.3). */
    static final String ATTACH = "--attach";

    /** The flag that requests usage help. */
    static final String HELP = "--help";

    /** The flag that requests the version. */
    static final String VERSION = "--version";

    /** The subcommand that lists resumable sessions or resumes one (04-apis § 1.2). */
    static final String RESUME = "resume";

    /** The subcommand that lists past sessions for the repo (04-apis § 1.2). */
    static final String SESSIONS = "sessions";

    /** The subcommand that inspects / curates curated memory (04-apis § 1.2, US-14). */
    static final String MEMORY = "memory";

    /** The subcommand that shows the resolved config / file locations (04-apis § 1.2, US-8). */
    static final String CONFIG = "config";

    /** The flag that raises operational logging to DEBUG (04-apis § 1.3, T-4.5). */
    static final String DEBUG = DebugLogging.DEBUG;

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
        SESSIONS,

        /** {@code memory [list|show <slug>|edit <slug>|rm <slug>]}: inspect / curate memory (US-14). */
        MEMORY,

        /** {@code config [show|path]}: show resolved config / file locations (US-8). */
        CONFIG
    }

    /**
     * The action of a {@code memory} subcommand invocation (04-apis § 1.2, US-14). Bare
     * {@code memory} defaults to {@link #LIST} (the always-loaded awareness surface).
     */
    public enum MemoryAction {

        /** {@code memory} / {@code memory list}: print the two-tier memory index (AC-14.3). */
        LIST,

        /** {@code memory show <slug>}: print a memory entry's markdown (AC-14.1). */
        SHOW,

        /** {@code memory edit <slug>}: resolve the entry's on-disk path for hand-editing (AC-14.1). */
        EDIT,

        /** {@code memory rm <slug>}: remove a memory entry and its index line (AC-14.1/14.3). */
        RM
    }

    /**
     * The action of a {@code config} subcommand invocation (04-apis § 1.2, US-8). Bare
     * {@code config} defaults to {@link #SHOW} (the resolved configuration).
     */
    public enum ConfigAction {

        /** {@code config} / {@code config show}: print the resolved configuration (US-8). */
        SHOW,

        /** {@code config path}: print the config file locations (US-8). */
        PATH
    }

    private final Kind kind;
    private final String prompt;
    private final String infoFlag;
    private final String sessionId;
    private final SessionMode mode;
    private final String attachPath;
    private final boolean debug;
    private final MemoryAction memoryAction;
    private final String memorySlug;
    private final ConfigAction configAction;

    private CliArguments(Builder b) {
        this.kind = b.kind;
        this.prompt = b.prompt;
        this.infoFlag = b.infoFlag;
        this.sessionId = b.sessionId;
        this.mode = b.mode;
        this.attachPath = b.attachPath;
        this.debug = b.debug;
        this.memoryAction = b.memoryAction;
        this.memorySlug = b.memorySlug;
        this.configAction = b.configAction;
    }

    /**
     * Accumulates the parsed fields before the immutable {@link CliArguments} is built. The
     * field set has outgrown a positional constructor (EJ Item 2): the orthogonal flags
     * ({@code --mode}, {@code --attach}, {@code --debug}) cut across the kind-selecting shapes, so
     * the parser collects them as it scans, then stamps the selected {@link Kind} and any
     * subcommand action/slug. Defaults match a no-argument interactive brownfield invocation.
     */
    private static final class Builder {
        private Kind kind = Kind.INTERACTIVE;
        private String prompt;
        private String infoFlag;
        private String sessionId;
        private SessionMode mode = SessionMode.BROWNFIELD;
        private String attachPath;
        private boolean debug;
        private MemoryAction memoryAction;
        private String memorySlug;
        private ConfigAction configAction;

        private Builder kind(Kind k) {
            this.kind = k;
            return this;
        }

        private CliArguments build() {
            return new CliArguments(this);
        }
    }

    /**
     * Parses the raw argument array into the recognized CLI shape.
     *
     * @param args the raw command-line arguments; {@code null} is treated as no arguments
     *             (the interactive shape, brownfield mode) so the launcher's {@code main}-style
     *             signature stays robust.
     * @return the parsed arguments; never {@code null}.
     * @throws UsageException if {@code -p} / {@code --prompt} is given without a (non-blank)
     *                        value, {@code --mode} is given without a recognized value,
     *                        {@code --attach} is given without a (non-blank) path, an
     *                        unrecognized flag is supplied, a subcommand
     *                        ({@code resume}/{@code sessions}/{@code memory}/{@code config}) is
     *                        given an unexpected or missing argument (bad invocation → exit
     *                        {@code 2}).
     */
    public static CliArguments parse(String[] args) {
        if (args == null || args.length == 0) {
            return new Builder().build();
        }
        // The subcommands are bare leading words (not flags); recognize them in the subcommand
        // position before the flag scan, which rejects bare positionals.
        if (RESUME.equals(args[0])) {
            return parseResume(args);
        }
        if (SESSIONS.equals(args[0])) {
            return parseSessions(args);
        }
        if (MEMORY.equals(args[0])) {
            return parseMemory(args);
        }
        if (CONFIG.equals(args[0])) {
            return parseConfig(args);
        }
        // The flag scan: --mode sets the working mode, --attach sets the attachment path, and
        // --debug raises the log level; all three continue scanning (they are orthogonal to the
        // kind). -p / --help / --version are terminal kind selectors. brownfield is the implicit
        // default when --mode is absent.
        Builder b = new Builder();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (MODE.equals(arg)) {
                b.mode = requireModeValue(args, i, arg);
                i++; // consume the mode value as well
                continue;
            }
            if (ATTACH.equals(arg)) {
                b.attachPath = requireAttachValue(args, i, arg);
                i++; // consume the attachment path as well
                continue;
            }
            if (DEBUG.equals(arg)) {
                b.debug = true;
                continue;
            }
            if (HELP.equals(arg) || VERSION.equals(arg)) {
                b.infoFlag = arg;
                return b.kind(Kind.INFO).build();
            }
            if (PROMPT_SHORT.equals(arg) || PROMPT_LONG.equals(arg)) {
                b.prompt = requirePromptValue(args, i, arg);
                return b.kind(Kind.ONE_SHOT).build();
            }
            if (arg.startsWith("-")) {
                throw new UsageException(arg, "unknown flag: " + arg);
            }
            // A bare positional argument is not part of this task's scope; reject it as a
            // usage error rather than silently dropping it (fail fast, exit 2).
            throw new UsageException(arg, "unexpected argument: " + arg);
        }
        // No -p among the (mode/attach/debug-only) flags: the interactive REPL shape, in the
        // selected mode, carrying any --attach path and the --debug state supplied.
        return b.kind(Kind.INTERACTIVE).build();
    }

    /** Parses {@code resume} / {@code resume <session-id>} (04-apis § 1.2, AC-7.1/7.2/7.4). */
    private static CliArguments parseResume(String[] args) {
        if (args.length == 1) {
            // Bare `resume`: list resumable sessions (most-recent-first), no id selected.
            return new Builder().kind(Kind.RESUME).build();
        }
        String id = args[1];
        if (id == null || id.isBlank()) {
            throw new UsageException(RESUME, "resume requires a non-blank session id");
        }
        if (args.length > 2) {
            // resume takes at most one id; an extra word is a malformed invocation.
            throw new UsageException(args[2], "unexpected argument after resume id: " + args[2]);
        }
        Builder b = new Builder().kind(Kind.RESUME);
        b.sessionId = id;
        return b.build();
    }

    /** Parses the {@code sessions} subcommand (04-apis § 1.2, AC-15.2). */
    private static CliArguments parseSessions(String[] args) {
        if (args.length > 1) {
            // `sessions` takes no arguments; an extra word is a malformed invocation.
            throw new UsageException(args[1], "unexpected argument after sessions: " + args[1]);
        }
        return new Builder().kind(Kind.SESSIONS).build();
    }

    /**
     * Parses {@code memory [list|show <slug>|edit <slug>|rm <slug>]} (04-apis § 1.2, US-14). Bare
     * {@code memory} is {@link MemoryAction#LIST} (the always-loaded awareness surface, AC-14.3);
     * {@code show}/{@code edit}/{@code rm} each require a non-blank slug; an unknown action or a
     * missing/extra argument is a fail-fast usage error (exit {@code 2}, G2).
     */
    private static CliArguments parseMemory(String[] args) {
        Builder b = new Builder().kind(Kind.MEMORY);
        if (args.length == 1) {
            b.memoryAction = MemoryAction.LIST;
            return b.build();
        }
        String action = args[1];
        if ("list".equals(action)) {
            if (args.length > 2) {
                throw new UsageException(args[2], "memory list takes no argument: " + args[2]);
            }
            b.memoryAction = MemoryAction.LIST;
            return b.build();
        }
        MemoryAction slugAction = slugMemoryAction(action);
        b.memoryAction = slugAction;
        b.memorySlug = requireSubcommandArg(args, action, "a slug");
        return b.build();
    }

    private static MemoryAction slugMemoryAction(String action) {
        return switch (action) {
            case "show" -> MemoryAction.SHOW;
            case "edit" -> MemoryAction.EDIT;
            case "rm" -> MemoryAction.RM;
            default -> throw new UsageException(action,
                    "unknown memory action: " + action + " (expected list|show|edit|rm)");
        };
    }

    /**
     * Resolves the slug argument of a {@code memory show|edit|rm} invocation: it must be present,
     * non-blank, and the only argument after the action (a missing slug or an extra word is a
     * fail-fast usage error naming the action, G2).
     */
    private static String requireSubcommandArg(String[] args, String action, String what) {
        if (args.length < 3) {
            throw new UsageException(action, "memory " + action + " requires " + what);
        }
        String value = args[2];
        if (value == null || value.isBlank()) {
            throw new UsageException(action, "memory " + action + " requires a non-blank slug");
        }
        if (args.length > 3) {
            throw new UsageException(args[3], "unexpected argument after memory " + action + ": " + args[3]);
        }
        return value;
    }

    /**
     * Parses {@code config [show|path]} (04-apis § 1.2, US-8). Bare {@code config} is
     * {@link ConfigAction#SHOW} (the resolved configuration); {@code path} prints the file
     * locations; an unknown action or an extra argument is a fail-fast usage error (exit
     * {@code 2}, G2).
     */
    private static CliArguments parseConfig(String[] args) {
        Builder b = new Builder().kind(Kind.CONFIG);
        if (args.length == 1) {
            b.configAction = ConfigAction.SHOW;
            return b.build();
        }
        if (args.length > 2) {
            throw new UsageException(args[2], "unexpected argument after config: " + args[2]);
        }
        b.configAction = switch (args[1]) {
            case "show" -> ConfigAction.SHOW;
            case "path" -> ConfigAction.PATH;
            default -> throw new UsageException(args[1],
                    "unknown config action: " + args[1] + " (expected show|path)");
        };
        return b.build();
    }

    /**
     * Resolves the value of {@code --mode}: {@code greenfield} or {@code brownfield} (ADR-0012).
     * A missing value or an unrecognized one is a fail-fast usage error naming {@code --mode}
     * (exit {@code 2}, G2).
     */
    private static SessionMode requireModeValue(String[] args, int flagIndex, String flag) {
        if (flagIndex + 1 >= args.length) {
            throw new UsageException(flag, flag + " requires a mode value (greenfield|brownfield)");
        }
        String value = args[flagIndex + 1];
        if (value == null || value.isBlank()) {
            throw new UsageException(
                    flag, flag + " requires a non-blank mode value (greenfield|brownfield)");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (MODE_GREENFIELD.equals(normalized)) {
            return SessionMode.GREENFIELD;
        }
        if (MODE_BROWNFIELD.equals(normalized)) {
            return SessionMode.BROWNFIELD;
        }
        throw new UsageException(value, "unknown mode: " + value + " (expected greenfield|brownfield)");
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
     * Resolves the value of {@code --attach}: the attachment path (T-4.2, § 2.3). A missing or
     * blank path is a fail-fast usage error naming {@code --attach} (exit {@code 2}, G2); the
     * path is not otherwise validated here (format inference, INV-18 sanitization, and the INV-19
     * capability gate are the {@link AttachmentResolver}'s job, applied after config resolves the
     * model and its capability profile).
     */
    private static String requireAttachValue(String[] args, int flagIndex, String flag) {
        if (flagIndex + 1 >= args.length) {
            throw new UsageException(flag, flag + " requires a path value");
        }
        String value = args[flagIndex + 1];
        if (value == null || value.isBlank()) {
            throw new UsageException(flag, flag + " requires a non-blank path value");
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

    /**
     * The session's working mode selected by {@code --mode} (ADR-0012, T-3.1):
     * {@link SessionMode#GREENFIELD} for {@code --mode greenfield}, else
     * {@link SessionMode#BROWNFIELD} (the implicit default when {@code --mode} is absent). Always
     * present and never {@code null}; the launcher dispatches the agent-running shapes
     * ({@link Kind#ONE_SHOT}, {@link Kind#INTERACTIVE}) to the matching workflow driver. For the
     * informational flags and the session subcommands the value is the default and unused.
     *
     * @return the selected working mode; never {@code null}.
     */
    public SessionMode mode() {
        return mode;
    }

    /**
     * The one-shot multimodal attachment path supplied via {@code --attach <path>} (T-4.2,
     * § 2.3). Present for any kind that scanned a {@code --attach} flag (typically
     * {@link Kind#ONE_SHOT} or {@link Kind#INTERACTIVE}); absent when {@code --attach} was not
     * given. The launcher hands the path to the {@link AttachmentResolver}, which infers the
     * format, sanitizes a document name (INV-18), and applies the capability gate (INV-19).
     *
     * @return the attachment path, or {@link Optional#empty()} when no {@code --attach} was given.
     */
    public Optional<String> attachPath() {
        return Optional.ofNullable(attachPath);
    }

    /**
     * Whether {@code --debug} was given (04-apis § 1.3, 05-operations § 3): the operational log
     * level is raised to {@code DEBUG} for the run. Orthogonal to {@link #kind()} — any invocation
     * may carry it. The level toggle itself is applied by {@link DebugLogging} at process startup,
     * before any logger binds; this accessor reports the parsed state for completeness.
     *
     * @return {@code true} when {@code --debug} was supplied.
     */
    public boolean debug() {
        return debug;
    }

    /**
     * The {@code memory} subcommand action, present only for {@link Kind#MEMORY} (04-apis § 1.2,
     * US-14): {@link MemoryAction#LIST} for bare {@code memory} / {@code memory list}, else the
     * named action ({@code show}/{@code edit}/{@code rm}).
     *
     * @return the memory action, or {@link Optional#empty()} when the kind is not
     *         {@link Kind#MEMORY}.
     */
    public Optional<MemoryAction> memoryAction() {
        return Optional.ofNullable(memoryAction);
    }

    /**
     * The slug a {@code memory show|edit|rm <slug>} invocation names (04-apis § 1.2, US-14).
     * Absent for {@code memory list} and every non-{@link Kind#MEMORY} kind.
     *
     * @return the non-blank slug, or {@link Optional#empty()} when no slug was given.
     */
    public Optional<String> memorySlug() {
        return Optional.ofNullable(memorySlug);
    }

    /**
     * The {@code config} subcommand action, present only for {@link Kind#CONFIG} (04-apis § 1.2,
     * US-8): {@link ConfigAction#SHOW} for bare {@code config} / {@code config show}, or
     * {@link ConfigAction#PATH} for {@code config path}.
     *
     * @return the config action, or {@link Optional#empty()} when the kind is not
     *         {@link Kind#CONFIG}.
     */
    public Optional<ConfigAction> configAction() {
        return Optional.ofNullable(configAction);
    }
}
