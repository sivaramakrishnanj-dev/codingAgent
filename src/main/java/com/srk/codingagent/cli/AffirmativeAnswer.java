package com.srk.codingagent.cli;

import java.util.Locale;

/**
 * The single place the CLI decides whether a developer's typed answer is an affirmative
 * <em>yes</em> at an approval prompt (AC-10.1/AC-10.2 present-then-decide; AC-1.5/AC-2.3 the
 * greenfield phase confirmation). Both the per-operation {@link InteractiveApprover} and the
 * per-phase {@link InteractiveGreenfieldApproval} read the developer's y/N through the same
 * boundary, so the affirmative semantics — and the fail-closed default — live here once rather
 * than being duplicated at two prompts.
 *
 * <p><b>Fail-closed (AC-10.2).</b> Only an explicit {@code y} / {@code yes} (case-insensitive,
 * surrounding whitespace ignored) is an affirmation; anything else — a blank line, an
 * unrecognized answer, or {@code null} (end-of-input / Ctrl-D) — is <em>not</em> affirmative, so
 * the caller denies the operation / declines the phase advance. A closed input is never a silent
 * yes.
 */
final class AffirmativeAnswer {

    private AffirmativeAnswer() {
        // Static decision utility; not instantiable.
    }

    /**
     * Whether {@code answer} is an explicit affirmative yes.
     *
     * @param answer the developer's typed answer line, or {@code null} at end-of-input.
     * @return {@code true} only for {@code y} / {@code yes} (case-insensitive, trimmed);
     *         {@code false} for a blank, unrecognized, or {@code null} answer (fail-closed).
     */
    static boolean isAffirmative(String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes");
    }
}
