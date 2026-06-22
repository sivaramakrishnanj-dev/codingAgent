package com.srk.codingagent.persistence;

import com.srk.codingagent.loop.VerifyOutcome;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The outcome-signal producer (component C14, US-16): when a unit of work concludes, it derives
 * a success/failure + effort signal from the terminal {@link VerifyOutcome} and appends it as an
 * {@code OUTCOME} event to the session log, in the aggregatable on-disk form
 * ({@code 06-formal/event.schema.json}, {@code $defs.outcome}) that
 * {@link SessionStore#deriveMeta} folds into a session's {@code outcomeSuccess} (AC-16.3).
 *
 * <p><b>Success derived from the final verification command's exit status (AC-16.2, RD-10,
 * INV-17, AC-20.4).</b> A unit of work's success is a zero exit from the configured test command
 * within the bound. The clean predicate for that is {@link VerifyOutcome#verified()} — {@code true}
 * only for {@link VerifyOutcome.Kind#VERIFIED} (the configured test command exited {@code 0}).
 * {@link VerifyOutcome.Kind#EXHAUSTED} (every attempt exited non-zero, including the {@code 124}
 * timeout) is failure. This producer derives {@code success} from {@code verified()} rather than
 * re-inspecting a raw exit code, so the success rule lives in exactly one place (the verify loop's
 * already-tested {@code verified()} predicate) and cannot drift from it. {@code iterations} is
 * {@link VerifyOutcome#iterations()} — the attempt that passed (VERIFIED) or the bound (EXHAUSTED).
 *
 * <p><b>No-verification disposition (AC-16.2, documented choice).</b> When no verification ran —
 * {@link VerifyOutcome.Kind#NO_TEST_COMMAND} (no test command configured), or no verify outcome at
 * all (the unit of work concluded before any verification, e.g. the change-turn surfaced an edge
 * condition) — there is <em>no exit status to derive a signal from</em>. AC-16.2 says the signal is
 * derived from "the final verification command's exit status"; with no such command there is no
 * exit status, so the cleanest reading is to record <em>no</em> outcome event rather than fabricate
 * a {@code success=true} (which would over-count) or a {@code success=false} (which would penalize
 * a unit of work that simply had nothing to verify). {@link #record} returns {@link Optional#empty()}
 * in that case (nothing appended); {@link #recordIfVerificationRan} is the {@link VerifyOutcome}-
 * shaped entry point both the producer and its callers use.
 *
 * <p><b>Boundary-captured timestamp (ADR-0005).</b> The {@code OUTCOME} event's timestamp comes from
 * the injected {@code clock} {@link Supplier}, never {@code Instant.now()} — mirroring how
 * {@link com.srk.codingagent.tool.memory.LearningProposer} and the
 * {@link com.srk.codingagent.context.Compactor} inject the boundary clock, so an appended outcome is
 * reproducible and tests are deterministic. The {@link EventLog} owns sequence numbering (INV-1) and
 * flushes the line per event (INV-2), so a recorded outcome is durable before the run reports it.
 *
 * <p>This collaborator owns only the derivation + the event append; it does not decide <em>when</em>
 * a unit of work has concluded (the workflow driver / run path does) nor read the signal back (that
 * is {@link SessionStore#deriveMeta}'s aggregation surface). Not thread-safe: it shares the session's
 * single-threaded {@link EventLog}.
 */
public final class OutcomeRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutcomeRecorder.class);

    private final EventLog log;
    private final Supplier<String> clock;
    private final String taskRef;

    /**
     * Creates a recorder bound to one unit of work's session log, clock, and task reference.
     *
     * @param log     the session event log an {@code OUTCOME} event is appended to (the same
     *                append + flush-per-event path everything else uses, INV-1/INV-2); must not be
     *                {@code null}.
     * @param clock   the boundary timestamp source for the appended event (ADR-0005 — never
     *                {@code Instant.now()}); must not be {@code null}.
     * @param taskRef the unit-of-work identifier the outcome is recorded under (AC-16.1 — recorded
     *                per task); non-blank. At M2 the brownfield run is free-form (not task-numbered),
     *                so the run path defaults this to the session lineage id; whatever identifier the
     *                caller supplies is carried verbatim into the {@code OUTCOME} event's
     *                {@code taskRef}.
     * @throws NullPointerException     if {@code log} or {@code clock} is {@code null}.
     * @throws IllegalArgumentException if {@code taskRef} is blank.
     */
    public OutcomeRecorder(EventLog log, Supplier<String> clock, String taskRef) {
        this.log = Objects.requireNonNull(log, "log");
        this.clock = Objects.requireNonNull(clock, "clock");
        Payloads.requireNonBlank(taskRef, "taskRef");
        this.taskRef = taskRef;
    }

    /**
     * Derives the outcome signal from a verification that actually ran and appends it as an
     * {@code OUTCOME} event (AC-16.1/AC-16.2/AC-16.3).
     *
     * <p>{@code success} is {@link VerifyOutcome#verified()} (true only for a zero exit from the
     * configured test command within the bound — RD-10/INV-17/AC-20.4); {@code iterations} is
     * {@link VerifyOutcome#iterations()}. The event is appended to the session log so the on-disk
     * JSONL line is the aggregatable form {@link SessionStore#deriveMeta} reads.
     *
     * <p>If no verification ran ({@link VerifyOutcome.Kind#NO_TEST_COMMAND}) there is no exit status
     * to derive a signal from, so nothing is appended and {@link Optional#empty()} is returned (see
     * the class doc's no-verification disposition).
     *
     * @param verify the terminal verify outcome of the unit of work; must not be {@code null}.
     * @return the appended {@code OUTCOME} {@link Event} (carrying its assigned {@code seq}), or
     *         {@link Optional#empty()} when no verification ran so no signal was recorded.
     * @throws NullPointerException if {@code verify} is {@code null}.
     * @throws PersistenceException if the {@code OUTCOME} event cannot be persisted (AC-13.4 —
     *                              surfaced, not swallowed, by the {@link EventLog}).
     */
    public Optional<Event> record(VerifyOutcome verify) {
        Objects.requireNonNull(verify, "verify");
        if (verify.kind() == VerifyOutcome.Kind.NO_TEST_COMMAND) {
            // AC-16.2: with no verification command there is no exit status to derive from, so there
            // is no success/failure signal to record. Record nothing rather than fabricate one.
            LOGGER.info("No verification ran (no test command); recording no outcome signal for '{}'",
                    taskRef);
            return Optional.empty();
        }
        boolean success = verify.verified();
        int iterations = verify.iterations();
        Event appended = log.append(new Event(
                log.nextSeq(), clock.get(), new OutcomePayload(taskRef, success, iterations)));
        LOGGER.info("Recorded outcome for '{}': success={}, iterations={} (AC-16.1/AC-16.2)",
                taskRef, success, iterations);
        return Optional.of(appended);
    }

    /**
     * Records the outcome for a unit of work that concluded, taking the verify outcome only when a
     * verification ran. This is the entry point the run path uses: a present outcome is recorded via
     * {@link #record}; an absent one (the unit of work concluded before any verification — e.g. the
     * change-turn surfaced an edge condition) records nothing, for the same reason
     * {@link VerifyOutcome.Kind#NO_TEST_COMMAND} does (no exit status to derive a signal from).
     *
     * @param verify the verify outcome if a verification ran, or {@link Optional#empty()} when none
     *               did; must not be {@code null} (use {@link Optional#empty()} for "no verify").
     * @return the appended {@code OUTCOME} event, or {@link Optional#empty()} when nothing was
     *         recorded.
     * @throws NullPointerException if {@code verify} is {@code null}.
     * @throws PersistenceException if the {@code OUTCOME} event cannot be persisted (AC-13.4).
     */
    public Optional<Event> recordIfVerificationRan(Optional<VerifyOutcome> verify) {
        Objects.requireNonNull(verify, "verify");
        if (verify.isEmpty()) {
            LOGGER.info("No verification ran (unit of work concluded before verifying); "
                    + "recording no outcome signal for '{}'", taskRef);
            return Optional.empty();
        }
        return record(verify.get());
    }
}
