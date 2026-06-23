package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * Tests {@link ArtifactApprovalGate}: the greenfield approval gate that records the approval
 * timestamp into each phase's artifact (AC-1.5) and enforces task-breakdown traceability before
 * admitting the session into implementation (AC-2.5), layered over the developer's per-phase decision
 * (ADR-0012, AC-2.3/AC-1.4).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link ArtifactApprovalGate} over a real
 * {@link GreenfieldArtifactStore} rooted at a {@link TempDir} (the target repo). The scripted seams
 * are the external boundary, never the SUT: the {@link ArtifactApprovalGate.ApprovalDecision} is the
 * developer's scripted yes/no, and a fixed {@link Supplier} is the boundary clock (ADR-0005).
 *
 * <p><b>Oracles trace to the spec:</b> see each test's inline note.
 */
class ArtifactApprovalGateTest {

    private static final String TS = "2026-06-23T09:00:00Z";

    private static Supplier<String> fixedClock() {
        return () -> TS;
    }

    private static ArtifactApprovalGate.ApprovalDecision approveAll() {
        return completedPhase -> true;
    }

    private static ArtifactApprovalGate.ApprovalDecision declineAll() {
        return completedPhase -> false;
    }

    // --- AC-1.5 : an approved phase records the approval timestamp into that phase's artifact ------

    @Test
    @DisplayName("AC-1.5: confirming the requirements phase records the approval timestamp in the requirements artifact")
    void approvingRequirementsRecordsTimestampInArtifact(@TempDir Path targetRepo) {
        // Oracle: AC-1.5 — "When the developer confirms the requirements, the agent shall record the
        // approval with a timestamp in the requirements artifact." With the requirements artifact
        // authored and the decision approving, the gate must append the approval timestamp to that
        // artifact and approve the advance. Assert the timestamp now appears in the requirements
        // artifact's content (the oracle is the timestamp the clock supplied, per AC-1.5).
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write(GreenfieldArtifact.REQUIREMENTS.relativePath(), "# Requirements\n\nUS-1...\n");
        ArtifactApprovalGate gate = new ArtifactApprovalGate(approveAll(), store, fixedClock());

        boolean advanced = gate.approveAdvance(GreenfieldPhase.REQUIREMENTS);

        assertTrue(advanced, "AC-2.3: a confirmed requirements phase approves the advance");
        String content = store.read(GreenfieldArtifact.REQUIREMENTS.relativePath()).orElseThrow();
        assertTrue(content.contains(TS),
                "AC-1.5: the approval timestamp is recorded in the requirements artifact; was: " + content);
    }

    @Test
    @DisplayName("AC-1.5 (ADR-0012 per-phase): confirming the design phase records the timestamp in the design artifact")
    void approvingDesignRecordsTimestampInDesignArtifact(@TempDir Path targetRepo) {
        // Oracle: ADR-0012 — "each approval is recorded (timestamped) in the artifact (AC-1.5)" —
        // generalizes AC-1.5 to every phase. Confirming the design phase must record the timestamp in
        // the DESIGN artifact (not the requirements one). Assert the design artifact now carries the
        // timestamp and the requirements artifact is untouched by this approval.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write(GreenfieldArtifact.DESIGN.relativePath(), "# Design\n");
        ArtifactApprovalGate gate = new ArtifactApprovalGate(approveAll(), store, fixedClock());

        gate.approveAdvance(GreenfieldPhase.DESIGN);

        assertTrue(store.read(GreenfieldArtifact.DESIGN.relativePath()).orElseThrow().contains(TS),
                "ADR-0012/AC-1.5: the design phase's approval is timestamped in the design artifact");
        assertFalse(store.read(GreenfieldArtifact.REQUIREMENTS.relativePath()).isPresent(),
                "the design approval does not write the requirements artifact");
    }

    // --- AC-2.3 / AC-1.4 : a declined phase records nothing and does not advance ------------------

    @Test
    @DisplayName("AC-2.3/AC-1.4: a declined phase records no approval and does not advance")
    void declinedPhaseRecordsNothingAndDoesNotAdvance(@TempDir Path targetRepo) {
        // Oracle: AC-2.3 — the agent does not advance without explicit approval; AC-1.4 — no source is
        // written while unapproved. A declined decision must NOT stamp an approval (there was none)
        // and must return false so the driver stops at the gate. Assert no approval timestamp was
        // added to the (authored) requirements artifact and the advance is refused.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write(GreenfieldArtifact.REQUIREMENTS.relativePath(), "# Requirements\n");
        ArtifactApprovalGate gate = new ArtifactApprovalGate(declineAll(), store, fixedClock());

        boolean advanced = gate.approveAdvance(GreenfieldPhase.REQUIREMENTS);

        assertFalse(advanced, "AC-2.3: a declined phase does not advance");
        assertFalse(store.read(GreenfieldArtifact.REQUIREMENTS.relativePath()).orElseThrow().contains(TS),
                "AC-1.5/AC-2.3: no approval is recorded when the developer declines");
    }

