package com.srk.codingagent.cli;

import com.srk.codingagent.context.CompactionOutcome;
import com.srk.codingagent.context.CompactionRequest;
import com.srk.codingagent.context.Compactor;
import com.srk.codingagent.loop.CompactionSeam;
import com.srk.codingagent.model.converse.ConverseMessage;
import com.srk.codingagent.persistence.CompactionTrigger;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.SessionReplay;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.persistence.StopReason;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@link CompactionSeam}, extracted from the (JaCoCo-excluded)
 * {@link AgentLoopFactory} so the live compaction-and-continue wiring is exercised under the
 * coverage gate (the T-2.7 lesson: business wiring lives in a gate-covered seam, not in the
 * excluded composition root). It bridges the {@link com.srk.codingagent.loop.AgentLoop}'s T13
 * budget seam to the real {@link Compactor} of component C6 (ADR-0006): on a {@code COMPACT}
 * signal it runs one compaction-with-derivation and replays the derived session back into the
 * {@code messages[]} the loop continues driving (state machine A T14), or surfaces the
 * context-exhausted reason on a compaction failure (T15).
 *
 * <p><b>The live session identity (the gap this closes).</b> The loop holds an
 * {@link com.srk.codingagent.persistence.EventLog} but not the {@code repoKey} / session id of
 * the live conversation it is appending to (those live in {@link Main} / this factory as
 * {@code ONE_SHOT_LINEAGE}). This seam threads that identity in so the {@link Compactor} can
 * read the original session's events and open the derived log. The derived session id is drawn
 * from a boundary-captured supplier (ADR-0005 — never {@code UUID.randomUUID()} inside the
 * orchestration), so the derived log path is deterministic and a run is reproducible.
 *
 * <p><b>What it returns to the loop.</b>
 * <ul>
 *   <li><b>Derived (LT3, T14).</b> The {@link Compactor} wrote the derived session (summary
 *       context block + recent-tail verbatim turns, INV-7 signatures intact, parent byte-identical
 *       INV-4/INV-5). This seam then replays the derived session's events via the same
 *       {@link SessionReplay} the resume path uses (events &rarr; {@code messages[]}), so the loop
 *       continues with exactly the derived conversation's transcript — the next Converse call is a
 *       well-formed request in the derived session carrying the carried-forward context and any
 *       reasoning signatures.</li>
 *   <li><b>Failed (LT4 &rarr; LT7, T15).</b> The {@link Compactor} appended an {@code ERROR} to
 *       the derived log and returned {@link CompactionOutcome#failed()} (carrying exit code 5).
 *       This seam surfaces {@link StopReason#MODEL_CONTEXT_WINDOW_EXCEEDED}, which the one-shot
 *       boundary ({@link OneShotRunner}) already maps to the context-exhausted exit code 5
 *       (cli-exit-codes 5, CT-SM-7) — so a failed compaction lands on exit 5 with no change to the
 *       tested outcome mapping.</li>
 * </ul>
 *
 * <p>The trigger recorded on the {@code COMPACTION} event is {@link CompactionTrigger#THRESHOLD}:
 * the live auto-compaction path the {@code TokenBudgetGuard} fires (AC-18.1). The manual
 * {@code /compact} path (AC-18.2) reuses the same {@link Compactor} with
 * {@link CompactionTrigger#MANUAL} from the REPL when that command lands (a later task); this
 * seam carries the threshold trigger because it is invoked from the budget seam.
 */
final class CompactingSeam implements CompactionSeam {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompactingSeam.class);

    private final Compactor compactor;
    private final SessionStore sessions;
    private final SessionReplay replay;
    private final String repoKey;
    private final String originalSessionId;
    private final Supplier<String> derivedSessionIds;

    /**
     * Creates the production compaction seam over its collaborators. Every argument is
     * constructible without a live AWS call (the {@link Compactor} carries the already-built
     * {@link com.srk.codingagent.model.converse.ModelClient}), so this seam is unit-testable
     * against temporary stores and a scripted Bedrock double.
     *
     * @param compactor          the compaction-with-derivation orchestrator (C6, ADR-0006); must
     *                           not be {@code null}.
     * @param sessions           the session store the derived session is read back from to
     *                           continue the loop (events &rarr; {@code messages[]}); must not be
     *                           {@code null}.
     * @param replay             the events&rarr;messages projection used to replay the derived
     *                           session (the same seam {@code resume} uses); must not be
     *                           {@code null}.
     * @param repoKey            the repository key both the original and derived sessions are
     *                           scoped to; non-blank.
     * @param originalSessionId  the live session being compacted (preserved unchanged, INV-5);
     *                           non-blank.
     * @param derivedSessionIds  the boundary-captured source of derived session ids (ADR-0005 —
     *                           never {@code UUID.randomUUID()} here); must not be {@code null}
     *                           and must yield a non-blank id differing from the original.
     * @throws NullPointerException     if a required reference argument is {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} or {@code originalSessionId} is blank.
     */
    CompactingSeam(
            Compactor compactor,
            SessionStore sessions,
            SessionReplay replay,
            String repoKey,
            String originalSessionId,
            Supplier<String> derivedSessionIds) {
        this.compactor = Objects.requireNonNull(compactor, "compactor");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.replay = Objects.requireNonNull(replay, "replay");
        this.repoKey = requireNonBlank(repoKey, "repoKey");
        this.originalSessionId = requireNonBlank(originalSessionId, "originalSessionId");
        this.derivedSessionIds = Objects.requireNonNull(derivedSessionIds, "derivedSessionIds");
    }

    /**
     * Runs one compaction-with-derivation and returns how the loop should proceed: continue in
     * the derived session (T14) or surface context-exhausted (T15).
     *
     * @param transcript the live conversation transcript (unused here — the {@link Compactor}
     *                   reads the original session's persisted events directly, which is the
     *                   durable record the recent-tail carryover and summary call replay from);
     *                   never {@code null}.
     * @param stopReason the stop reason of the turn that triggered compaction (unused on the
     *                   compaction path — a derive continues, a failure surfaces the
     *                   context-exhausted reason); never {@code null}.
     * @return a continue-in-derived result on success, or a surface-context-exhausted result on
     *         failure; never {@code null}.
     */
    @Override
    public CompactionResult compact(List<ConverseMessage> transcript, StopReason stopReason) {
        String derivedId = requireNonBlank(derivedSessionIds.get(), "derivedSessionId");
        CompactionRequest request = new CompactionRequest(
                repoKey, originalSessionId, derivedId, CompactionTrigger.THRESHOLD);
        CompactionOutcome outcome = compactor.compact(request);
        if (!outcome.succeeded()) {
            // LT4 -> LT7 -> T15: the summary/derive could not recover context. Surface the
            // context-exhausted reason; OneShotRunner maps it to exit 5 (cli-exit-codes 5).
            LOGGER.warn("Live compaction failed for session {}; surfacing context-exhausted (exit {})",
                    originalSessionId, CompactionOutcome.CONTEXT_EXHAUSTED_EXIT_CODE);
            return CompactionResult.surfaced(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED);
        }
        // T14: replay the derived session's events into the messages[] the loop continues in.
        List<Event> derivedEvents = sessions.readEvents(repoKey, derivedId);
        List<ConverseMessage> derivedTranscript = replay.replay(derivedEvents);
        LOGGER.info("Live compaction derived session {} from {}; continuing in it with {} message(s)",
                derivedId, originalSessionId, derivedTranscript.size());
        return CompactionResult.continued(derivedTranscript);
    }

    private static String requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }
}
