package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.SessionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CliArguments} — the narrow arg parser for this task's CLI scope:
 * the one-shot prompt ({@code -p} / {@code --prompt}), the informational flags
 * ({@code --help} / {@code --version}), the interactive (no-{@code -p}) shape, and the
 * usage-error rejections that fail fast with exit {@code 2}.
 *
 * <p>Oracles trace to 04-apis § 1.1/§ 1.3 and the exit-code contract:
 * <ul>
 *   <li><b>04-apis § 1.1:</b> {@code codingagent -p "<prompt>"} is the one-shot shape;
 *       {@code codingagent} (no {@code -p}) is the interactive shape (US-6).</li>
 *   <li><b>cli-exit-codes {@code 2} / § 3.2:</b> "bad CLI args → exit 2" — an unknown flag
 *       or a missing prompt value is a usage error (named via the offending argument).</li>
 * </ul>
 */
class CliArgumentsTest {

    @Test
    @DisplayName("04-apis § 1.1: -p \"<prompt>\" is parsed as a one-shot with the prompt text")
    void shortPromptFlagParsesOneShot() {
        // Oracle: 04-apis § 1.1 — "One-shot = codingagent -p \"<prompt>\"". The parser must
        // classify a -p invocation as ONE_SHOT carrying the prompt value.
        CliArguments parsed = CliArguments.parse(new String[] {"-p", "fix the bug"});

        assertEquals(CliArguments.Kind.ONE_SHOT, parsed.kind(),
                "04-apis § 1.1: -p selects the one-shot shape");
        assertEquals("fix the bug", parsed.prompt().orElseThrow(),
                "the one-shot prompt is the value after -p");
    }

    @Test
    @DisplayName("04-apis § 1.3: --prompt is the long form of -p")
    void longPromptFlagParsesOneShot() {
        // Oracle: 04-apis § 1.3 — "-p, --prompt <text>": the long form selects the same shape.
        CliArguments parsed = CliArguments.parse(new String[] {"--prompt", "run tests"});

        assertEquals(CliArguments.Kind.ONE_SHOT, parsed.kind());
        assertEquals("run tests", parsed.prompt().orElseThrow(),
                "--prompt carries the same one-shot prompt value as -p");
    }

    @Test
    @DisplayName("T-4.2: --attach <path> on a one-shot carries the attachment path")
    void attachOnOneShotCarriesPath() {
        // Oracle: § 2.3 multimodal input / T-4.2 — --attach <path> supplies a one-shot attachment
        // path orthogonal to the kind; the parser carries it alongside the one-shot prompt.
        CliArguments parsed = CliArguments.parse(
                new String[] {"--attach", "design/diagram.png", "-p", "review this"});

        assertEquals(CliArguments.Kind.ONE_SHOT, parsed.kind(),
                "--attach does not change the kind (it is orthogonal, like --mode)");
        assertEquals("review this", parsed.prompt().orElseThrow(), "the one-shot prompt is parsed");
        assertEquals("design/diagram.png", parsed.attachPath().orElseThrow(),
                "T-4.2: --attach carries the attachment path");
    }

    @Test
    @DisplayName("T-4.2: --attach <path> with no -p carries the path on the interactive shape")
    void attachOnInteractiveCarriesPath() {
        // Oracle: T-4.2 — --attach applies to the agent-running shapes; with no -p the shape is
        // interactive and still carries the attachment path.
        CliArguments parsed = CliArguments.parse(new String[] {"--attach", "spec.pdf"});

        assertEquals(CliArguments.Kind.INTERACTIVE, parsed.kind(),
                "no -p with --attach is the interactive shape");
        assertEquals("spec.pdf", parsed.attachPath().orElseThrow(),
                "T-4.2: the interactive shape carries the --attach path");
    }

    @Test
    @DisplayName("T-4.2: no --attach means no attachment path")
    void noAttachMeansEmpty() {
        // Oracle: T-4.2 — --attach is optional; absent, there is no attachment path.
        CliArguments parsed = CliArguments.parse(new String[] {"-p", "no attachment here"});

        assertTrue(parsed.attachPath().isEmpty(),
                "an invocation without --attach carries no attachment path");
    }

