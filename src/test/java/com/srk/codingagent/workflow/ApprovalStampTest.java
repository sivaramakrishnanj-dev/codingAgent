package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ApprovalStamp}: the timestamped approval line recorded into a greenfield artifact when
 * the developer confirms a phase (AC-1.5).
 *
 * <p><b>Oracle:</b> AC-1.5 — "record the approval with a timestamp in the … artifact". The line must
 * carry the approval timestamp verbatim and name the artifact that was approved.
 */
class ApprovalStampTest {

    @Test
    @DisplayName("AC-1.5: the approval line carries the approval timestamp verbatim")
    void lineCarriesTheTimestamp() {
        // Oracle: AC-1.5 — the recorded approval includes a TIMESTAMP. The boundary clock's reading
        // must appear in the line verbatim, so a reader sees the moment of approval.
        String timestamp = "2026-06-23T09:00:00Z";

        String line = ApprovalStamp.line(GreenfieldArtifact.REQUIREMENTS, timestamp);

        assertTrue(line.contains(timestamp),
                "AC-1.5: the approval line records the timestamp verbatim; was: " + line);
    }

    @Test
    @DisplayName("AC-1.5: the approval line records that an approval was given for the named artifact")
    void lineRecordsAnApprovalForTheArtifact() {
        // Oracle: AC-1.5 — the agent RECORDS THE APPROVAL. The line must read as an approval record
        // and name which artifact was approved (the requirements artifact here), so the recorded
        // approval is unambiguous. Expected tokens trace to AC-1.5's "record the approval", not to the
        // stamp's exact phrasing.
        String line = ApprovalStamp.line(GreenfieldArtifact.REQUIREMENTS, "2026-06-23T09:00:00Z");

        assertTrue(line.toLowerCase(java.util.Locale.ROOT).contains("approved"),
                "AC-1.5: the line records that an approval was given; was: " + line);
        assertTrue(line.contains(GreenfieldArtifact.REQUIREMENTS.heading()),
                "AC-1.5: the line names the approved artifact (the requirements artifact); was: " + line);
    }

    @Test
    @DisplayName("the stamp rejects a null artifact, null timestamp, or blank timestamp")
    void rejectsBadInput() {
        assertThrows(NullPointerException.class,
                () -> ApprovalStamp.line(null, "2026-06-23T09:00:00Z"));
        assertThrows(NullPointerException.class,
                () -> ApprovalStamp.line(GreenfieldArtifact.DESIGN, null));
        assertThrows(IllegalArgumentException.class,
                () -> ApprovalStamp.line(GreenfieldArtifact.DESIGN, "   "));
    }
}
