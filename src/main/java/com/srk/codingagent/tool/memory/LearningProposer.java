package com.srk.codingagent.tool.memory;

import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStatus;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.MemoryWritePayload;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The propose-and-approve write path (component C12, US-21, ADR-0007): the "approved" half of
 * the curated-write lifecycle that complements the explicit {@link WriteMemoryTool}. When the
 * agent identifies a durable, reusable learning — a mistake + fix discovered in the loop, a
 * project convention, or a learning harvested at compaction (AC-18.5) — it proposes the
 * learning to the developer for approval (AC-21.1). The developer's answer decides:
 *
 * <ul>
 *   <li><b>Approved (AC-21.3):</b> the learning is written as a curated memory entry with
 *       provenance — the same {@link MemoryStore#write} + {@code MEMORY_WRITE} event the
 *       explicit {@code write_memory} tool uses (AC-12.4) — only this time after approval.</li>
 *   <li><b>Not approved (AC-21.2):</b> nothing is persisted. No file, no index line, no
 *       {@code MEMORY_WRITE} event. The proposal is discarded.</li>
 * </ul>
 *
 * <p><b>No auto-extract (AC-21.4, INV-13).</b> There is no path here that persists a learning
 * without the developer's approval. Every {@link #propose} call routes through the
 * {@link LearningApprover}; a {@link PermissionDecisionOutcome#DENY} writes nothing. This is
 * the anti-poisoning stance ADR-0007 names: auto-harvest is future-work (the RL ladder), not
 * v1.
 *
 * <p><b>Boundary-captured provenance (ADR-0005/ADR-0007).</b> The {@code created} timestamp
 * comes from the injected {@code clock} {@link Supplier}, and the {@code originSession} is the
 * injected session id — neither is derived in-process, so an approved write is reproducible
 * and tests are deterministic, exactly as {@link WriteMemoryTool} does it.
 *
 * <p>This collaborator owns only the approval gate + the store write + the event append; it
 * does not decide <em>what</em> to propose. The compaction harvest (the
 * {@code LearningHarvester} the {@link com.srk.codingagent.context.Compactor} invokes before
 * archiving) and the in-loop "agent discovered a learning" path both feed proposals into this
 * one path, so the approve→persist→log behaviour lives in exactly one place.
 */
public final class LearningProposer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LearningProposer.class);

    private final MemoryStore store;
    private final EventLog log;
    private final LearningApprover approver;
    private final Supplier<String> clock;
    private final String originSession;
    private final String repoKey;

    /**
     * Creates the proposer over its collaborators.
     *
     * @param store         the memory store an approved learning is written to (the same seam
     *                      {@link WriteMemoryTool} uses); must not be {@code null}.
     * @param log           the session event log an approved write's {@code MEMORY_WRITE}
     *                      event is appended to (AC-12.4); must not be {@code null}.
     * @param approver      the developer approve/deny boundary on a proposed learning
     *                      (AC-21.1/AC-21.2); must not be {@code null}.
     * @param clock         the boundary timestamp source for an approved entry's
     *                      {@code created} (ADR-0005 — never {@code Instant.now()}); must not
     *                      be {@code null}.
     * @param originSession the session id that produced the proposal (provenance, AC-12.2);
     *                      non-blank.
     * @param repoKey       the repository key (boundary-captured) for the PROJECT tier;
     *                      non-blank.
     * @throws NullPointerException     if {@code store}, {@code log}, {@code approver}, or
     *                                  {@code clock} is {@code null}.
     * @throws IllegalArgumentException if {@code originSession} or {@code repoKey} is blank.
     */
    public LearningProposer(
            MemoryStore store,
            EventLog log,
            LearningApprover approver,
            Supplier<String> clock,
            String originSession,
            String repoKey) {
        this.store = Objects.requireNonNull(store, "store");
        this.log = Objects.requireNonNull(log, "log");
        this.approver = Objects.requireNonNull(approver, "approver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.originSession = requireNonBlank(originSession, "originSession");
        this.repoKey = requireNonBlank(repoKey, "repoKey");
    }

    /**
     * Proposes a durable learning to the developer and, only if approved, writes it as a
     * curated memory entry with provenance and logs the {@code MEMORY_WRITE} event
     * (AC-21.1 → AC-21.3); on a deny, persists nothing (AC-21.2).
     *
     * @param proposal the learning to propose; must not be {@code null}.
     * @return the persisted {@link MemoryEntry} when the developer approved, or
     *         {@link Optional#empty()} when the developer did not approve (nothing written).
     * @throws NullPointerException if {@code proposal} is {@code null}.
     */
    public Optional<MemoryEntry> propose(LearningProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        PermissionDecisionOutcome decision = approver.approve(proposal);
        if (decision != PermissionDecisionOutcome.APPROVE) {
            // AC-21.2 / AC-21.4 / INV-13: a learning the developer did not approve is never
            // persisted — no file, no index line, no MEMORY_WRITE event.
            LOGGER.info("Proposed learning '{}' was not approved; not persisting (AC-21.2)",
                    proposal.slug());
            return Optional.empty();
        }
        MemoryEntry entry = new MemoryEntry(
                proposal.slug(), proposal.tier(), clock.get(), originSession,
                proposal.why(), MemoryStatus.ACTIVE, proposal.body());
        // AC-21.3: write the approved learning with provenance — the SAME store.write +
        // MEMORY_WRITE log the explicit write_memory path uses (AC-12.4), only after approval.
        store.write(entry, repoKey);
        log.append(new Event(log.nextSeq(), clock.get(),
                new MemoryWritePayload(entry.slug(), entry.tier().name(), originSession, entry.why())));
        LOGGER.info("Approved learning '{}' written into {} memory (AC-21.3)",
                entry.slug(), entry.tier());
        return Optional.of(entry);
    }

    private static String requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }
}
