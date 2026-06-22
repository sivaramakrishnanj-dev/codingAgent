package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("cli-exit-codes 2: a bare positional argument is a usage error")
    void positionalArgumentIsUsageError() {
        // Oracle: § 3.2 "bad CLI args → exit 2". A bare positional argument is not part of this
        // task's scope (no subcommands here); reject it as a usage error naming it.
        UsageException thrown = assertThrows(UsageException.class,
                () -> CliArguments.parse(new String[] {"resume"}));

        assertEquals("resume", thrown.offendingArgument(),
                "the usage error names the unexpected positional argument");
    }
}
