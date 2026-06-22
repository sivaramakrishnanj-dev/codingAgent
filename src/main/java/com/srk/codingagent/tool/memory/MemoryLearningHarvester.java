package com.srk.codingagent.tool.memory;

import com.srk.codingagent.context.LearningHarvester;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The compaction learning-harvest wiring (component C6 → C12, AC-18.5): the real
 * implementation of the Compactor's {@link LearningHarvester} seam. It bridges the Context
 * Manager (which produces the compaction summary) to the memory propose-and-approve path
 * (which owns approval + curated writes): extract the durable learnings the summary surfaced
 * ({@link LearningExtractor}), then run <em>each</em> through the
 * {@link LearningProposer#propose(LearningProposal)} path — the SAME propose-and-approve flow
 * the in-loop "agent discovered a learning" path uses (US-21).
 *
 * <p><b>Propose, never auto-extract (AC-21.4, INV-13).</b> The harvest <em>proposes</em>;
 * it does not persist on its own. Every candidate is presented to the developer (via the
 * proposer's {@link LearningApprover}); a learning the developer rejects persists nothing
 * (AC-21.2). A summary that surfaces no learnings — or one whose every learning is rejected —
 * harvests zero entries, which is the correct no-auto-extract behaviour at the harvest moment.
 *
 * <p><b>Before archiving (AC-18.5).</b> The Compactor invokes {@link #harvest(String)} after a
 * usable summary is produced and before it derives the successor session, so the proposals are
 * made in the "before archiving" window the AC requires. This class does not order the call —
 * the Compactor does; this class only does the extract-then-propose work when called.
 */
public final class MemoryLearningHarvester implements LearningHarvester {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryLearningHarvester.class);

    private final LearningExtractor extractor;
    private final LearningProposer proposer;

    /**
     * Creates the harvester over its extraction strategy and the propose-and-approve path.
     *
     * @param extractor the strategy that turns the summary text into learning candidates
     *                  (AC-18.5); must not be {@code null}.
     * @param proposer  the propose-and-approve path each candidate is run through (US-21,
     *                  AC-21.1/AC-21.2/AC-21.3); must not be {@code null}.
     * @throws NullPointerException if {@code extractor} or {@code proposer} is {@code null}.
     */
    public MemoryLearningHarvester(LearningExtractor extractor, LearningProposer proposer) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.proposer = Objects.requireNonNull(proposer, "proposer");
    }

    /**
     * Extracts the durable learnings the compaction summary surfaced and proposes each for
     * memory through the propose-and-approve path (AC-18.5 → US-21). Only the learnings the
     * developer approves are persisted (AC-21.2/AC-21.4, INV-13).
     *
     * @param summary the compaction summary text; must not be {@code null}.
     * @return the number of proposed learnings the developer approved and that were persisted;
     *         {@code >= 0}.
     * @throws NullPointerException if {@code summary} is {@code null}.
     */
    @Override
    public int harvest(String summary) {
        Objects.requireNonNull(summary, "summary");
        List<LearningProposal> candidates = extractor.extract(summary);
        LOGGER.info("Compaction harvest extracted {} durable-learning candidate(s) to propose (AC-18.5)",
                candidates.size());
        int persisted = 0;
        for (LearningProposal candidate : candidates) {
            if (proposer.propose(candidate).isPresent()) {
                persisted++;
            }
        }
        return persisted;
    }
}