    // --- AC-2.5 : the tasks approval is refused when the breakdown is not traceable ---------------

    @Test
    @DisplayName("AC-2.5: confirming the tasks phase is refused when a task does not trace to a requirement")
    void tasksApprovalRefusedWhenBreakdownNotTraceable(@TempDir Path targetRepo) {
        // Oracle: AC-2.5 — "the agent shall ensure every task in the breakdown traces to at least one
        // stated requirement." The tasks gate admits the session into implementation (AC-2.3). With a
        // task breakdown containing an untraced task, confirming the tasks phase must be REFUSED (the
        // guarantee is enforced, not advisory) — the gate returns false and records no approval.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write(GreenfieldArtifact.TASKS.relativePath(),
                "# Tasks\n- T-1 build (AC-1.2)\n- T-2 untraced task\n");
        ArtifactApprovalGate gate = new ArtifactApprovalGate(approveAll(), store, fixedClock());

        boolean advanced = gate.approveAdvance(GreenfieldPhase.TASKS);

        assertFalse(advanced,
                "AC-2.5: an untraceable task breakdown cannot be approved into implementation");
        assertFalse(store.read(GreenfieldArtifact.TASKS.relativePath()).orElseThrow().contains(TS),
                "AC-2.5: no approval timestamp is recorded for an untraceable breakdown");
    }

    @Test
    @DisplayName("AC-2.5/AC-1.5: confirming a traceable tasks breakdown records the approval timestamp and advances")
    void tasksApprovalRecordedWhenBreakdownTraceable(@TempDir Path targetRepo) {
        // Oracle: AC-2.5 (every task traces) + AC-1.5 (record timestamped approval). A traceable
        // breakdown — every task references a requirement — must be approvable: the gate records the
        // timestamp in the tasks artifact and approves the advance into implementation (AC-2.3).
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write(GreenfieldArtifact.TASKS.relativePath(),
                "# Tasks\n- T-1 build (AC-1.2)\n- T-2 wire (US-3, AC-2.1)\n");
        ArtifactApprovalGate gate = new ArtifactApprovalGate(approveAll(), store, fixedClock());

        boolean advanced = gate.approveAdvance(GreenfieldPhase.TASKS);

        assertTrue(advanced, "AC-2.5/AC-2.3: a traceable breakdown is approvable into implementation");
        assertTrue(store.read(GreenfieldArtifact.TASKS.relativePath()).orElseThrow().contains(TS),
                "AC-1.5: the tasks approval is timestamped in the tasks artifact");
    }

    @Test
    @DisplayName("AC-2.5: confirming the tasks phase is refused when no task-breakdown artifact was authored")
    void tasksApprovalRefusedWhenNoBreakdownAuthored(@TempDir Path targetRepo) {
        // Oracle: AC-2.5 — the agent ensures every task traces. If no task-breakdown artifact exists,
        // there is no breakdown to verify, so the tasks phase cannot be approved into implementation.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        ArtifactApprovalGate gate = new ArtifactApprovalGate(approveAll(), store, fixedClock());

        assertFalse(gate.approveAdvance(GreenfieldPhase.TASKS),
                "AC-2.5: with no task-breakdown artifact, the tasks phase cannot be approved");
    }

    @Test
    @DisplayName("the gate rejects null collaborators and a null completed phase")
    void rejectsNulls(@TempDir Path targetRepo) {
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        assertThrows(NullPointerException.class,
                () -> new ArtifactApprovalGate(null, store, fixedClock()));
        assertThrows(NullPointerException.class,
                () -> new ArtifactApprovalGate(approveAll(), null, fixedClock()));
        assertThrows(NullPointerException.class,
                () -> new ArtifactApprovalGate(approveAll(), store, null));
        ArtifactApprovalGate gate = new ArtifactApprovalGate(approveAll(), store, fixedClock());
        assertThrows(NullPointerException.class, () -> gate.approveAdvance(null));
    }
}
