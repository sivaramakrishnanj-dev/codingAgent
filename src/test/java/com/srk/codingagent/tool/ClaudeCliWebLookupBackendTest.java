package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ClaudeCliWebLookupBackend} — the v1 web-lookup backend that shells out to a
 * constrained headless Claude CLI delegate (C11, ADR-0008). The SUT is the real backend over a
 * <em>real</em> {@link CommandExecutor} rooted at a scratch temp directory (so the ADR-0003 subprocess
 * machinery is genuinely exercised). The only test control is the injected delegate program name: a
 * deterministic {@code claude}-shaped stub script (success / empty / error / sleep) or a
 * guaranteed-absent program — so no test depends on a real {@code claude} being installed.
 *
 * <p><b>Oracles.</b> Expected values trace to the spec, never to the backend's incidental behavior:
 * <ul>
 *   <li><b>AC-11.1 / 04-apis § 4:</b> a successful delegate returns its text as a summarized result.</li>
 *   <li><b>AC-11.3 / ADR-0008:</b> an absent-on-PATH, errored, or empty delegate yields a
 *       <em>failure</em> result (report, never fabricate) — not a thrown exception, not a fabricated
 *       answer.</li>
 *   <li><b>NFR-NET-WEBLOOKUP-TIMEOUT:</b> a delegate that exceeds the timeout is tree-killed and
 *       yields a failure result.</li>
 * </ul>
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class ClaudeCliWebLookupBackendTest {

    private static final Duration SHORT = Duration.ofSeconds(30);

    /** Writes an executable {@code claude}-shaped stub script with the given shell body. */
    private static Path stubProgram(Path dir, String body) throws IOException {
        Path script = dir.resolve("claude-stub.sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private static ClaudeCliWebLookupBackend backend(Path scratch, Duration timeout, String program) {
        return new ClaudeCliWebLookupBackend(new CommandExecutor(scratch), timeout, program);
    }

    @Test
    @DisplayName("AC-11.1: a successful delegate returns its text as a summarized result")
    void successReturnsSummarizedText(@TempDir Path scratch) throws IOException {
        // Oracle: AC-11.1 / 04-apis § 4 — the delegate "Returns TEXT" and the lookup returns a
        // summarized result. A stub that echoes a summary to stdout and exits 0 must produce a
        // success result carrying that text. Expected text traces to the stub's stdout + the AC.
        Path stub = stubProgram(scratch, "printf '%s' 'Mars has two moons.'");
        ClaudeCliWebLookupBackend backend = backend(scratch, SHORT, stub.toString());

        WebLookupResult result = backend.lookup(WebLookupRequest.search("moons of Mars"));

        assertTrue(result.success(), "AC-11.1: a delegate that returns text is a successful lookup");
        assertEquals("Mars has two moons.", result.text(),
                "AC-11.1: the lookup returns the delegate's summarized text");
    }

    @Test
    @DisplayName("ADR-0008: the constrained delegate is invoked in print mode (-p ... --output-format text)")
    void invokesConstrainedPrintMode(@TempDir Path scratch) throws IOException {
        // Oracle: ADR-0008 / 04-apis § 4 — the invocation is `claude -p "<task>" --output-format
        // text`. A stub that echoes its own argv lets us assert the delegate is run constrained: in
        // print mode (-p), text output format, and with the lookup task as the prompt. The argv shape
        // traces to the ADR's invocation clause, not to the backend's string building.
        Path stub = stubProgram(scratch, "printf '%s' \"$*\"");
        ClaudeCliWebLookupBackend backend = backend(scratch, SHORT, stub.toString());

        WebLookupResult result = backend.lookup(WebLookupRequest.search("Java 21 features"));

        assertTrue(result.success(), "the echo stub completes successfully");
        assertTrue(result.text().contains("-p"),
                "ADR-0008: the delegate runs in print mode (-p)");
        assertTrue(result.text().contains("--output-format text"),
                "ADR-0008: the delegate is constrained to text output");
        assertTrue(result.text().contains("Java 21 features"),
                "ADR-0008: the lookup task is passed as the delegate prompt");
    }

    @Test
    @DisplayName("AC-11.3: an absent-on-PATH delegate yields a failure result, not a crash")
    void absentDelegateYieldsFailure(@TempDir Path scratch) {
        // Oracle: AC-11.3 / ADR-0008 — "if the delegate is absent (not on PATH) ... return a failure
        // result; the agent REPORTS it, never fabricates". A guaranteed-absent program name makes the
        // shell report command-not-found (exit 127); the backend must return a failure result, never
        // throw and never fabricate an answer.
        ClaudeCliWebLookupBackend backend =
                backend(scratch, SHORT, "definitely-not-a-real-program-xyzzy");

        WebLookupResult result = backend.lookup(WebLookupRequest.search("anything"));

        assertFalse(result.success(),
                "AC-11.3: an absent delegate is a failure (not on PATH)");
        assertTrue(result.text().toLowerCase().contains("unavailable")
                        || result.text().toLowerCase().contains("not found"),
                "AC-11.3: the failure reports the delegate is unavailable, rather than fabricating: "
                        + result.text());
    }

    @Test
    @DisplayName("AC-11.3: a delegate that errors (non-zero exit) yields a failure result")
    void erroringDelegateYieldsFailure(@TempDir Path scratch) throws IOException {
        // Oracle: AC-11.3 / ADR-0008 — a delegate that "errors" yields a failure result the agent
        // reports. A stub that exits non-zero must produce a failure result, not a fabricated answer.
        Path stub = stubProgram(scratch, "echo 'boom' >&2; exit 2");
        ClaudeCliWebLookupBackend backend = backend(scratch, SHORT, stub.toString());

        WebLookupResult result = backend.lookup(WebLookupRequest.fetch("https://example.com"));

        assertFalse(result.success(), "AC-11.3: a delegate that errors is a failure");
        assertTrue(result.text().toLowerCase().contains("fail"),
                "AC-11.3: the failure is reported, not fabricated: " + result.text());
    }

    @Test
    @DisplayName("AC-11.3: a delegate that returns no text yields a failure result (no fabrication)")
    void emptyDelegateOutputYieldsFailure(@TempDir Path scratch) throws IOException {
        // Oracle: AC-11.3 — the agent must not fabricate. A delegate that exits 0 but produces no
        // text gives nothing to summarize; rather than inventing a result, the backend reports a
        // failure so the agent does not pass off an empty/fabricated answer.
        Path stub = stubProgram(scratch, "exit 0");
        ClaudeCliWebLookupBackend backend = backend(scratch, SHORT, stub.toString());

        WebLookupResult result = backend.lookup(WebLookupRequest.search("anything"));

        assertFalse(result.success(),
                "AC-11.3: no delegate text is a failure (the agent does not fabricate a result)");
    }

    @Test
    @DisplayName("NFR-NET-WEBLOOKUP-TIMEOUT: a delegate exceeding the timeout yields a failure result")
    void timedOutDelegateYieldsFailure(@TempDir Path scratch) throws IOException {
        // Oracle: NFR-NET-WEBLOOKUP-TIMEOUT / AC-11.3 — on timeout the delegate is killed and the
        // lookup is a failure (report, do not fabricate). A 1-second timeout against a 30-second
        // sleeping stub must time out (tree-kill) and yield a failure result. The timeout value
        // bounding the run traces to the NFR, not to the backend's code.
        Path stub = stubProgram(scratch, "sleep 30");
        ClaudeCliWebLookupBackend backend =
                backend(scratch, Duration.ofSeconds(1), stub.toString());

        WebLookupResult result = backend.lookup(WebLookupRequest.search("slow query"));

        assertFalse(result.success(), "NFR-NET-WEBLOOKUP-TIMEOUT: a timed-out delegate is a failure");
        assertTrue(result.text().toLowerCase().contains("timed out"),
                "AC-11.3: the timeout is reported, not fabricated: " + result.text());
    }

    @Test
    @DisplayName("a null request is a programming error (NullPointerException), not a silent failure")
    void nullRequestThrows(@TempDir Path scratch) throws IOException {
        // Oracle: defensive class invariant (EJ Item 49) — a null request is a caller bug, distinct
        // from the AC-11.3 delegate-failure path; it throws rather than fabricating a failure result.
        Path stub = stubProgram(scratch, "exit 0");
        ClaudeCliWebLookupBackend backend = backend(scratch, SHORT, stub.toString());

        assertThrows(NullPointerException.class, () -> backend.lookup(null));
    }
}
