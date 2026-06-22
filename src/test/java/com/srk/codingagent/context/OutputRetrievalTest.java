package com.srk.codingagent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.OutcomePayload;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.persistence.ToolResultPayload;
import com.srk.codingagent.persistence.ToolResultStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link OutputRetrieval} — full-output retrieval (component C6, AC-19.3).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link OutputRetrieval} over a real
 * {@link SessionStore} rooted at a temp directory, reading a real session event log written by
 * a real {@link EventLog}. Nothing the retriever's logic owns is mocked; the disk is real
 * (a @TempDir), which is the genuine external dependency.
 *
 * <p><b>Oracles trace to the spec:</b>
 * <ul>
 *   <li><b>AC-19.3 (round-trip):</b> the full output persisted to the log under a fullRef is
 *       recoverable, byte-for-byte, from the log via that fullRef — "retrieve rather than
 *       re-run".</li>
 *   <li><b>AC-19.2 (the log is the full store):</b> what is persisted is the FULL output, not
 *       a reduced copy.</li>
 *   <li><b>not-found is surfaced as empty</b> (a malformed / unresolved pointer is not a
 *       crash).</li>
 * </ul>
 */
class OutputRetrievalTest {

    private static final String REPO_KEY = "repo-abc";
    private static final String SESSION_ID = "one-shot";
    private static final String TS = "2026-06-17T09:00:00Z";

