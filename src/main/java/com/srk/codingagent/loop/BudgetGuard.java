package com.srk.codingagent.loop;

import com.srk.codingagent.persistence.ModelUsagePayload;

/**
 * The budget-check seam the agent loop consults after each model turn (state machine A,
 * T13: {@code usage.inputTokens >= 0.85 x window OR /compact} hands off to lifecycle
 * machine B). It exists so the loop has the single, explicit hook where compaction (S6,
 * T-2.1/T-2.2) plugs in, without the loop itself owning any budget arithmetic or
 * compaction logic.
 *
 * <p><b>Scope (T-0.8).</b> This task delivers the seam and wires the loop to call it; it
 * does <em>not</em> implement compaction. The production wiring uses {@link #NONE} (a
 * no-op that never asks to compact), so the loop runs the full tool-use cycle today and a
 * later task swaps in a real guard that returns {@link Decision#COMPACT} when the input
 * token usage crosses the 0.85-of-window threshold. Modelling it as an injected seam (not
 * an inline {@code if}) keeps the compaction transition a clean extension point rather
 * than a TODO buried in the loop.
 */
@FunctionalInterface
public interface BudgetGuard {

    /** A guard that never requests compaction — the T-0.8 production wiring. */
    BudgetGuard NONE = usage -> Decision.CONTINUE;

    /** What the loop should do after a model turn, given the turn's token usage. */
    enum Decision {

        /** Stay in the loop; usage is within budget. */
        CONTINUE,

        /**
         * Usage crossed the budget threshold; compaction is due (state machine A, T13 →
         * S6). The compaction handler is a later task (T-2.1/T-2.2); when a real guard
         * returns this, the loop surfaces it as the budget seam rather than implementing
         * compaction here.
         */
        COMPACT
    }

    /**
     * Decides whether the loop should continue or hand off to compaction, given the usage
     * reported by the model turn just appended.
     *
     * @param usage the token usage of the most recent model turn; never {@code null}.
     * @return {@link Decision#CONTINUE} to stay in the loop, or {@link Decision#COMPACT}
     *         to signal the compaction threshold was crossed.
     */
    Decision evaluate(ModelUsagePayload usage);
}
