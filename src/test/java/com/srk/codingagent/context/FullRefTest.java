package com.srk.codingagent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FullRef} — the {@code fullRef} pointer format (AC-19.2/19.3). The
 * format is the contract the disposer (writer) and retriever (reader) share, so format and
 * parse must be exact inverses, and a malformed reference must parse to empty (not crash).
 */
class FullRefTest {

    @Test
    @DisplayName("AC-19.2: forSeq formats the pointer as evt:<seq>")
    void forSeq_formatsAsEvtSeq() {
        // Oracle: the fullRef points back to the TOOL_RESULT event by its seq (AC-19.2). The
        // pinned shape is evt:<seq>.
        assertEquals("evt:0", FullRef.forSeq(0), "seq 0 formats as evt:0");
        assertEquals("evt:42", FullRef.forSeq(42), "seq 42 formats as evt:42");
    }

    @Test
    @DisplayName("AC-19.3: forSeq and seqOf round-trip (the writer and reader agree)")
    void formatAndParse_roundTrip() {
        // Oracle: AC-19.3 — retrieval must resolve the pointer the disposer wrote. Format then
        // parse must recover the original seq for the format/parse pair to be a usable contract.
        for (int seq : new int[] {0, 1, 7, 100, 99999}) {
            Optional<Integer> parsed = FullRef.seqOf(FullRef.forSeq(seq));
            assertTrue(parsed.isPresent(), "a well-formed pointer must parse back");
            assertEquals(seq, parsed.get(), "the parsed seq must equal the formatted seq");
        }
    }

    @Test
    @DisplayName("seqOf returns empty for a malformed reference (surfaced, not a crash)")
    void seqOf_malformed_isEmpty() {
        // Oracle: a malformed fullRef must be surfaced as not-found, never an exception (the
        // retriever treats it as a miss). Cover wrong scheme, non-numeric, negative, and empty.
        assertTrue(FullRef.seqOf("session:5").isEmpty(), "a non-evt scheme parses to empty");
        assertTrue(FullRef.seqOf("evt:abc").isEmpty(), "a non-numeric seq parses to empty");
        assertTrue(FullRef.seqOf("evt:-3").isEmpty(), "a negative seq parses to empty");
        assertTrue(FullRef.seqOf("evt:").isEmpty(), "an empty seq parses to empty");
        assertTrue(FullRef.seqOf("").isEmpty(), "an empty string parses to empty");
    }

    @Test
    @DisplayName("forSeq rejects a negative seq; seqOf rejects a null reference")
    void rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> FullRef.forSeq(-1),
                "a negative seq is not a valid event reference (seq >= 0, INV-1)");
        assertThrows(NullPointerException.class, () -> FullRef.seqOf(null),
                "a null reference must be rejected");
    }
}
