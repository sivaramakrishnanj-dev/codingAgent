package com.srk.codingagent.loop;

import com.srk.codingagent.tool.CommandResult;
import java.util.Objects;

/**
 * Builds the user-facing verification-failure report a workflow driver surfaces when a bounded verify
 * does not pass within {@code NFR-VERIFY-MAX-ITERATIONS} attempts (AC-20.5 &mdash; "stop and surface
 * the failure with the relevant output"). It renders a caller-supplied headline (the brownfield
 * change-verify and the greenfield end-of-phase verify phrase it differently), the attempt count, the
 * last attempt's exit code, and the last attempt's captured {@code stdout}/{@code stderr} (each
 * included only when non-blank).
 *
 * <p><b>Why a shared seam.</b> The {@link VerifyLoop} surfaces an {@link VerifyOutcome.Kind#EXHAUSTED}
 * outcome carrying the last failing run; the <em>shape</em> of the failure report the consuming driver
 * shows the developer &mdash; the attempt count and the failing command's relevant output &mdash; is
 * identical regardless of which driver consumes it (AC-20.5), so keeping it here (alongside
 * {@link RemedyPrompt} and {@link VerifyLoop}) is a single source of truth, exercised under the
 * coverage gate. Mirrors the shared {@link RemedyPrompt} discipline: the failure-feedback prompt and
 * the failure-surface report both live in the {@code loop} package rather than being duplicated per
 * driver.
 *
 * <p>Kept as a small tested artifact so the suite can assert the failure output is actually carried
 * into the report (rather than a fixed canned string that ignores the failure).
 */
public final class VerifyFailureReport {

    private VerifyFailureReport() {
        // Holder for the report builder; not instantiable.
    }

    /**
     * Builds the verification-failure report for an exhausted verify: the {@code headline}, the
     * attempt count, the last attempt's exit code, and its captured {@code stdout}/{@code stderr}
     * (each included only when non-blank), so the developer sees the relevant output (AC-20.5).
     *
     * @param headline the caller's lead-in describing what failed to verify (e.g. "The change was
     *                 made but did not pass verification" or "End-of-phase verification did not
     *                 pass"); must not be {@code null}.
     * @param verify   the exhausted verify outcome carrying the last failing run; must not be
     *                 {@code null} and must carry a result (a ran verify, i.e. not
     *                 {@link VerifyOutcome.Kind#NO_TEST_COMMAND}).
     * @return the report carrying the headline, attempt count, and the last failure's output; never
     *         {@code null}.
     * @throws NullPointerException if {@code headline} or {@code verify} is {@code null}, or if
     *                              {@code verify} carries no result (no command ran).
     */
    public static String forExhaustedVerify(String headline, VerifyOutcome verify) {
        Objects.requireNonNull(headline, "headline");
        Objects.requireNonNull(verify, "verify");
        CommandResult failure = Objects.requireNonNull(
                verify.result(), "an exhausted verify carries the last failing run's result");
        StringBuilder report = new StringBuilder()
                .append(headline)
                .append(" after ")
                .append(verify.iterations())
                .append(" attempt(s). Last failure (exit ")
                .append(failure.exitCode())
                .append("):");
        appendIfPresent(report, failure.stdout());
        appendIfPresent(report, failure.stderr());
        return report.toString();
    }

    private static void appendIfPresent(StringBuilder report, String output) {
        if (output != null && !output.isBlank()) {
            report.append('\n').append(output);
        }
    }
}
