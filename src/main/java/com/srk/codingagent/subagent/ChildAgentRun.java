package com.srk.codingagent.subagent;

import com.srk.codingagent.loop.LoopOutcome;

/**
 * One runnable invocation of a child sub-agent's nested loop (ADR-0010). The
 * {@link SubAgentOrchestrator} obtains a {@code ChildAgentRun} from the
 * {@link ChildAgentLoopFactory} and executes it on a worker thread under the wall-clock
 * budget, treating the returned {@link LoopOutcome} as the child's terminal result.
 *
 * <p>This is the narrow seam the orchestrator depends on instead of the concrete (final)
 * {@link com.srk.codingagent.loop.AgentLoop}: the orchestrator needs only "run the child and
 * give me its {@link LoopOutcome}". Production wires it to {@code () -> agentLoop.run(prompt)}
 * over a real {@link com.srk.codingagent.loop.AgentLoop} (so the child reuses the same
 * {@code ModelClient}/{@code ConverseWireMapper} wire path — D2-safe by construction); tests
 * supply a controllable run (one that blocks to drive the over-budget path, throws to drive
 * the failure path, or drives a real nested loop over a scripted Bedrock double).
 *
 * <p>The orchestrator runs this on a separate worker thread and may interrupt it on budget
 * expiry (AC-17.6); an implementation that performs blocking work should honour interruption.
 */
@FunctionalInterface
public interface ChildAgentRun {

    /**
     * Runs the child loop to its terminal outcome.
     *
     * @return the child loop's terminal {@link LoopOutcome}; never {@code null}.
     * @throws RuntimeException if the child loop fails; the orchestrator catches it and
     *                          returns a failure result to the parent (AC-17.6).
     */
    LoopOutcome run();
}
