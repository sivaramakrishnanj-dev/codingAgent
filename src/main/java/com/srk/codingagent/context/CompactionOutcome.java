package com.srk.codingagent.context;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The terminal result of one {@link Compactor#compact} call (state-machine B): either the
 * derivation succeeded (LT3 &rarr; L2: a new {@code DERIVED_FROM} session was seeded and
 * work continues there) or it failed (LT4 &rarr; L4: the summary/derive could not recover
 * context, an {@code ERROR} was appended, and the run must exit context-exhausted).
 *
 * <p><b>The failure signal (LT4 &rarr; LT7 &rarr; machine A T15).</b> A {@link Kind#FAILED}
 * outcome carries the process exit code the failure maps to —
 * {@value #CONTEXT_EXHAUSTED_EXIT_CODE} (context-exhausted, {@code cli-exit-codes} 5) — so
 * the failure&rarr;exit-5 mapping is decided here, in a tested unit, rather than in the
 * (JaCoCo-excluded) composition root. The CLI/REPL boundary returns this code; the
 * {@link Compactor} owns producing it.
 *
 * @param kind             whether the compaction succeeded (derived) or failed; never
 *                         {@code null}.
 * @param derivedSessionId the derived successor's id for a {@link Kind#DERIVED} outcome, or
 *                         {@code null} for {@link Kind#FAILED}.
 * @param failureExitCode  the process exit code a {@link Kind#FAILED} outcome maps to, or
 *                         {@code null} for {@link Kind#DERIVED}.
 */
public record CompactionOutcome(Kind kind, String derivedSessionId, Integer failureExitCode) {

    /**
     * The {@code cli-exit-codes} {@code 5} (context-exhausted) code an unrecoverable
     * compaction maps to (state-machine B LT7 &rarr; machine A T15).
     */
    public static final int CONTEXT_EXHAUSTED_EXIT_CODE = 5;

    /** Which terminal state the compaction reached (state-machine B). */
    public enum Kind {

        /** LT3 &rarr; L2: a derived {@code DERIVED_FROM} session was seeded; work continues there. */
        DERIVED,

        /** LT4 &rarr; L4: the summary/derive failed; an {@code ERROR} was appended; exit 5. */
        FAILED
    }

    /**
     * Validates the outcome's field invariants.
     *
     * @throws NullPointerException     if {@code kind} is {@code null}.
     * @throws IllegalArgumentException if the field set does not match the kind.
     */
    public CompactionOutcome {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.DERIVED && derivedSessionId == null) {
            throw new IllegalArgumentException("a DERIVED outcome must carry the derived session id");
        }
        if (kind == Kind.FAILED && failureExitCode == null) {
            throw new IllegalArgumentException("a FAILED outcome must carry a failure exit code");
        }
    }

    /**
     * Builds a successful (derived) outcome for state-machine B LT3.
     *
     * @param derivedSessionId the derived successor's id; non-blank.
     * @return a {@link Kind#DERIVED} outcome.
     * @throws NullPointerException if {@code derivedSessionId} is {@code null}.
     */
    public static CompactionOutcome derived(String derivedSessionId) {
        return new CompactionOutcome(Kind.DERIVED,
                Objects.requireNonNull(derivedSessionId, "derivedSessionId"), null);
    }

    /**
     * Builds a failed outcome for state-machine B LT4, carrying the context-exhausted exit
     * code (5) the failure maps to.
     *
     * @return a {@link Kind#FAILED} outcome with exit code {@value #CONTEXT_EXHAUSTED_EXIT_CODE}.
     */
    public static CompactionOutcome failed() {
        return new CompactionOutcome(Kind.FAILED, null, CONTEXT_EXHAUSTED_EXIT_CODE);
    }

    /**
     * Whether the compaction produced a derived session (as opposed to failing).
     *
     * @return {@code true} for a {@link Kind#DERIVED} outcome.
     */
    public boolean succeeded() {
        return kind == Kind.DERIVED;
    }

    /**
     * The derived successor's session id, present only for a successful compaction.
     *
     * @return the derived session id, or {@link Optional#empty()} for a failure.
     */
    public Optional<String> derivedSessionIdIfPresent() {
        return Optional.ofNullable(derivedSessionId);
    }

    /**
     * The process exit code a failed compaction maps to (context-exhausted, 5).
     *
     * @return the exit code, or {@link OptionalInt#empty()} for a successful compaction.
     */
    public OptionalInt failureExitCodeIfPresent() {
        return failureExitCode == null ? OptionalInt.empty() : OptionalInt.of(failureExitCode);
    }
}
