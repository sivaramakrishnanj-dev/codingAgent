package com.srk.codingagent.context;

import com.srk.codingagent.model.converse.ConverseMessage;
import com.srk.codingagent.model.converse.ModelBackendException;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.persistence.CompactionPayload;
import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.EdgeType;
import com.srk.codingagent.persistence.ErrorPayload;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.EventPayload;
import com.srk.codingagent.persistence.ModelResponsePayload;
import com.srk.codingagent.persistence.PersistenceException;
import com.srk.codingagent.persistence.SessionMeta;
import com.srk.codingagent.persistence.SessionReplay;
import com.srk.codingagent.persistence.SessionStatus;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.persistence.UserMessagePayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compaction-with-derivation (component C6, the Context Manager; ADR-0006, state-machine B).
 * When compaction is triggered — by the budget threshold ({@code 0.85 x window}, AC-18.1),
 * the manual {@code /compact} command (AC-18.2), or the {@code model_context_window_exceeded}
 * backstop — this orchestrator runs the three-step flow ADR-0006 mandates:
 *
 * <ol>
 *   <li><b>Summarize via a dedicated Converse call</b> (OQ-D): one model call to the
 *       configured summarizer model with a fixed <em>compaction system prompt</em> asking for
 *       outstanding task state, decisions made, files touched, open work, and durable
 *       learnings — enough to "continue without the developer re-explaining" (AC-18.4). The
 *       summary is text; it does not carry raw reasoning blocks.</li>
 *   <li><b>Derive a NEW session, never mutate</b> (INV-4, AC-18.3, LT3): a new session (new
 *       sessionId, supplied at the boundary) is seeded with the summary as an initial context
 *       block plus a configurable tail of recent verbatim turns, the child's {@code .meta.json}
 *       records the lineage edge ({@code edgeType = DERIVED_FROM}, {@code parentSessionId =}
 *       original), and a {@code COMPACTION(from,to,summaryRef)} event is appended to the
 *       <em>child</em> log marking its provenance.</li>
 *   <li><b>Preserve the original unchanged</b> (INV-5, AC-18.3, LT5): the original session's
 *       JSONL log is never edited, appended to, or deleted by this flow — it becomes a
 *       read-only lineage node, byte-identical after compaction (CT-INV-3).</li>
 * </ol>
 *
 * <p><b>Where the {@code COMPACTION} event lands (CT-INV-3 forces this).</b> State-machine B
 * LT3 appends a {@code COMPACTION(from,to,summaryRef)}; CT-INV-3 requires the parent's bytes
 * to be identical after compaction. The only reading consistent with both is to append the
 * lineage marker to the <em>derived</em> (child) log — it is a new event of the new session,
 * recording {@code from = original, to = derived}. The parent is left untouched (INV-4/INV-5).
 *
 * <p><b>INV-7 reasoning-signature replay (load-bearing).</b> The recent-tail verbatim turns are
 * carried forward by re-appending the original's {@code USER_MESSAGE}/{@code MODEL_RESPONSE}
 * events into the derived log unchanged — including any {@code reasoningContent} blocks with
 * their {@code signature}. Because the seed reuses the same persistence&rarr;replay path,
 * replaying the derived session and mapping it through {@link com.srk.codingagent.model.converse.ConverseWireMapper}
 * resends those reasoning signatures verbatim; a dropped or mutated signature would error the
 * first live Converse call in the derived session (INV-7, § 6.A.1).
 *
 * <p><b>Failure path</b> (LT4 &rarr; LT7 &rarr; machine A T15). If the summary/derive cannot
 * recover context — the summarizer Converse call fails, or it returns no usable summary text —
 * an {@code ERROR} event is appended to the <em>derived</em> log (the parent stays untouched)
 * and a {@link CompactionOutcome#failed()} is returned, carrying the context-exhausted exit
 * code (5) the CLI/REPL boundary maps to. This task owns producing the LT4 signal; the
 * {@code System.exit(5)} dispatch stays at the CLI boundary.
 *
 * <p><b>The learning-harvest seam (AC-18.5).</b> ADR-0006 says compaction "proposes durable
 * learnings for memory before archiving." T-2.2 left a clean seam: the summary surfaces
 * durable learnings as text (the compaction prompt asks for them). T-2.5 wires it as an
 * injected {@link LearningHarvester} the Compactor invokes <em>after</em> a usable summary is
 * produced and <em>before</em> the successor is derived (the "before archiving" window AC-18.5
 * requires). The harvester only <em>proposes</em> — nothing is persisted without the
 * developer's approval (AC-21.4, INV-13) — and the original is preserved unchanged regardless
 * (INV-5). When no memory harvest is configured the Compactor uses {@link LearningHarvester#NONE}
 * (a no-op), so compaction behaviour is unchanged where the harvest is not wired.
 *
 * <p>Boundary-captured ids and timestamps (ADR-0005): the derived session id is supplied in the
 * {@link CompactionRequest}; every appended event draws its timestamp from the injected
 * {@code clock}, never {@code Instant.now()}.
 */
public final class Compactor {

    private static final Logger LOGGER = LoggerFactory.getLogger(Compactor.class);

    /** The failure {@code category} recorded on the LT4 {@code ERROR} event. */
    private static final String FAILURE_CATEGORY = "compaction";

    /**
     * The fixed compaction system prompt (OQ-D, AC-18.4): it asks the summarizer for exactly
     * the carry-forward context ADR-0006 names — outstanding task state, decisions made, files
     * touched, open work, and durable learnings — so the derived session can continue without
     * the developer re-explaining. The "durable learnings" line is the seam T-2.5's harvest
     * (AC-18.5) reads.
     */
    static final String COMPACTION_SYSTEM_PROMPT =
            "You are compacting a long coding session into a concise hand-off summary so work "
                    + "can continue in a fresh conversation without the developer re-explaining "
                    + "anything. Summarize, as plain text: (1) the outstanding task state and "
                    + "goal, (2) the decisions made and why, (3) the files touched and what "
                    + "changed in each, (4) the open work still to do, and (5) any durable "
                    + "learnings worth remembering. Be specific and complete; omit pleasantries.";

    private final ModelClient modelClient;
    private final SessionStore store;
    private final SessionReplay replay;
    private final Supplier<String> clock;
    private final String summarizerModelId;
    private final int recentTailTurns;
    private final LearningHarvester harvester;

    /**
     * Creates a compactor over its collaborators with no learning harvest configured (the
     * harvest seam defaults to {@link LearningHarvester#NONE}). Equivalent to
     * {@link #Compactor(ModelClient, SessionStore, SessionReplay, Supplier, String, int,
     * LearningHarvester)} with {@code LearningHarvester.NONE}.
     *
     * @param modelClient       the Converse adapter the summary call goes through (C4); must
     *                          not be {@code null}.
     * @param store             the session/lineage store (C15) used to read the original, open
     *                          the derived log, and write the child's lineage meta; must not be
     *                          {@code null}.
     * @param replay            the events&rarr;messages projection (reused for the recent-tail
     *                          carryover and the summary-call transcript); must not be
     *                          {@code null}.
     * @param clock             the timestamp source for every appended event (ADR-0005 — never
     *                          {@code Instant.now()}); must not be {@code null}.
     * @param summarizerModelId the model id the summary Converse call uses (the same model or a
     *                          configured cheaper summarizer, ADR-0006 /
     *                          {@link com.srk.codingagent.config.ResolvedConfig#summarizerModelId()});
     *                          non-blank.
     * @param recentTailTurns   how many recent verbatim turns to carry forward into the derived
     *                          session (the configurable tail, ADR-0006); {@code >= 0} (0 seeds
     *                          only the summary).
     * @throws NullPointerException     if any reference argument is {@code null}.
     * @throws IllegalArgumentException if {@code summarizerModelId} is blank or
     *                                  {@code recentTailTurns} is negative.
     */
    public Compactor(
            ModelClient modelClient,
            SessionStore store,
            SessionReplay replay,
            Supplier<String> clock,
            String summarizerModelId,
            int recentTailTurns) {
        this(modelClient, store, replay, clock, summarizerModelId, recentTailTurns,
                LearningHarvester.NONE);
    }

    /**
     * Creates a compactor over its collaborators, including the learning-harvest seam (AC-18.5).
     *
     * @param modelClient       the Converse adapter the summary call goes through (C4); must
     *                          not be {@code null}.
     * @param store             the session/lineage store (C15) used to read the original, open
     *                          the derived log, and write the child's lineage meta; must not be
     *                          {@code null}.
     * @param replay            the events&rarr;messages projection (reused for the recent-tail
     *                          carryover and the summary-call transcript); must not be
     *                          {@code null}.
     * @param clock             the timestamp source for every appended event (ADR-0005 — never
     *                          {@code Instant.now()}); must not be {@code null}.
     * @param summarizerModelId the model id the summary Converse call uses (the same model or a
     *                          configured cheaper summarizer, ADR-0006 /
     *                          {@link com.srk.codingagent.config.ResolvedConfig#summarizerModelId()});
     *                          non-blank.
     * @param recentTailTurns   how many recent verbatim turns to carry forward into the derived
     *                          session (the configurable tail, ADR-0006); {@code >= 0} (0 seeds
     *                          only the summary).
     * @param harvester         the learning-harvest seam (AC-18.5) invoked after a usable
     *                          summary is produced and before the successor is derived; use
     *                          {@link LearningHarvester#NONE} for no harvest; must not be
     *                          {@code null}.
     * @throws NullPointerException     if any reference argument is {@code null}.
     * @throws IllegalArgumentException if {@code summarizerModelId} is blank or
     *                                  {@code recentTailTurns} is negative.
     */
    public Compactor(
            ModelClient modelClient,
            SessionStore store,
            SessionReplay replay,
            Supplier<String> clock,
            String summarizerModelId,
            int recentTailTurns,
            LearningHarvester harvester) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.store = Objects.requireNonNull(store, "store");
        this.replay = Objects.requireNonNull(replay, "replay");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (Objects.requireNonNull(summarizerModelId, "summarizerModelId").isBlank()) {
            throw new IllegalArgumentException("summarizerModelId must be non-blank");
        }
        if (recentTailTurns < 0) {
            throw new IllegalArgumentException(
                    "recentTailTurns must be >= 0 (was " + recentTailTurns + ")");
        }
        this.summarizerModelId = summarizerModelId;
        this.recentTailTurns = recentTailTurns;
        this.harvester = Objects.requireNonNull(harvester, "harvester");
    }

    /**
     * Runs one compaction-with-derivation: summarize the original session, derive a new session
     * seeded with the summary + recent tail, link it {@code DERIVED_FROM}, and leave the
     * original byte-identical (state-machine B LT2&rarr;LT3, or LT4 on failure).
     *
     * @param request the compaction inputs (repo, original/derived ids, trigger); must not be
     *                {@code null}.
     * @return a {@link CompactionOutcome#derived(String)} on success, or
     *         {@link CompactionOutcome#failed()} when the summary/derive cannot recover context.
     * @throws NullPointerException if {@code request} is {@code null}.
     */
    public CompactionOutcome compact(CompactionRequest request) {
        Objects.requireNonNull(request, "request");
        LOGGER.info("Compacting session {} -> {} (trigger={})",
                request.originalSessionId(), request.derivedSessionId(), request.trigger());

        List<Event> originalEvents = store.readEvents(request.repoKey(), request.originalSessionId());
        List<ConverseMessage> originalTranscript = replay.replay(originalEvents);

        String summary;
        try {
            summary = summarize(originalTranscript);
        } catch (ModelBackendException backendFailure) {
            // LT4: the summary call failed at the backend — context cannot be recovered.
            LOGGER.error("Compaction summary call failed for session {}",
                    request.originalSessionId(), backendFailure);
            return fail(request, "summary Converse call failed: " + backendFailure.getMessage());
        }

        if (summary.isBlank()) {
            // LT4: the model produced no usable summary text — derivation cannot carry context.
            LOGGER.warn("Compaction summary was empty for session {}; cannot derive context",
                    request.originalSessionId());
            return fail(request, "summarizer returned no usable summary text");
        }

        // AC-18.5: propose durable learnings from the summary for memory BEFORE archiving. The
        // harvest runs here — after a usable summary exists, before derive() seeds the successor
        // and writes the lineage edge (the archival moment). The harvester only PROPOSES; the
        // developer's approval decides what (if anything) is persisted (AC-21.2/AC-21.4, INV-13),
        // and the original is preserved unchanged regardless (INV-5).
        int harvested = harvester.harvest(summary);
        if (harvested > 0) {
            LOGGER.info("Harvested {} approved learning(s) from the compaction summary (AC-18.5)",
                    harvested);
        }

        return derive(request, originalEvents, summary);
    }

    /**
     * LT3: seed the derived session (summary + recent-tail verbatim turns), record the lineage
     * edge on its meta, and append the {@code COMPACTION} marker to the derived log. The
     * original log is never touched (INV-4/INV-5, CT-INV-3).
     */
    private CompactionOutcome derive(
            CompactionRequest request, List<Event> originalEvents, String summary) {
        List<EventPayload> seed = buildSeed(originalEvents, summary);
        int summaryRefSeq;
        try (EventLog derivedLog = store.openLog(request.repoKey(), request.derivedSessionId())) {
            summaryRefSeq = appendAll(derivedLog, seed);
            // The lineage marker is a new event of the NEW session (CT-INV-3 keeps the parent
            // byte-identical): record from = original, to = derived, summaryRef = the seed's seq.
            derivedLog.append(event(new CompactionPayload(
                    request.originalSessionId(),
                    request.derivedSessionId(),
                    "evt:" + summaryRefSeq,
                    request.trigger())));
        } catch (PersistenceException persistence) {
            // LT4: the derive could not be persisted — surface as a compaction failure.
            LOGGER.error("Compaction derive failed to persist for session {}",
                    request.derivedSessionId(), persistence);
            return fail(request, "failed to persist derived session: " + persistence.getMessage());
        }

        // The child's lineage edge (INV-4): DERIVED_FROM the original. deriveMeta aggregates the
        // child's own (seeded) events; we set the parent edge it deliberately leaves null.
        SessionMeta derived = store.deriveMeta(
                request.repoKey(), request.derivedSessionId(), SessionStatus.ACTIVE);
        store.writeMeta(new SessionMeta(
                derived.sessionId(), derived.repoKey(), derived.status(), derived.eventCount(),
                derived.inputTokens(), derived.outputTokens(),
                request.originalSessionId(), EdgeType.DERIVED_FROM,
                derived.outcomeSuccess()));

        LOGGER.info("Compaction derived session {} from {} (DERIVED_FROM, {} seed event(s))",
                request.derivedSessionId(), request.originalSessionId(), seed.size());
        return CompactionOutcome.derived(request.derivedSessionId());
    }

    /**
     * Builds the derived session's seed: the summary as an initial user context block, followed
     * by the recent-tail verbatim turns from the original (re-appended unchanged, preserving any
     * reasoning signatures — INV-7).
     */
    private List<EventPayload> buildSeed(List<Event> originalEvents, String summary) {
        List<EventPayload> seed = new ArrayList<>();
        seed.add(new UserMessagePayload(List.of(ContentBlock.text(summaryContext(summary)))));
        for (EventPayload turn : recentTail(originalEvents)) {
            seed.add(turn);
        }
        return seed;
    }

    /** Frames the summary as the initial context block of the derived conversation (AC-18.4). */
    private static String summaryContext(String summary) {
        return "[Compacted context summary of the prior conversation]\n" + summary;
    }

    /**
     * The recent-tail verbatim message-bearing turns of the original, in seq order, capped at
     * {@code recentTailTurns}. Only {@code USER_MESSAGE}/{@code MODEL_RESPONSE} payloads are
     * carried forward (the conversation turns); audit-only events are not turns. Carrying the
     * payloads unchanged is what preserves reasoning signatures verbatim (INV-7) and the
     * toolUse&harr;toolResult pairing (INV-6) of the tail.
     */
    private List<EventPayload> recentTail(List<Event> originalEvents) {
        List<EventPayload> turns = new ArrayList<>();
        for (Event event : originalEvents) {
            EventPayload payload = event.payload();
            if (payload instanceof UserMessagePayload || payload instanceof ModelResponsePayload) {
                turns.add(payload);
            }
        }
        if (recentTailTurns >= turns.size()) {
            return turns;
        }
        return new ArrayList<>(turns.subList(turns.size() - recentTailTurns, turns.size()));
    }

    /**
     * The dedicated summary Converse call (OQ-D): the original transcript plus the fixed
     * compaction system prompt, no tool config. The summary is the concatenated text of the
     * response's text blocks (raw reasoning blocks are not part of the summary text).
     */
    private String summarize(List<ConverseMessage> originalTranscript) {
        List<ConverseMessage> transcript = new ArrayList<>(originalTranscript);
        if (transcript.isEmpty()) {
            // A request needs at least one message; an empty original still gets a (trivial)
            // summary request seeded with the instruction so the call is well-formed.
            transcript.add(ConverseMessage.user(List.of(
                    ContentBlock.text("(the prior conversation had no recorded turns)"))));
        }
        ModelClient.Turn turn = modelClient.converse(
                summarizerModelId, transcript, List.of(COMPACTION_SYSTEM_PROMPT), null);
        return concatText(turn.response().content());
    }

    /** Concatenates the text blocks of a response (the summary text), newline-joined. */
    private static String concatText(List<ContentBlock> content) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.Text textBlock) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(textBlock.text());
            }
        }
        return text.toString();
    }

    /**
     * LT4: record the failure as an {@code ERROR} on the derived log (the parent stays
     * byte-identical, INV-4/CT-INV-3) carrying the context-exhausted exit code, and return the
     * failed outcome. A best-effort append: if even the error cannot be persisted, the failed
     * outcome is still returned so the run exits 5.
     */
    private CompactionOutcome fail(CompactionRequest request, String message) {
        try (EventLog derivedLog = store.openLog(request.repoKey(), request.derivedSessionId())) {
            derivedLog.append(event(new ErrorPayload(
                    FAILURE_CATEGORY, message, CompactionOutcome.CONTEXT_EXHAUSTED_EXIT_CODE)));
        } catch (PersistenceException persistence) {
            LOGGER.error("Failed to record compaction ERROR event for session {}",
                    request.derivedSessionId(), persistence);
        }
        return CompactionOutcome.failed();
    }

    /** Appends each payload to the log in order; returns the seq the FIRST payload was stamped with. */
    private int appendAll(EventLog log, List<EventPayload> payloads) {
        int firstSeq = log.nextSeq();
        for (EventPayload payload : payloads) {
            log.append(event(payload));
        }
        return firstSeq;
    }

    /** Builds an event with a boundary-captured timestamp (ADR-0005); seq is assigned at append. */
    private Event event(EventPayload payload) {
        return new Event(0, clock.get(), payload);
    }
}
