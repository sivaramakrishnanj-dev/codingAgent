package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WebLookupResult} — the success/failure value the {@link WebLookupBackend} returns
 * (C11, ADR-0008).
 *
 * <p>Oracle: AC-11.1 (a successful lookup carries a summarized result) and AC-11.3 (a failed lookup
 * carries a report the agent surfaces rather than fabricating an answer). Failure is a value, not an
 * exception, so the backend controls the report text.
 */
class WebLookupResultTest {

    @Test
    @DisplayName("AC-11.1: a success result carries the summarized text and is marked successful")
    void successCarriesText() {
        // Oracle: AC-11.1 — a successful lookup returns a summarized result.
        WebLookupResult result = WebLookupResult.success("a concise summary");

        assertTrue(result.success(), "AC-11.1: a success result is marked successful");
        assertEquals("a concise summary", result.text());
        assertEquals("a concise summary", result.requireText());
    }

    @Test
    @DisplayName("AC-11.3: a failure result carries the report and is marked unsuccessful")
    void failureCarriesReport() {
        // Oracle: AC-11.3 — the agent reports the failure rather than fabricating. The failure result
        // carries the report text and is not marked successful, so callers branch to reporting.
        WebLookupResult result = WebLookupResult.failure("web lookup unavailable: claude not on PATH");

        assertFalse(result.success(), "AC-11.3: a failure result is not successful");
        assertEquals("web lookup unavailable: claude not on PATH", result.text(),
                "AC-11.3: the failure result carries the report the agent surfaces");
    }

    @Test
    @DisplayName("a blank text is rejected (a result with no text to surface is invalid)")
    void blankTextRejected() {
        // Oracle: both success and failure must carry non-blank text — there is always either a
        // summary or a report to give the model; an empty result is never valid.
        assertThrows(IllegalArgumentException.class, () -> WebLookupResult.success("  "));
        assertThrows(IllegalArgumentException.class, () -> WebLookupResult.failure(""));
    }
}
