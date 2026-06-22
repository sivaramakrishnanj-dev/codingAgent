package com.srk.codingagent.loop;

import com.srk.codingagent.persistence.StopReason;
import java.util.Objects;
import java.util.Optional;

/**
 * The terminal result of one {@link AgentLoop} run: why the loop stopped and, when the
 * model produced a final answer, its rendered text. This is the value T-0.9 (one-shot
 * CLI) and T-1.1 (REPL) map to an exit code and to the next REPL prompt — the loop
 * itself never calls {@code System.exit} (state machine A's S8/exit dispatch is out of
 * scope here; the loop stops at S5/S6/S7 and reports which).
 *
 * <p>The two terminal shapes the agent loop produces (02-architecture.md § 3.1, state
 * machine A):
 * <ul>
 *   <li><b>{@link Kind#COMPLETED} (T3 → S5).</b> The model returned
 *       {@code stopReason == end_turn}; {@link #finalText()} carries the concatenated
 *       text of the final assistant turn (empty when the turn had no text block).</li>
 *   <li><b>{@link Kind#SURFACED} (T4/T5 → S6/S7).</b> The model returned an edge stop
 *       reason — {@code max_tokens} / {@code model_context_window_exceeded} (the budget
 *       /compaction seam, T-2.1/T-2.2) or {@code guardrail_intervened} /
 *       {@code content_filtered} / {@code malformed_*} (the surface-and-decide seam,
 *       state machine A T5). The loop stops without running tools and reports the
 *       {@link #stopReason()} so the caller (T-0.9) decides retry/compact/exit. The
 *       bounded repair-retry and compaction machinery are deliberately NOT built here.</li>
 * </ul>
 *
 * <p>Every outcome carries the terminal {@link #stopReason()} so the exit-code mapper
 * has the precise model signal, and the {@link #kind()} so it can distinguish a clean
 * completion from a surfaced edge condition without re-deriving it from the stop reason.
 *
 * @param kind       whether the loop completed normally or surfaced an edge condition;
 *                   must not be {@code null}.
 * @param stopReason the terminal model stop reason that ended the loop; must not be
 *                   {@code null}.
 * @param finalText  the final assistant text for a {@link Kind#COMPLETED} outcome, or
 *                   {@code null} for a {@link Kind#SURFACED} outcome (no final answer was
 *                   produced).
 */
public record LoopOutcome(Kind kind, StopReason stopReason, String finalText) {

    /** Which terminal state the loop reached. */
    public enum Kind {

        /** {@code end_turn}: the model produced a final answer (state machine A, S5). */
        COMPLETED,

        /**
         * An edge stop reason was surfaced without running tools (state machine A,
         * S6/S7). The caller decides whether to compact, retry, or exit.
         */
        SURFACED
    }

    /**
     * Validates the outcome.
     *
     * @throws NullPointerException if {@code kind} or {@code stopReason} is {@code null}.
     */
    public LoopOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(stopReason, "stopReason");
    }

    /**
     * Builds a {@link Kind#COMPLETED} outcome for an {@code end_turn} turn.
     *
     * @param finalText the final assistant text; must not be {@code null} (use the empty
     *                  string when the turn carried no text block).
     * @return a completed outcome with {@link StopReason#END_TURN}.
     * @throws NullPointerException if {@code finalText} is {@code null}.
     */
    public static LoopOutcome completed(String finalText) {
        return new LoopOutcome(Kind.COMPLETED, StopReason.END_TURN,
                Objects.requireNonNull(finalText, "finalText"));
    }

    /**
     * Builds a {@link Kind#SURFACED} outcome for an edge stop reason the loop did not act
     * on (no tools dispatched, no final answer).
     *
     * @param stopReason the surfaced edge stop reason; must not be {@code null}.
     * @return a surfaced outcome carrying {@code stopReason} and no final text.
     * @throws NullPointerException if {@code stopReason} is {@code null}.
     */
    public static LoopOutcome surfaced(StopReason stopReason) {
        return new LoopOutcome(Kind.SURFACED, stopReason, null);
    }

    /**
     * Whether the loop completed normally with a final answer (S5), as opposed to
     * surfacing an edge condition (S6/S7).
     *
     * @return {@code true} for a {@link Kind#COMPLETED} outcome.
     */
    public boolean completed() {
        return kind == Kind.COMPLETED;
    }

    /**
     * The final assistant text, present only for a {@link Kind#COMPLETED} outcome.
     *
     * @return the final text, or {@link Optional#empty()} for a surfaced outcome.
     */
    public Optional<String> finalTextIfPresent() {
        return Optional.ofNullable(finalText);
    }
}