    /**
     * Appends a TOOL_RESULT event carrying the full output to a real session log and returns
     * the appended event's assigned seq — the seq the fullRef keys on (as the agent loop does).
     */
    private static int persistFullToolResult(SessionStore store, String toolUseId, Object fullOutput) {
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            Event appended = log.append(new Event(
                    log.nextSeq(), TS, ToolResultPayload.of(toolUseId, ToolResultStatus.OK, fullOutput)));
            return appended.seq();
        }
    }

    @Test
    @DisplayName("AC-19.3 round-trip: a persisted full output is retrievable byte-for-byte via its fullRef")
    void retrieve_roundTripsFullOutput(@TempDir Path dir) {
        // Oracle: AC-19.3 — "be able to retrieve the full output from the log rather than
        // re-running the command". Persist a large full output as a TOOL_RESULT, derive its
        // fullRef from the appended seq (as the disposer does), and assert retrieval returns the
        // SAME full output — the round-trip the live G1 cycle depends on.
        SessionStore store = new SessionStore(dir);
        OutputRetrieval retrieval = new OutputRetrieval(store);
        String fullOutput = "BEGIN " + "x".repeat(40_000) + " END BUILD FAILED line 42";
        int seq = persistFullToolResult(store, "tu_1", fullOutput);
        String fullRef = FullRef.forSeq(seq);

        Optional<Object> retrieved = retrieval.retrieve(REPO_KEY, SESSION_ID, fullRef);

        assertTrue(retrieved.isPresent(), "AC-19.3: the full output must be retrievable from the log");
        assertEquals(fullOutput, retrieved.get(),
                "AC-19.3: the retrieved output must equal the persisted full output, byte-for-byte");
    }

    @Test
    @DisplayName("AC-19.2: the log stores the FULL output, not a reduced copy (no truncation marker)")
    void retrieve_returnsFullNotReduced(@TempDir Path dir) {
        // Oracle: AC-19.2 — "persist the FULL output to the session log so it remains
        // retrievable". The retrieved value must be the complete output, never a head+tail
        // reduction; it must not carry the disposer's truncation marker.
        SessionStore store = new SessionStore(dir);
        OutputRetrieval retrieval = new OutputRetrieval(store);
        String fullOutput = "line1\n".repeat(10_000);
        int seq = persistFullToolResult(store, "tu_1", fullOutput);

        Object retrieved = retrieval.retrieve(REPO_KEY, SESSION_ID, FullRef.forSeq(seq)).orElseThrow();

        assertEquals(fullOutput.getBytes(StandardCharsets.UTF_8).length,
                ((String) retrieved).getBytes(StandardCharsets.UTF_8).length,
                "AC-19.2: the log holds the full output, identical in length to the original");
        assertTrue(!((String) retrieved).contains("truncated"),
                "AC-19.2: the persisted full output carries no truncation marker (it is the full copy)");
    }

    @Test
    @DisplayName("retrieval picks the TOOL_RESULT at the referenced seq among multiple events")
    void retrieve_selectsCorrectSeq(@TempDir Path dir) {
        // Oracle: AC-19.3 — the fullRef identifies a SPECIFIC event by seq. With several
        // TOOL_RESULTs in the log, retrieval must return the one at the referenced seq, not the
        // first or last.
        SessionStore store = new SessionStore(dir);
        OutputRetrieval retrieval = new OutputRetrieval(store);
        int seqA;
        int seqB;
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            seqA = log.append(new Event(log.nextSeq(), TS,
                    ToolResultPayload.of("tu_a", ToolResultStatus.OK, "output A"))).seq();
            seqB = log.append(new Event(log.nextSeq(), TS,
                    ToolResultPayload.of("tu_b", ToolResultStatus.OK, "output B"))).seq();
        }

        assertEquals("output A", retrieval.retrieve(REPO_KEY, SESSION_ID, FullRef.forSeq(seqA)).orElseThrow(),
                "the fullRef at seqA must resolve to output A");
        assertEquals("output B", retrieval.retrieve(REPO_KEY, SESSION_ID, FullRef.forSeq(seqB)).orElseThrow(),
                "the fullRef at seqB must resolve to output B");
    }

    @Test
    @DisplayName("a fullRef past the end of the log resolves to empty (not a crash)")
    void retrieve_unresolvedSeq_isEmpty(@TempDir Path dir) {
        // Oracle: a pointer that does not resolve must be surfaced as empty, not throw. A seq
        // beyond the events present has no matching event.
        SessionStore store = new SessionStore(dir);
        OutputRetrieval retrieval = new OutputRetrieval(store);
        persistFullToolResult(store, "tu_1", "only event");

        assertTrue(retrieval.retrieve(REPO_KEY, SESSION_ID, FullRef.forSeq(999)).isEmpty(),
                "a fullRef past the end of the log must resolve to empty");
    }

    @Test
    @DisplayName("a fullRef resolving to a non-TOOL_RESULT event is empty (only tool output is retrievable)")
    void retrieve_nonToolResultSeq_isEmpty(@TempDir Path dir) {
        // Oracle: AC-19.3 retrieves a tool/command OUTPUT; a fullRef that lands on a different
        // event kind (e.g. an OUTCOME) is not a tool result and must resolve to empty.
        SessionStore store = new SessionStore(dir);
        OutputRetrieval retrieval = new OutputRetrieval(store);
        int seq;
        try (EventLog log = store.openLog(REPO_KEY, SESSION_ID)) {
            seq = log.append(new Event(log.nextSeq(), TS, new OutcomePayload("t", true, 1))).seq();
        }

        assertTrue(retrieval.retrieve(REPO_KEY, SESSION_ID, FullRef.forSeq(seq)).isEmpty(),
                "a fullRef pointing at a non-TOOL_RESULT event must resolve to empty");
    }

    @Test
    @DisplayName("a malformed fullRef resolves to empty without reading the log")
    void retrieve_malformedRef_isEmpty(@TempDir Path dir) {
        // Oracle: a malformed pointer is a miss, surfaced as empty. (No session log exists; a
        // malformed ref must not even attempt a read that could fail.)
        SessionStore store = new SessionStore(dir);
        OutputRetrieval retrieval = new OutputRetrieval(store);

        assertTrue(retrieval.retrieve(REPO_KEY, SESSION_ID, "not-an-evt-ref").isEmpty(),
                "a malformed fullRef must resolve to empty");
    }

    @Test
    @DisplayName("retrieval rejects a null fullRef and a blank session id")
    void retrieve_rejectsBadArgs(@TempDir Path dir) {
        OutputRetrieval retrieval = new OutputRetrieval(new SessionStore(dir));

        assertThrows(NullPointerException.class,
                () -> retrieval.retrieve(REPO_KEY, SESSION_ID, null),
                "a null fullRef must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> retrieval.retrieve(REPO_KEY, "  ", FullRef.forSeq(0)),
                "a blank session id must be rejected (the store validates it)");
    }

    @Test
    @DisplayName("the retriever requires a non-null session store")
    void constructor_rejectsNullStore() {
        assertThrows(NullPointerException.class, () -> new OutputRetrieval(null),
                "the retriever requires a session store to read through");
    }
}
