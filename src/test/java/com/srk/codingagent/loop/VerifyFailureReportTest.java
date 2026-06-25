package com.srk.codingagent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.tool.CommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link VerifyFailureReport}: the shared verification-failure report both workflow-driver
 * verify-exhaustion paths surface (AC-20.5 &mdash; "stop and surface the failure with the relevant
 * output").
 *
 * <p><b>Oracles trace to AC-20.5:</b> the report must carry the caller's headline, the attempt count,
 * and the last failing run's exit code and captured {@code stdout}/{@code stderr}, so the developer
 * sees the relevant output rather than a canned "it failed" string. The asserted output tokens are the
 * input's own output (and the input's attempt count), not the builder's wording.
 */
class VerifyFailureReportTest {

    private static final String CMD = "mvn test";

    @Test
    @DisplayName("AC-20.5: the report carries the headline, attempt count, exit code, and the last failure's output")
    void carriesHeadlineCountAndOutput() {
        // Oracle: AC-20.5 — surface the failure WITH the relevant output. The report must carry the
        // headline, the attempt count (3 here), the last attempt's exit code (7), and both captured
        // streams verbatim, so the developer reasons over the real failure. The count + output tokens
        // are the input's own (iterations 3, the failing CommandResult's streams), not the builder's.
        CommandResult fail = CommandResult.completed(CMD, 7, "BUILD output", "FAILED: boom", 12L);
        VerifyOutcome exhausted = VerifyOutcome.exhausted(3, fail);

        String report = VerifyFailureReport.forExhaustedVerify("End-of-phase verification did not pass",
                exhausted);

        assertTrue(report.contains("End-of-phase verification did not pass"),
                "the caller's headline is rendered");
        assertTrue(report.contains("3 attempt"),
                "AC-20.5: the attempt count (the verify's iterations) is reported");
        assertTrue(report.contains("exit 7"), "the last failing exit code is reported");
        assertTrue(report.contains("BUILD output"), "AC-20.5: the last attempt's stdout is surfaced");
        assertTrue(report.contains("FAILED: boom"), "AC-20.5: the last attempt's stderr is surfaced");
    }

    @Test
    @DisplayName("a blank output stream is omitted (only present output is surfaced)")
    void omitsBlankOutput() {
        // Oracle: AC-20.5 — the RELEVANT output is surfaced. An empty stderr adds no empty section; the
        // present stdout is carried.
        CommandResult fail = CommandResult.completed(CMD, 1, "only stdout", "", 5L);
        VerifyOutcome exhausted = VerifyOutcome.exhausted(2, fail);

        String report = VerifyFailureReport.forExhaustedVerify("verify failed", exhausted);

        assertTrue(report.contains("only stdout"), "present stdout is surfaced");
        assertFalse(report.endsWith("\n"),
                "a blank stderr stream adds no trailing empty section; was: " + report);
    }

    @Test
    @DisplayName("forExhaustedVerify rejects a null headline, a null verify, and a verify with no result")
    void rejectsNullAndResultlessVerify() {
        // Oracle: the report builder needs the last failing run to surface its output (AC-20.5); a
        // NO_TEST_COMMAND verify carries no result (no command ran), so it is not a valid input.
        VerifyOutcome exhausted = VerifyOutcome.exhausted(
                1, CommandResult.completed(CMD, 1, "o", "e", 1L));
        assertThrows(NullPointerException.class,
                () -> VerifyFailureReport.forExhaustedVerify(null, exhausted));
        assertThrows(NullPointerException.class,
                () -> VerifyFailureReport.forExhaustedVerify("h", null));
        assertThrows(NullPointerException.class,
                () -> VerifyFailureReport.forExhaustedVerify("h", VerifyOutcome.noTestCommand()));
    }
}
