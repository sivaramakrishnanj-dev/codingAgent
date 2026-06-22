package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link Main#run(String[])}'s one-shot orchestration up to (but not through) the
 * model call: the startup-ordered exit-{@code 2} paths (bad CLI args, malformed config) the
 * exit-code contract places <em>before</em> any model or tool work, plus the informational
 * and interactive shapes. The post-model-call run-and-map (exit 0/3/4/5/1/130) is exercised
 * against the {@link OneShotRunner} seam in {@code OneShotRunnerTest} with a scripted Bedrock
 * double; driving it through {@code Main} would require live AWS, which the task forbids.
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>cli-exit-codes {@code 2} / § 3.2:</b> "bad CLI args → exit 2" — an unknown flag
 *       given to {@code -p}'s sibling on the one-shot path fails fast before any model call,
 *       naming the offending argument (G2).</li>
 *   <li><b>CT-EX-1 / AC-8.5 / ADR-0009:</b> on the one-shot path a malformed config still
 *       fails fast with exit {@code 2} naming the key, before the loop is built — the
 *       fail-fast-before-any-model-call ordering is preserved.</li>
 *   <li><b>04-apis § 1.1:</b> {@code --help} / {@code --version} print and exit {@code 0};
 *       the interactive shape (no {@code -p}) exits {@code 0} (REPL is T-1.1).</li>
 * </ul>
 *
 * <p>The test redirects {@code user.home} to a temporary store and captures
 * {@code System.out}/{@code System.err}, restoring both per test. {@code Main.main(String[])}
 * (which calls {@link System#exit(int)}) is not exercised; the {@link Main#run(String[])}
 * seam carries the same mapping without terminating the test JVM.
 */
class MainOneShotTest {

    private String originalUserHome;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream capturedOut;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void redirectHomeAndStreams(@TempDir Path home) throws Exception {
        originalUserHome = System.getProperty("user.home");
        originalOut = System.out;
        originalErr = System.err;

        System.setProperty("user.home", home.toString());
        capturedOut = new ByteArrayOutputStream();
        capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));

        Files.createDirectories(home.resolve(".codingagent"));
    }

    @AfterEach
    void restoreHomeAndStreams() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        } else {
            System.clearProperty("user.home");
        }
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private Path configFile(Path home) {
        return home.resolve(".codingagent").resolve("config.yaml");
    }

    private String stdout() {
        return capturedOut.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return capturedErr.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("cli-exit-codes 2: an unknown flag makes run() exit 2 before any model call")
    void unknownFlag_exitsTwoNamingFlag() {
        // Oracle: cli-exit-codes "2 usage/config — unknown flag" / § 3.2 "bad CLI args → exit
        // 2". An unknown flag is rejected at startup, before config or any model call; the
        // stderr line names the flag (G2).
        int exitCode = Main.run(new String[] {"--frobnicate"});

        assertEquals(2, exitCode, "an unknown flag must fail fast with exit 2 (cli-exit-codes 2)");
        assertTrue(stderr().contains("--frobnicate"),
                "G2: the stderr line names the offending flag; was: " + stderr());
    }

    @Test
    @DisplayName("cli-exit-codes 2: -p without a prompt value makes run() exit 2")
    void promptWithoutValue_exitsTwo() {
        // Oracle: cli-exit-codes "2 usage/config — bad invocation detected BEFORE doing work".
        // A -p with no value is a malformed invocation; exit 2 before any model call.
        int exitCode = Main.run(new String[] {"-p"});

        assertEquals(2, exitCode, "-p without a value must fail fast with exit 2");
        assertTrue(stderr().contains("-p"),
                "G2: the stderr line names the prompt flag; was: " + stderr());
    }

    @Test
    @DisplayName("CT-EX-1: a malformed config makes a one-shot exit 2 before the loop is built")
    void oneShot_malformedConfig_exitsTwoNamingKey(@TempDir Path home) throws Exception {
        // Oracle: CT-EX-1 / AC-8.5 / ADR-0009 — on the one-shot path a malformed/unknown config
        // key still fails fast with exit 2 naming the key, BEFORE any model call (the loop is
        // never built). Preserves the fail-fast ordering the one-shot path must keep.
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".codingagent"));
        Files.writeString(configFile(home), "notAKey: oops\n", StandardCharsets.UTF_8);

        int exitCode = Main.run(new String[] {"-p", "do something"});

        assertEquals(2, exitCode,
                "CT-EX-1: a malformed config fails the one-shot fast with exit 2 (before any model call)");
        assertTrue(stderr().contains("notAKey"),
                "CT-EX-1/G2: the stderr line names the offending key; was: " + stderr());
    }

    @Test
    @DisplayName("CT-EX-1: a malformed permission mode makes a one-shot exit 2 naming the key")
    void oneShot_malformedPermissionMode_exitsTwoNamingKey(@TempDir Path home) throws Exception {
        // Oracle: AC-8.5 — an unknown permission mode is a config fault → exit 2 naming the key,
        // before the model call, even on the one-shot path.
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".codingagent"));
        Files.writeString(configFile(home), "permissionMode: NONSENSE\n", StandardCharsets.UTF_8);

        int exitCode = Main.run(new String[] {"-p", "go"});

        assertEquals(2, exitCode, "an unknown permission mode fails the one-shot fast with exit 2");
        assertTrue(stderr().contains("permissionMode"),
                "G2: the stderr line names permissionMode; was: " + stderr());
    }

    @Test
    @DisplayName("04-apis § 1.1: --help prints usage and exits 0")
    void help_printsUsageAndExitsZero() {
        // Oracle: 04-apis § 1.3 "--help: standard"; a help request exits cleanly (0).
        int exitCode = Main.run(new String[] {"--help"});

        assertEquals(0, exitCode, "--help exits 0 (clean informational request)");
        assertTrue(stdout().toLowerCase(java.util.Locale.ROOT).contains("usage"),
                "--help prints usage to stdout; was: " + stdout());
    }

    @Test
    @DisplayName("04-apis § 1.1: --version prints a version line and exits 0")
    void version_printsAndExitsZero() {
        // Oracle: 04-apis § 1.3 "--version: standard"; a version request exits cleanly (0).
        int exitCode = Main.run(new String[] {"--version"});

        assertEquals(0, exitCode, "--version exits 0");
        assertTrue(stdout().toLowerCase(java.util.Locale.ROOT).contains("codingagent"),
                "--version prints a version line; was: " + stdout());
    }

    @Test
    @DisplayName("04-apis § 1.1: the interactive shape (no -p) exits 0 (REPL is T-1.1)")
    void interactiveShape_exitsZero(@TempDir Path home) {
        // Oracle: 04-apis § 1.1 — "Interactive (REPL) = codingagent [options]". The REPL is
        // T-1.1; this task recognizes the no-arg shape and exits cleanly (0) after reporting
        // that interactive mode is not yet available.
        System.setProperty("user.home", home.toString());

        int exitCode = Main.run(new String[] {});

        assertEquals(0, exitCode,
                "the interactive shape (no -p) exits 0 at M0 (the REPL is T-1.1)");
    }
}
