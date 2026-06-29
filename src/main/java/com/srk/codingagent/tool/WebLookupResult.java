package com.srk.codingagent.tool;

import java.util.Objects;

/**
 * The outcome of a single web lookup (C11, ADR-0008): a {@link #success() success} flag plus the
 * {@link #text() text} the model receives. On success {@code text} is the delegate's summarized
 * result; on failure {@code text} is a human-readable report of why the lookup could not complete.
 *
 * <p><b>Failure is a value, not an exception (AC-11.3).</b> When the delegate is unavailable (not on
 * PATH), errors, or times out, the backend returns a {@code failure} result rather than throwing.
 * The {@code web_search}/{@code web_fetch} tools surface that report to the model so the agent
 * <em>reports</em> the failure rather than fabricating an answer (ADR-0008 failure clause). Modelling
 * the failure as a returned value (not a thrown exception) keeps the report text under the backend's
 * control and lets the tool decide how to present it, rather than collapsing every failure into a
 * generic tool-error string.
 *
 * @param success whether the lookup produced a usable summarized result.
 * @param text    the summarized result (on success) or the failure report (on failure); non-blank.
 */
public record WebLookupResult(boolean success, String text) {

    /**
     * Validates the result.
     *
     * @throws IllegalArgumentException if {@code text} is blank.
     */
    public WebLookupResult {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must be non-blank");
        }
    }

    /**
     * A successful lookup carrying the delegate's summarized result.
     *
     * @param summary the summarized result text; non-blank.
     * @return a success result.
     */
    public static WebLookupResult success(String summary) {
        return new WebLookupResult(true, summary);
    }

    /**
     * A failed lookup carrying the report the model sees (AC-11.3 — report, do not fabricate).
     *
     * @param report a human-readable explanation of why the lookup could not complete; non-blank.
     * @return a failure result.
     */
    public static WebLookupResult failure(String report) {
        return new WebLookupResult(false, report);
    }

    /**
     * The {@link #text()}, asserted non-{@code null} for callers that have already branched on
     * {@link #success()}.
     *
     * @return the result text; never {@code null}.
     */
    public String requireText() {
        return Objects.requireNonNull(text, "text");
    }
}
