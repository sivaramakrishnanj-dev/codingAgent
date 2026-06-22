package com.srk.codingagent.subagent;

import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.persistence.EventLog;

/**
 * The seam through which the {@link SubAgentOrchestrator} builds the runnable <em>child</em>
 * sub-agent for one spawn (ADR-0010: "starts a new nested Agent Loop… fresh context"). The
 * orchestrator owns the isolation policy — the child's own session log, its fresh
 * (no-inherited-grants) permission gate, its resolved model id, its scoped prompt — and hands
 * those (as a {@link ChildLoopContext}) to this factory, which assembles the child loop's
 * remaining collaborators and returns a {@link ChildAgentRun} the orchestrator executes under
 * the wall-clock budget.
 *
 * <p><b>Why a seam (and why it preserves the D2 wire path).</b> Production wires the
 * {@link ChildAgentRun} to {@code () -> agentLoop.run(context.prompt())} over a <em>real</em>
 * {@link AgentLoop} (itself over the real {@code ModelClient} and {@code ConverseWireMapper}),
 * so a child tool-use cycle's toolResult routes through the same D2-fixed wire path the parent
 * uses — a plain-string toolResult lands in the Converse {@code text} member, never
 * {@code json} (reused by construction). Modelling child-loop construction as an injected
 * factory keeps the orchestrator's isolation/budget/lineage logic unit-testable (a test injects
 * a child run driven by a scripted Bedrock double), while the live G2 smoke exercises the real
 * nested loop end-to-end.
 *
 * <p>The child must be built with: the supplied child {@link EventLog} (its OWN session log —
 * the N=1 clean design that sidesteps the shared-writer hazard, ADR-0010 Notes), a permission
 * gate backed by a fresh empty {@code GrantStore} (no inherited grants, AC-10.6, INV-10) — both
 * carried on the {@link ChildLoopContext} — and the resolved child {@code modelId}.
 */
@FunctionalInterface
public interface ChildAgentLoopFactory {

    /**
     * Builds the runnable nested child sub-agent for one spawn.
     *
     * @param childContext the resolved child context — its own session log, fresh permission
     *                     gate (no inherited grants), resolved model id, and scoped prompt;
     *                     never {@code null}.
     * @return a {@link ChildAgentRun} the orchestrator executes under the budget; never
     *         {@code null}.
     */
    ChildAgentRun create(ChildLoopContext childContext);
}