    @Test
    @DisplayName("cli-exit-codes 2: --attach with no value is a fail-fast usage error")
    void attachWithoutValueIsUsageError() {
        // Oracle: cli-exit-codes 2 / § 3.2 — a flag missing its required value is a bad invocation
        // (exit 2). --attach with no following path is rejected, like -p with no prompt.
        assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"--attach"}),
                "--attach requires a path value (exit 2)");
    }

    @Test
    @DisplayName("cli-exit-codes 2: --attach with a blank value is a fail-fast usage error")
    void attachWithBlankValueIsUsageError() {
        // Oracle: cli-exit-codes 2 — a blank path is not a usable attachment reference; reject it
        // as a usage error rather than carrying an empty path.
        assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"--attach", "  ", "-p", "x"}),
                "--attach requires a non-blank path value (exit 2)");
    }

    @Test
    @DisplayName("T-4.2: --attach coexists with --mode (both orthogonal to the kind)")
    void attachCoexistsWithMode() {
        // Oracle: T-4.2 / ADR-0012 — both --attach and --mode are orthogonal flags that continue
        // the scan; they coexist and the kind is still selected by -p.
        CliArguments parsed = CliArguments.parse(new String[] {
                "--mode", "greenfield", "--attach", "use-case.pdf", "-p", "build it"});

        assertEquals(CliArguments.Kind.ONE_SHOT, parsed.kind());
        assertEquals(SessionMode.GREENFIELD, parsed.mode(), "--mode is still parsed");
        assertEquals("use-case.pdf", parsed.attachPath().orElseThrow(),
                "--attach is still parsed alongside --mode");
    }

    @Test
    @DisplayName("04-apis § 1.1: no -p selects the interactive (REPL) shape")
    void noPromptFlagIsInteractive() {
        // Oracle: 04-apis § 1.1 — "Interactive (REPL) = codingagent [options]". No -p means the
        // interactive shape (the REPL is T-1.1; this task only recognizes the shape).
        assertEquals(CliArguments.Kind.INTERACTIVE, CliArguments.parse(new String[] {}).kind(),
                "no arguments selects the interactive shape");
    }

    @Test
    @DisplayName("null args are treated as no arguments (interactive), not a crash")
    void nullArgsIsInteractive() {
        // Oracle: the launcher's main-style signature admits null; parsing must be robust and
        // treat it as the no-argument (interactive) shape.
        assertEquals(CliArguments.Kind.INTERACTIVE, CliArguments.parse(null).kind(),
                "null args parse as the interactive shape");
    }

    @Test
    @DisplayName("04-apis § 1.3: --help is an informational request")
    void helpIsInfo() {
        // Oracle: 04-apis § 1.3 — "--version / --help: standard". --help selects the INFO shape.
        CliArguments parsed = CliArguments.parse(new String[] {"--help"});

        assertEquals(CliArguments.Kind.INFO, parsed.kind(), "--help selects the informational shape");
        assertEquals("--help", parsed.infoFlag().orElseThrow(),
                "the requested informational flag is reported");
    }

    @Test
    @DisplayName("04-apis § 1.3: --version is an informational request")
    void versionIsInfo() {
        // Oracle: 04-apis § 1.3 — "--version / --help: standard".
        CliArguments parsed = CliArguments.parse(new String[] {"--version"});

        assertEquals(CliArguments.Kind.INFO, parsed.kind());
        assertEquals("--version", parsed.infoFlag().orElseThrow());
    }

    @Test
    @DisplayName("cli-exit-codes 2: an unknown flag is a usage error naming the flag")
    void unknownFlagIsUsageError() {
        // Oracle: cli-exit-codes "2 usage/config — unknown flag" / § 3.2 "bad CLI args → exit 2".
        // An unrecognized flag must be rejected (not silently ignored), naming the flag (G2).
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"--frobnicate"}));

        assertEquals("--frobnicate", thrown.offendingArgument(),
                "the usage error names the offending flag (G2)");
        assertTrue(thrown.getMessage().contains("--frobnicate"),
                "the message names the unknown flag; was: " + thrown.getMessage());
    }

    @Test
    @DisplayName("cli-exit-codes 2: -p without a value is a usage error naming the flag")
    void promptWithoutValueIsUsageError() {
        // Oracle: cli-exit-codes "2 usage/config — bad invocation detected BEFORE doing work".
        // A -p with no following value is a malformed invocation; reject it naming -p (G2).
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"-p"}));

        assertEquals("-p", thrown.offendingArgument(),
                "the usage error names the prompt flag missing its value (G2)");
    }

    @Test
    @DisplayName("cli-exit-codes 2: -p with a blank value is a usage error")
    void promptWithBlankValueIsUsageError() {
        // Oracle: 04-apis § 1.1 — a one-shot runs a prompt; a blank prompt is not a runnable
        // invocation, so it is rejected as a usage error (fail fast, exit 2) rather than
        // starting a run on empty input.
        assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"-p", "   "}),
                "a blank prompt value is a usage error");
    }

    @Test
    @DisplayName("cli-exit-codes 2: an unrecognized bare positional argument is a usage error")
    void positionalArgumentIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". A bare positional that is not a recognized
        // subcommand (resume/sessions are; an arbitrary word is not) is rejected naming it.
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"frobnicate"}));

        assertEquals("frobnicate", thrown.offendingArgument(),
                "the usage error names the unexpected positional argument");
    }

    @Test
    @DisplayName("04-apis § 1.2: bare `resume` is the resume-list shape (no session id)")
    void bareResumeIsResumeListShape() {
        // Oracle: 04-apis § 1.2 — "codingagent resume [<session-id>]: list resumable sessions
        // ... or resume one". With no id, resume is the list shape (AC-7.1).
        CliArguments parsed = CliArguments.parse(new String[] {"resume"});

        assertEquals(CliArguments.Kind.RESUME, parsed.kind(), "resume selects the RESUME shape");
        assertTrue(parsed.sessionId().isEmpty(), "bare resume carries no session id (list mode)");
    }

    @Test
    @DisplayName("04-apis § 1.2: `resume <id>` carries the session id to resume (AC-7.2)")
    void resumeWithIdCarriesSessionId() {
        // Oracle: 04-apis § 1.2 — "resume one"; AC-7.2 reconstructs the selected session. The id
        // after `resume` is the session to resume.
        CliArguments parsed = CliArguments.parse(new String[] {"resume", "2026-06-17T090000Z-abc"});

        assertEquals(CliArguments.Kind.RESUME, parsed.kind());
        assertEquals("2026-06-17T090000Z-abc", parsed.sessionId().orElseThrow(),
                "resume <id> selects the session to resume");
    }

    @Test
    @DisplayName("04-apis § 1.2: `sessions` is the session-list shape (AC-15.2)")
    void sessionsIsListShape() {
        // Oracle: 04-apis § 1.2 — "codingagent sessions: list past sessions for this repo". The
        // sessions subcommand selects the SESSIONS shape.
        CliArguments parsed = CliArguments.parse(new String[] {"sessions"});

        assertEquals(CliArguments.Kind.SESSIONS, parsed.kind(), "sessions selects the SESSIONS shape");
        assertTrue(parsed.sessionId().isEmpty(), "sessions carries no session id");
    }

    @Test
    @DisplayName("cli-exit-codes 2: `sessions` with an extra argument is a usage error")
    void sessionsWithExtraArgIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". `sessions` takes no arguments; an extra word is
        // a malformed invocation, named for G2.
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"sessions", "extra"}));

        assertEquals("extra", thrown.offendingArgument(),
                "the usage error names the unexpected argument after sessions");
    }

    @Test
    @DisplayName("cli-exit-codes 2: `resume` with a blank id is a usage error naming resume")
    void resumeWithBlankIdIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". A `resume ""` / `resume "   "` names no runnable
        // session; reject it as a usage error (fail fast) naming the subcommand for G2.
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"resume", "   "}));

        assertEquals("resume", thrown.offendingArgument(),
                "the usage error names the resume subcommand for the blank id");
    }

    @Test
    @DisplayName("cli-exit-codes 2: `resume <id> <extra>` is a usage error (resume takes at most one id)")
    void resumeWithExtraArgIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". resume takes at most one id; a second positional
        // is malformed.
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"resume", "id-1", "id-2"}));

        assertEquals("id-2", thrown.offendingArgument(),
                "the usage error names the unexpected extra argument after the resume id");
    }

    // --- 04-apis § 1.3 / ADR-0012 : --mode selects the workflow driver (T-3.1) -------------------

    @Test
    @DisplayName("04-apis § 1.3/ADR-0012: brownfield is the implicit default mode when --mode is absent")
    void defaultModeIsBrownfield() {
        // Oracle: 04-apis § 1.1 ("start interactive session (mode from config/flag)") + ADR-0012 —
        // brownfield (the understand->change->verify path the earlier milestones built) is the
        // implicit default when --mode is not given. Every shape without --mode reports BROWNFIELD.
        assertEquals(SessionMode.BROWNFIELD, CliArguments.parse(new String[] {}).mode(),
                "no --mode → the interactive shape is brownfield by default");
        assertEquals(SessionMode.BROWNFIELD,
                CliArguments.parse(new String[] {"-p", "fix the bug"}).mode(),
                "no --mode → a one-shot run is brownfield by default");
    }

    @Test
    @DisplayName("04-apis § 1.3/ADR-0012: --mode greenfield selects the greenfield workflow driver")
    void modeGreenfieldSelectsGreenfield() {
        // Oracle: 04-apis § 1.3 "--mode <greenfield|brownfield>: workflow mode" (US-1..5) + ADR-0012.
        // --mode greenfield must select the GREENFIELD workflow for the agent-running shapes
        // (one-shot and interactive), orthogonal to the Kind.
        CliArguments oneShot = CliArguments.parse(new String[] {"--mode", "greenfield", "-p", "build a CLI"});
        assertEquals(CliArguments.Kind.ONE_SHOT, oneShot.kind(),
                "--mode is orthogonal to the kind; -p still selects the one-shot shape");
        assertEquals(SessionMode.GREENFIELD, oneShot.mode(),
                "04-apis § 1.3: --mode greenfield selects the greenfield workflow");

        CliArguments interactive = CliArguments.parse(new String[] {"--mode", "greenfield"});
        assertEquals(CliArguments.Kind.INTERACTIVE, interactive.kind(),
                "--mode with no -p is the interactive shape");
        assertEquals(SessionMode.GREENFIELD, interactive.mode(),
                "04-apis § 1.3: an interactive greenfield session is selected by --mode greenfield");
    }

    @Test
    @DisplayName("04-apis § 1.3: --mode brownfield selects the brownfield workflow explicitly")
    void modeBrownfieldSelectsBrownfield() {
        // Oracle: 04-apis § 1.3 — brownfield is an explicit, recognized --mode value (not only the
        // implicit default). --mode brownfield selects BROWNFIELD.
        CliArguments parsed = CliArguments.parse(new String[] {"--mode", "brownfield", "-p", "fix it"});

        assertEquals(SessionMode.BROWNFIELD, parsed.mode(),
                "04-apis § 1.3: --mode brownfield selects the brownfield workflow explicitly");
    }

    @Test
    @DisplayName("04-apis § 1.3: --mode is case-insensitive in its value")
    void modeValueIsCaseInsensitive() {
        // Oracle: 04-apis § 1.3 — the mode value names the workflow; a case difference is the same
        // workflow (the parser normalizes the value). GREENFIELD and Greenfield select greenfield.
        assertEquals(SessionMode.GREENFIELD,
                CliArguments.parse(new String[] {"--mode", "GREENFIELD"}).mode(),
                "--mode value matching is case-insensitive");
        assertEquals(SessionMode.GREENFIELD,
                CliArguments.parse(new String[] {"--mode", "Greenfield"}).mode());
    }

    @Test
    @DisplayName("cli-exit-codes 2: --mode without a value is a usage error naming --mode")
    void modeWithoutValueIsUsageError() {
        // Oracle: cli-exit-codes "2 usage/config — bad invocation detected BEFORE doing work". A
        // --mode with no following value is malformed; reject it naming --mode (G2).
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"--mode"}));

        assertEquals("--mode", thrown.offendingArgument(),
                "the usage error names the --mode flag missing its value (G2)");
    }

    @Test
    @DisplayName("cli-exit-codes 2: an unknown --mode value is a usage error naming the bad value")
    void unknownModeValueIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". A --mode value that is neither greenfield nor
        // brownfield is rejected fail-fast, naming the offending value (G2).
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"--mode", "sideways"}));

        assertEquals("sideways", thrown.offendingArgument(),
                "the usage error names the unknown mode value (G2)");
        assertTrue(thrown.getMessage().toLowerCase(java.util.Locale.ROOT).contains("greenfield"),
                "the message names the recognized mode values; was: " + thrown.getMessage());
    }

    @Test
    @DisplayName("cli-exit-codes 2: --mode with a blank value is a usage error")
    void modeWithBlankValueIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". A blank --mode value names no workflow; reject it.
        assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"--mode", "   "}),
                "a blank --mode value is a usage error");
    }

    // --- 04-apis § 1.3 / 05-operations § 3 : --debug raises the operational log level (T-4.5) ----

    @Test
    @DisplayName("04-apis § 1.3: --debug is parsed (orthogonal to the kind) and reported by debug()")
    void debugFlagIsParsedOrthogonally() {
        // Oracle: 04-apis § 1.3 "--debug: DEBUG-level internals to stderr". --debug is orthogonal to
        // the kind (like --mode/--attach): it continues the scan and -p still selects the one-shot
        // shape; debug() reports it was given.
        CliArguments parsed = CliArguments.parse(new String[] {"--debug", "-p", "fix the bug"});

        assertEquals(CliArguments.Kind.ONE_SHOT, parsed.kind(),
                "--debug does not change the kind (it is orthogonal)");
        assertEquals("fix the bug", parsed.prompt().orElseThrow(), "the one-shot prompt is parsed");
        assertTrue(parsed.debug(), "04-apis § 1.3: --debug is parsed and reported");
    }

    @Test
    @DisplayName("05-operations § 3: no --debug means debug() is false (default INFO baseline)")
    void noDebugFlagIsFalse() {
        // Oracle: 05-operations § 3 — the default level baseline is INFO; --debug is opt-in. An
        // invocation without --debug reports debug() == false.
        assertFalse(CliArguments.parse(new String[] {"-p", "no debug"}).debug(),
                "an invocation without --debug is not in debug mode");
        assertFalse(CliArguments.parse(new String[] {}).debug(),
                "the bare interactive shape is not in debug mode by default");
    }

    @Test
    @DisplayName("04-apis § 1.3: --debug coexists with the interactive shape and other flags")
    void debugCoexistsWithOtherFlags() {
        // Oracle: 04-apis § 1.3 / ADR-0012 — --debug is orthogonal; it coexists with --mode on the
        // interactive shape (no -p) and is reported alongside the selected mode.
        CliArguments parsed = CliArguments.parse(new String[] {"--mode", "greenfield", "--debug"});

        assertEquals(CliArguments.Kind.INTERACTIVE, parsed.kind(), "no -p is the interactive shape");
        assertEquals(SessionMode.GREENFIELD, parsed.mode(), "--mode is still parsed");
        assertTrue(parsed.debug(), "--debug is parsed alongside --mode");
    }

    // --- 04-apis § 1.2 : memory subcommand (US-14) ------------------------------------------------

    @Test
    @DisplayName("04-apis § 1.2: bare `memory` is the memory-list shape (LIST, no slug)")
    void bareMemoryIsListShape() {
        // Oracle: 04-apis § 1.2 — "memory [list|show <slug>|edit <slug>|rm <slug>]". The bracketed
        // action is optional; bare `memory` defaults to listing (the always-loaded awareness
        // surface, AC-14.3), with no slug.
        CliArguments parsed = CliArguments.parse(new String[] {"memory"});

        assertEquals(CliArguments.Kind.MEMORY, parsed.kind(), "memory selects the MEMORY shape");
        assertEquals(CliArguments.MemoryAction.LIST, parsed.memoryAction().orElseThrow(),
                "bare memory is the LIST action");
        assertTrue(parsed.memorySlug().isEmpty(), "bare memory carries no slug");
    }

    @Test
    @DisplayName("04-apis § 1.2: `memory list` is the explicit list shape")
    void memoryListIsListShape() {
        // Oracle: 04-apis § 1.2 — `list` is the explicit listing action (not only the bare default).
        CliArguments parsed = CliArguments.parse(new String[] {"memory", "list"});

        assertEquals(CliArguments.Kind.MEMORY, parsed.kind());
        assertEquals(CliArguments.MemoryAction.LIST, parsed.memoryAction().orElseThrow(),
                "memory list selects the LIST action");
    }

    @Test
    @DisplayName("04-apis § 1.2: `memory show <slug>` carries the slug to inspect (AC-14.1)")
    void memoryShowCarriesSlug() {
        // Oracle: 04-apis § 1.2 — "show <slug>" inspects a memory entry (AC-14.1). The word after
        // `show` is the slug to display.
        CliArguments parsed = CliArguments.parse(new String[] {"memory", "show", "a-learning"});

        assertEquals(CliArguments.MemoryAction.SHOW, parsed.memoryAction().orElseThrow(),
                "memory show selects the SHOW action");
        assertEquals("a-learning", parsed.memorySlug().orElseThrow(),
                "memory show <slug> carries the slug to inspect");
    }

    @Test
    @DisplayName("04-apis § 1.2: `memory edit <slug>` carries the slug to hand-edit (AC-14.1)")
    void memoryEditCarriesSlug() {
        // Oracle: 04-apis § 1.2 — "edit <slug>" (also hand-editable on disk, AC-14.1).
        CliArguments parsed = CliArguments.parse(new String[] {"memory", "edit", "a-learning"});

        assertEquals(CliArguments.MemoryAction.EDIT, parsed.memoryAction().orElseThrow(),
                "memory edit selects the EDIT action");
        assertEquals("a-learning", parsed.memorySlug().orElseThrow(),
                "memory edit <slug> carries the slug to hand-edit");
    }

    @Test
    @DisplayName("04-apis § 1.2: `memory rm <slug>` carries the slug to remove (AC-14.3)")
    void memoryRmCarriesSlug() {
        // Oracle: 04-apis § 1.2 — "rm <slug>" curates (removes) a memory entry (AC-14.1/14.3).
        CliArguments parsed = CliArguments.parse(new String[] {"memory", "rm", "a-learning"});

        assertEquals(CliArguments.MemoryAction.RM, parsed.memoryAction().orElseThrow(),
                "memory rm selects the RM action");
        assertEquals("a-learning", parsed.memorySlug().orElseThrow(),
                "memory rm <slug> carries the slug to remove");
    }

    @Test
    @DisplayName("cli-exit-codes 2: `memory show` with no slug is a usage error")
    void memoryShowWithoutSlugIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". show/edit/rm each require a slug; a missing one is
        // a malformed invocation named for G2.
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"memory", "show"}));

        assertEquals("show", thrown.offendingArgument(),
                "the usage error names the memory action missing its slug");
    }

    @Test
    @DisplayName("cli-exit-codes 2: an unknown memory action is a usage error naming it")
    void unknownMemoryActionIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". An action that is not list/show/edit/rm is
        // rejected naming the bad action (G2).
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"memory", "frobnicate"}));

        assertEquals("frobnicate", thrown.offendingArgument(),
                "the usage error names the unknown memory action");
    }

    @Test
    @DisplayName("cli-exit-codes 2: `memory list <extra>` is a usage error (list takes no argument)")
    void memoryListWithExtraArgIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". `memory list` takes no argument; an extra word is
        // a malformed invocation named for G2.
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"memory", "list", "extra"}));

        assertEquals("extra", thrown.offendingArgument(),
                "the usage error names the unexpected argument after memory list");
    }

    @Test
    @DisplayName("cli-exit-codes 2: `memory show <blank-slug>` is a usage error")
    void memoryShowWithBlankSlugIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". A blank slug names no entry; reject it fail-fast,
        // naming the action (G2), rather than carrying an empty slug.
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"memory", "show", "   "}));

        assertEquals("show", thrown.offendingArgument(),
                "the usage error names the memory action for the blank slug");
    }

    @Test
    @DisplayName("cli-exit-codes 2: `memory show <slug> <extra>` is a usage error (one slug only)")
    void memoryShowWithExtraArgIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". show takes exactly one slug; a second positional is
        // malformed.
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"memory", "show", "slug-1", "slug-2"}));

        assertEquals("slug-2", thrown.offendingArgument(),
                "the usage error names the unexpected extra argument after the slug");
    }

    // --- 04-apis § 1.2 : config subcommand (US-8) -------------------------------------------------

    @Test
    @DisplayName("04-apis § 1.2: bare `config` is the show shape (resolved config)")
    void bareConfigIsShowShape() {
        // Oracle: 04-apis § 1.2 — "config [show|path]". The bracketed action is optional; bare
        // `config` defaults to showing the resolved configuration (US-8).
        CliArguments parsed = CliArguments.parse(new String[] {"config"});

        assertEquals(CliArguments.Kind.CONFIG, parsed.kind(), "config selects the CONFIG shape");
        assertEquals(CliArguments.ConfigAction.SHOW, parsed.configAction().orElseThrow(),
                "bare config is the SHOW action");
    }

    @Test
    @DisplayName("04-apis § 1.2: `config show` shows the resolved config (US-8)")
    void configShowIsShowShape() {
        // Oracle: 04-apis § 1.2 — "config show: show resolved config".
        CliArguments parsed = CliArguments.parse(new String[] {"config", "show"});

        assertEquals(CliArguments.Kind.CONFIG, parsed.kind());
        assertEquals(CliArguments.ConfigAction.SHOW, parsed.configAction().orElseThrow(),
                "config show selects the SHOW action");
    }

    @Test
    @DisplayName("04-apis § 1.2: `config path` shows the config file locations (US-8)")
    void configPathIsPathShape() {
        // Oracle: 04-apis § 1.2 — "config path: show ... file locations".
        CliArguments parsed = CliArguments.parse(new String[] {"config", "path"});

        assertEquals(CliArguments.Kind.CONFIG, parsed.kind());
        assertEquals(CliArguments.ConfigAction.PATH, parsed.configAction().orElseThrow(),
                "config path selects the PATH action");
    }

    @Test
    @DisplayName("cli-exit-codes 2: an unknown config action is a usage error naming it")
    void unknownConfigActionIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". A config action that is neither show nor path is
        // rejected naming the bad action (G2).
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"config", "dump"}));

        assertEquals("dump", thrown.offendingArgument(),
                "the usage error names the unknown config action");
    }

    @Test
    @DisplayName("cli-exit-codes 2: `config show <extra>` is a usage error (config takes one action)")
    void configWithExtraArgIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". config takes at most one action word; a second
        // positional is malformed.
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"config", "show", "extra"}));

        assertEquals("extra", thrown.offendingArgument(),
                "the usage error names the unexpected argument after config");
    }
}
