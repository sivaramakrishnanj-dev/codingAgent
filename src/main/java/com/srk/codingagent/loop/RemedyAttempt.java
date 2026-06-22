package com.srk.codingagent.loop;

import com.srk.codingagent.tool.CommandResult;

/**
 * The remedy seam the {@link VerifyLoop} invokes between two failed verification attempts:
 * given the failing command's output, it feeds that output back into the agent's reasoning
 * and attempts a fix (AC-20.3 &mdash; "when a verification command exits non-zero, the agent
 * shall feed the failure output back into its reasoning and attempt a remedy").
 *
 * <p><b>Boundary (T-1.4 scope).</b> The model-driven remedy &mdash; the actual turn that reads
 * the failure and edits code &mdash; belongs to the workflow driver (the brownfield driver
 * T-1.6 and the greenfield implement loop T-3.3), which has the {@link AgentLoop} in hand.
 * T-1.4's deliverable is the bounded run&rarr;check&rarr;remedy&rarr;retry control and the
 * surface-after-N (AC-20.5); the "attempt a remedy" step is modelled as this injected seam so
 * the loop logic is fully unit-testable without a model in the loop. The driver supplies a
 * real remedy that runs a model turn; tests supply a scripted remedy (e.g. one that flips a
 * later attempt to success, or a no-op).
 *
 * <p>The remedy is invoked only <em>between</em> attempts &mdash; after a failing attempt that
 * is not the last one &mdash; never after the final attempt (there is no point fixing for an
 * attempt that will not run) and never after a success.
 */
@FunctionalInterface
public interface RemedyAttempt {

    /**
     * A remedy that does nothing &mdash; the default for a verify loop with no remedy wired
     * (e.g. a pure re-run, or a unit test of the bound). With it, a failing sequence simply
     * re-runs the command until success or exhaustion.
     */
    RemedyAttempt NONE = failure -> { };

    /**
     * Feeds the failing attempt's output back into reasoning and attempts a remedy before
     * the next attempt (AC-20.3).
     *
     * @param failure the failing attempt's result (non-zero exit), whose
     *                {@link CommandResult#stdout()}/{@link CommandResult#stderr()} carry the
     *                output to reason over; never {@code null}.
     */
    void attempt(CommandResult failure);
}
