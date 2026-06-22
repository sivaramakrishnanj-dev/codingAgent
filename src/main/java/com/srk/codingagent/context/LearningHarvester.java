package com.srk.codingagent.context;

/**
 * The learning-harvest seam the {@link Compactor} invokes during compaction (component C6 →
 * C12, ADR-0006, AC-18.5). When durable learnings are identified in a compaction summary, the
 * agent proposes them for memory <em>before</em> the original is archived (the compaction
 * moment is the natural harvest trigger). The Compactor produces the summary text; this seam
 * is where the durable-learnings the summary surfaced are extracted and run through the
 * propose-and-approve path (US-21).
 *
 * <p><b>Why an injected seam (not inline).</b> The harvest is the C6 → C12 hand-off: the
 * Context Manager (which owns compaction) proposes learnings to the memory subsystem (which
 * owns the propose-and-approve write path). Modelling it as an injected collaborator — default
 * {@link #NONE} — keeps the Compactor's single responsibility (summarize + derive + preserve)
 * intact and lets the real harvest (the memory propose-and-approve wiring, T-2.5) plug in
 * without the Compactor depending on the memory store. This mirrors how T-2.1's
 * {@code BudgetGuard.NONE} seam was swapped for a real guard.
 *
 * <p><b>Before archiving (AC-18.5, load-bearing).</b> The Compactor calls
 * {@link #harvest(String)} after the summary is produced but before it derives the successor
 * session — so the propose-and-approve happens in the "before archiving" window AC-18.5
 * requires. The original is preserved unchanged regardless (INV-5); the harvest only
 * <em>proposes</em>, so a developer who rejects every learning leaves nothing persisted
 * (AC-21.2/AC-21.4/INV-13).
 */
@FunctionalInterface
public interface LearningHarvester {

    /**
     * A harvester that proposes nothing — the no-compaction-harvest wiring (and what the
     * Compactor uses when no memory harvest is configured). Keeps the seam a clean extension
     * point rather than a {@code null} the Compactor must guard.
     */
    LearningHarvester NONE = summary -> 0;

    /**
     * Harvests durable learnings from a compaction summary by proposing them for memory
     * (AC-18.5 → US-21): extract the durable-learnings the summary surfaced and run each
     * through the propose-and-approve path. Implementations must only <em>propose</em> —
     * nothing is persisted without the developer's approval (AC-21.4, INV-13).
     *
     * @param summary the compaction summary text the summarizer produced (the compaction
     *                prompt asked for durable learnings as part of it); never {@code null} or
     *                blank when called by the Compactor.
     * @return the number of learnings the developer approved and that were persisted (0 when
     *         none were proposed or none were approved); {@code >= 0}.
     */
    int harvest(String summary);
}
