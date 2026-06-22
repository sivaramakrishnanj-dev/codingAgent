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
 * Tests the CLI's config-resolution wiring in {@link Main} — specifically the
 * fail-fast mapping of a configuration fault onto process exit code {@code 2}.
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>CT-EX-1</b> (exit 2, positive): a malformed/unknown config key &rarr;
 *       exit 2, and the message names the key. Traces AC-8.5.</li>
 *   <li><b>AC-8.5 / AC-6.4 / ADR-0009</b>: malformed config fails fast before any
 *       model call; the process exits {@link ExitCode#USAGE_CONFIG} ({@code 2}).</li>
 * </ul>
 *
 * <p>This file covers only the config <em>fail-fast</em> ordering ({@code 2} before any
 * model call), which {@code run} decides before dispatching to either run shape. The
 * valid-config success path now flows into a run shape (one-shot or the interactive REPL)
 * that resolves live SigV4 credentials and builds a Bedrock client — not unit-testable
 * without a live AWS call. Those paths are covered at their testable seams
 * ({@code OneShotRunnerTest}, {@code ReplRunnerTest}) and confirmed end-to-end by the
 * real-Bedrock smoke test at the G1 gate.
 *
 * <p>The test redirects {@code user.home} to a temporary store so {@code Main} reads
 * a controlled {@code ~/.codingagent/config.yaml} rather than the real user's home.
 * {@code user.home} and {@code System.err} are saved and restored around each test so
 * no global state leaks. {@code Main.main(String[])} (which calls
 * {@link System#exit(int)}) is intentionally not exercised; the {@link Main#run(String[])}
 * seam carries the same mapping without terminating the test JVM.
 */
class MainConfigTest {

    private String originalUserHome;
    private PrintStream originalErr;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void redirectHomeAndStderr(@TempDir Path home) throws Exception {
        originalUserHome = System.getProperty("user.home");
        originalErr = System.err;

        System.setProperty("user.home", home.toString());
        capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));

        // Ensure the store directory exists; individual tests write config.yaml into it.
        Files.createDirectories(home.resolve(".codingagent"));
    }

    @AfterEach
    void restoreHomeAndStderr() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        } else {
            System.clearProperty("user.home");
        }
        System.setErr(originalErr);
    }

    private Path configFile(Path home) {
        return home.resolve(".codingagent").resolve("config.yaml");
    }

    private String stderr() {
        return capturedErr.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("CT-EX-1: an unknown config key makes run() exit 2 and name the key (AC-8.5)")
    void ct_ex_1_unknownKey_exitsTwoNamingKey(@TempDir Path home) throws Exception {
        // Oracle: CT-EX-1 — malformed/unknown config key -> exit 2, message names the
        // key. The expected code 2 is the exit-code contract's usage-config code.
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".codingagent"));
        Files.writeString(configFile(home), "modelId: m\nnotAKey: oops\n", StandardCharsets.UTF_8);

        int exitCode = Main.run(new String[] {});

        assertEquals(2, exitCode,
                "an unknown config key must fail fast with exit 2 (CT-EX-1, AC-8.5)");
        assertTrue(stderr().contains("notAKey"),
                "the stderr line must name the offending key (CT-EX-1, AC-8.5); was: " + stderr());
    }

    @Test
    @DisplayName("a malformed permission mode makes run() exit 2 and name the key (AC-8.5)")
    void malformedPermissionMode_exitsTwoNamingKey(@TempDir Path home) throws Exception {
        // Oracle: AC-8.5 "unknown mode" -> exit 2 identifying the offending key.
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".codingagent"));
        Files.writeString(configFile(home),
                "permissionMode: NONSENSE\n", StandardCharsets.UTF_8);

        int exitCode = Main.run(new String[] {});

        assertEquals(2, exitCode, "a malformed permission mode must exit 2 (AC-8.5)");
        assertTrue(stderr().contains("permissionMode"),
                "the stderr line must name permissionMode (AC-8.5); was: " + stderr());
    }
}
