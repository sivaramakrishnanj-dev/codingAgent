package com.srk.codingagent.tool.memory;

import java.util.List;

/**
 * Turns a compaction summary's text into the durable-learning candidates worth proposing for
 * memory (component C12, AC-18.5). The compaction prompt asks the summarizer for "durable
 * learnings worth remembering" as part of the summary; this seam is where that surfaced text
 * is mapped to concrete {@link LearningProposal}s the {@link LearningProposer} can run through
 * the propose-and-approve path.
 *
 * <p><b>Why a separate, injected strategy.</b> Extracting structured learnings from free-form
 * summary prose is a heuristic (or, in a richer future build, a model call) — a concern
 * distinct from the propose-and-approve lifecycle. Keeping it behind this seam lets the
 * {@link MemoryLearningHarvester} stay a thin wiring of "extract → propose", lets the
 * extraction evolve independently, and lets tests inject deterministic candidates so the
 * harvest wiring is exercised without coupling to any one extraction heuristic.
 *
 * <p><b>Extracting is not persisting (INV-13, AC-21.4).</b> Producing proposals writes
 * nothing — they are in-memory candidates. Persistence happens only after the developer
 * approves each one in {@link LearningProposer#propose(LearningProposal)}.
 */
@FunctionalInterface
public interface LearningExtractor {

    /** An extractor that surfaces no learnings — the empty-harvest baseline. */
    LearningExtractor NONE = summary -> List.of();

    /**
     * Extracts the durable-learning candidates a compaction summary surfaced.
     *
     * @param summary the compaction summary text; never {@code null}.
     * @return the learnings to propose, in summary order; empty when the summary surfaced none.
     *         Never {@code null}.
     */
    List<LearningProposal> extract(String summary);
}
