package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.ClasspathSchemaLoader;
import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.tool.CommandResult;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Unit tests for {@link OutcomeRecorder} — the outcome-signal producer (component C14, US-16). The
 * SUT is a real recorder over a real {@link EventLog} (a {@link StringWriter} or a real
 * {@link SessionStore} on a {@code @TempDir}); only the inputs ({@link VerifyOutcome}s) are
 * fixtures, never the SUT.
 *
 * <p><b>Oracles trace to the spec, never to the producer's own code:</b>
 * <ul>
 *   <li><b>AC-16.2 / RD-10 / INV-17 / AC-20.4 — derivation truth table.</b> Success is a zero exit
 *       from the configured test command. {@link VerifyOutcome.Kind#VERIFIED} (zero exit within the
 *       bound) &rarr; {@code success=true}; {@link VerifyOutcome.Kind#EXHAUSTED} (non-zero on every
 *       attempt) &rarr; {@code success=false}; {@code iterations} equals
 *       {@link VerifyOutcome#iterations()}.</li>
 *   <li><b>AC-16.2 — no-verification disposition (documented choice).</b> With no test command
 *       configured ({@link VerifyOutcome.Kind#NO_TEST_COMMAND}) there is no exit status to derive a
 *       signal from, so no outcome event is recorded — not a fabricated success or failure.</li>
 *   <li><b>AC-16.1 — per-task.</b> The recorded outcome carries the supplied {@code taskRef}.</li>
 *   <li><b>AC-16.3 / CT-SCH (OUTCOME) — persisted, schema-valid, aggregatable.</b> The appended
 *       JSONL line validates against {@code event.schema.json} {@code $defs.outcome} (the same
 *       networknt validation {@link EventSchemaContractTest} does), and {@link SessionStore#deriveMeta}
 *       reads the recorded outcome back into {@code SessionMeta.outcomeSuccess()} — i.e. the event the
 *       recorder writes is the one the aggregation path reads.</li>
 *   <li><b>ADR-0005 — boundary clock.</b> The appended event's {@code ts} is the injected clock's
 *       value, not {@code Instant.now()}.</li>
 * </ul>
 */
class OutcomeRecorderTest {

    private static final String TASK_REF = "github.com-example-widget#one-shot";
    private static final String TS = "2026-06-23T10:00:00Z";
    private static final String EVENT_SCHEMA_ID = "https://codingagent.srk/schemas/event.schema.json";
    private static final String CONTENT_BLOCK_SCHEMA_ID =
            "https://codingagent.srk/schemas/content-block.schema.json";

    private static JsonSchema eventSchema;

    @BeforeAll
    static void loadSchema() {
        // Mirror EventSchemaContractTest: validate emitted lines against the FORMAL schema, so the
        // oracle for "the OUTCOME line is the aggregatable shape" is the spec artifact, not this
        // code's incidental serialization.
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder
                        .schemaLoaders(loaders -> loaders.add(new ClasspathSchemaLoader()))
                        .schemaMappers(mappers -> mappers.mappings(Map.of(
                                EVENT_SCHEMA_ID, "classpath:schemas/event.schema.json",
                                CONTENT_BLOCK_SCHEMA_ID, "classpath:schemas/content-block.schema.json"))));
        eventSchema = factory.getSchema(SchemaLocation.of(EVENT_SCHEMA_ID));
    }

    private static Set<ValidationMessage> validate(String json) {
        return eventSchema.validate(json, InputFormat.JSON);
    }

    private static VerifyOutcome verified(int iterations) {
        return VerifyOutcome.verified(
                iterations, CommandResult.completed("mvn test", 0, "BUILD SUCCESS", "", 12L));
    }

    private static VerifyOutcome exhausted(int iterations) {
        return VerifyOutcome.exhausted(
                iterations, CommandResult.completed("mvn test", 1, "Tests run: 9, Failures: 1", "", 9L));
    }

    private static OutcomePayload onlyOutcomeIn(String jsonl) {
        EventCodec codec = new EventCodec();
        List<Event> events = jsonl.lines()
                .filter(line -> !line.isBlank())
                .map(codec::decode)
                .toList();
        List<Event> outcomes = events.stream()
                .filter(e -> e.payload() instanceof OutcomePayload)
                .toList();
        assertEquals(1, outcomes.size(), "exactly one OUTCOME event must have been appended");
        return (OutcomePayload) outcomes.get(0).payload();
    }

    @Test
    @DisplayName("AC-16.2/RD-10/INV-17: a VERIFIED outcome (zero exit) records success=true")
    void verifiedRecordsSuccessTrue() {
        // Oracle: RD-10 / INV-17 / AC-20.4 — a zero exit from the configured test command is the
        // unit-of-work success signal. VerifyOutcome.verified() is true ONLY for Kind.VERIFIED, so a
        // VERIFIED outcome must record success=true. (Truth derived from the spec, not the producer.)
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);

        Optional<Event> appended = recorder.record(verified(3));

        assertTrue(appended.isPresent(), "a VERIFIED outcome records an OUTCOME event");
        OutcomePayload payload = onlyOutcomeIn(sink.toString());
        assertTrue(payload.success(), "AC-16.2/RD-10: a zero exit (VERIFIED) is success=true");
    }

    @Test
    @DisplayName("AC-16.2/RD-10/INV-17: an EXHAUSTED outcome (non-zero on every attempt) records success=false")
    void exhaustedRecordsSuccessFalse() {
        // Oracle: RD-10 / INV-17 — success is a zero exit; EXHAUSTED means the command exited
        // non-zero on every attempt within the bound (no zero exit), so it is NOT success. The
        // signal must be success=false, not a fabricated success.
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);

        Optional<Event> appended = recorder.record(exhausted(5));

        assertTrue(appended.isPresent(), "an EXHAUSTED outcome records an OUTCOME event");
        OutcomePayload payload = onlyOutcomeIn(sink.toString());
        assertFalse(payload.success(), "AC-16.2/RD-10: no zero exit (EXHAUSTED) is success=false");
    }

    @Test
    @DisplayName("AC-16.1: the recorded outcome's iterations equal VerifyOutcome.iterations() — VERIFIED")
    void verifiedRecordsItsIterations() {
        // Oracle: AC-16.1 — the outcome captures "the number of iterations taken". The source of
        // truth for that count is the VerifyOutcome's own iterations() (the attempt that passed),
        // so the recorded value must equal it.
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);
        VerifyOutcome verify = verified(3);

        recorder.record(verify);

        assertEquals(verify.iterations(), onlyOutcomeIn(sink.toString()).iterations(),
                "AC-16.1: recorded iterations must equal VerifyOutcome.iterations() (here 3)");
        assertEquals(3, onlyOutcomeIn(sink.toString()).iterations(),
                "the VERIFIED attempt count (3) is recorded verbatim");
    }

    @Test
    @DisplayName("AC-16.1: the recorded outcome's iterations equal VerifyOutcome.iterations() — EXHAUSTED (the bound)")
    void exhaustedRecordsTheBoundAsIterations() {
        // Oracle: AC-16.1 — iterations taken. For EXHAUSTED, VerifyOutcome.iterations() is the bound
        // (every attempt failed), so the recorded effort signal must equal that bound.
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);
        VerifyOutcome verify = exhausted(5);

        recorder.record(verify);

        assertEquals(verify.iterations(), onlyOutcomeIn(sink.toString()).iterations(),
                "AC-16.1: recorded iterations must equal VerifyOutcome.iterations() (the bound, 5)");
        assertEquals(5, onlyOutcomeIn(sink.toString()).iterations(),
                "the EXHAUSTED bound (5) is recorded verbatim");
    }

    @Test
    @DisplayName("AC-16.2: NO_TEST_COMMAND records no outcome — no exit status to derive a signal from")
    void noTestCommandRecordsNothing() {
        // Oracle: AC-16.2 — success/failure is "derived from the final verification command's exit
        // status". With NO_TEST_COMMAND there is no verification command and no exit status, so there
        // is no signal to derive. The documented disposition is to record NOTHING (neither a
        // fabricated success nor a failure). Asserted both via the empty return and the empty log.
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);

        Optional<Event> appended = recorder.record(VerifyOutcome.noTestCommand());

        assertTrue(appended.isEmpty(),
                "AC-16.2: with no test command there is no exit status; record no signal");
        assertTrue(sink.toString().isBlank(),
                "AC-16.2: no OUTCOME event (or any event) is written when no verification ran");
    }

    @Test
    @DisplayName("AC-16.2: recordIfVerificationRan(empty) records no outcome — the unit concluded before verifying")
    void noVerificationRanRecordsNothing() {
        // Oracle: AC-16.2 — same reasoning as NO_TEST_COMMAND: when the unit of work concluded before
        // any verification ran (e.g. the change-turn surfaced an edge condition, so the brownfield
        // outcome carries no VerifyOutcome), there is no exit status to derive a signal from, so
        // nothing is recorded.
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);

        Optional<Event> appended = recorder.recordIfVerificationRan(Optional.empty());

        assertTrue(appended.isEmpty(), "AC-16.2: no verification ran, so no signal is recorded");
        assertTrue(sink.toString().isBlank(), "no event is written when no verification ran");
    }

    @Test
    @DisplayName("AC-16.2: recordIfVerificationRan(present VERIFIED) records the derived success signal")
    void recordIfVerificationRanDelegatesForPresentOutcome() {
        // Oracle: AC-16.2 — when a verification DID run, the signal is derived and recorded. The
        // VerifyOutcome-shaped entry point the run path uses must record the same success=true a
        // direct record(VERIFIED) does.
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);

        Optional<Event> appended = recorder.recordIfVerificationRan(Optional.of(verified(1)));

        assertTrue(appended.isPresent(), "a present (ran) verification records an outcome");
        assertTrue(onlyOutcomeIn(sink.toString()).success(),
                "AC-16.2: a VERIFIED verification that ran records success=true");
    }

    @Test
    @DisplayName("AC-16.1: the recorded outcome carries the supplied taskRef (per-task)")
    void recordsUnderTheSuppliedTaskRef() {
        // Oracle: AC-16.1 — the outcome is recorded PER TASK. The taskRef the recorder was bound to
        // must be the taskRef on the emitted OUTCOME payload (not a default or a derived value).
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);

        recorder.record(verified(1));

        assertEquals(TASK_REF, onlyOutcomeIn(sink.toString()).taskRef(),
                "AC-16.1: the outcome is recorded under the supplied taskRef (per task)");
    }

    @Test
    @DisplayName("CT-SCH (OUTCOME): the emitted OUTCOME line validates against event.schema.json $defs.outcome")
    void emittedOutcomeLineValidatesAgainstSchema() {
        // Oracle: CT-SCH (OUTCOME) — the OUTCOME event the producer emits must validate against the
        // formal event.schema.json (the $defs.outcome shape). This is the D2 guard: assert the REAL
        // on-disk shape against the spec schema, not merely that a field is present.
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);

        recorder.record(exhausted(2));

        String line = sink.toString().strip();
        Set<ValidationMessage> errors = validate(line);
        assertTrue(errors.isEmpty(),
                "CT-SCH (OUTCOME): the emitted OUTCOME line must validate against the schema; was: "
                        + errors + " line: " + line);
        assertTrue(line.contains("\"type\":\"OUTCOME\""),
                "the emitted line is an OUTCOME event");
    }

    @Test
    @DisplayName("ADR-0005: the appended event's timestamp is the injected clock's value, not Instant.now()")
    void usesTheInjectedBoundaryClock() {
        // Oracle: ADR-0005 — the event timestamp is boundary-captured via the injected clock, never
        // derived in-process. The emitted ts must equal the clock's value.
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);

        recorder.record(verified(1));

        assertTrue(sink.toString().contains("\"ts\":\"" + TS + "\""),
                "ADR-0005: the appended event must carry the injected clock's timestamp");
    }

    @Test
    @DisplayName("INV-1/INV-2: the OUTCOME event is appended through the log (assigned a seq) and flushed")
    void appendsThroughTheLogAssigningSeq() {
        // Oracle: INV-1 — the EventLog owns sequence numbering; an appended event gets the next seq.
        // The producer uses the same append + flush-per-event path everything else uses, so the
        // returned event carries the log-assigned seq (0 for the first append on a fresh log).
        StringWriter sink = new StringWriter();
        OutcomeRecorder recorder = new OutcomeRecorder(EventLog.over(sink, "t"), () -> TS, TASK_REF);

        Optional<Event> appended = recorder.record(verified(1));

        assertEquals(0, appended.orElseThrow().seq(),
                "INV-1: the first appended OUTCOME event gets seq 0 from the log");
    }

    @Test
    @DisplayName("AC-16.3: SessionStore.deriveMeta reads the recorded outcome back (the aggregation surface)")
    void recordedOutcomeIsReadByDeriveMeta(@TempDir Path root) {
        // Oracle: AC-16.3 — the outcome is persisted in a form aggregatable across sessions. The
        // aggregation surface is SessionStore.deriveMeta, which folds OutcomePayload.success() into
        // SessionMeta.outcomeSuccess(). Assert the event the recorder writes is the one the
        // aggregation path reads: record over a real session log, then derive meta and read it back.
        String repoKey = "github.com-example-widget";
        String sessionId = "2026-06-23T100000Z-abc";
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(repoKey, sessionId)) {
            OutcomeRecorder recorder = new OutcomeRecorder(log, () -> TS, TASK_REF);
            recorder.record(exhausted(4));
        }

        SessionMeta meta = store.deriveMeta(repoKey, sessionId, SessionStatus.COMPLETED);

        assertEquals(Boolean.FALSE, meta.outcomeSuccess(),
                "AC-16.3: deriveMeta must surface the recorded outcome's success flag (EXHAUSTED -> false)");
    }

    @Test
    @DisplayName("AC-16.3: a recorded VERIFIED outcome is read back as success=true via deriveMeta")
    void recordedVerifiedOutcomeAggregatesAsTrue(@TempDir Path root) {
        // Oracle: AC-16.3 — the success case also round-trips through the aggregation surface, so a
        // verified unit of work is countable as a success across sessions.
        String repoKey = "github.com-example-widget";
        String sessionId = "2026-06-23T100100Z-def";
        SessionStore store = new SessionStore(root);
        try (EventLog log = store.openLog(repoKey, sessionId)) {
            OutcomeRecorder recorder = new OutcomeRecorder(log, () -> TS, TASK_REF);
            recorder.record(verified(1));
        }

        SessionMeta meta = store.deriveMeta(repoKey, sessionId, SessionStatus.COMPLETED);

        assertEquals(Boolean.TRUE, meta.outcomeSuccess(),
                "AC-16.3: a recorded VERIFIED outcome aggregates as success=true");
    }

    @Test
    @DisplayName("the recorder requires its log, clock, and a non-blank taskRef")
    void constructorRejectsBadArgs() {
        // Defensive-construction (EJ Item 49 / fail-fast): a recorder cannot be built without its
        // collaborators or with a blank task reference (the schema requires a non-blank taskRef).
        assertThrows(NullPointerException.class,
                () -> new OutcomeRecorder(null, () -> TS, TASK_REF));
        assertThrows(NullPointerException.class,
                () -> new OutcomeRecorder(EventLog.over(new StringWriter(), "t"), null, TASK_REF));
        assertThrows(IllegalArgumentException.class,
                () -> new OutcomeRecorder(EventLog.over(new StringWriter(), "t"), () -> TS, " "));
    }

    @Test
    @DisplayName("record and recordIfVerificationRan reject null arguments")
    void methodsRejectNull() {
        OutcomeRecorder recorder =
                new OutcomeRecorder(EventLog.over(new StringWriter(), "t"), () -> TS, TASK_REF);
        assertThrows(NullPointerException.class, () -> recorder.record(null));
        assertThrows(NullPointerException.class, () -> recorder.recordIfVerificationRan(null));
    }
}
