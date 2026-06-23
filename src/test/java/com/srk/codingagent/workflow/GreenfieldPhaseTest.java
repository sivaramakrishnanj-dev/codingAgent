package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GreenfieldPhase} — the greenfield phase state machine (component C3,
 * ADR-0012 greenfield side, US-1/US-2/US-3).
 *
 * <p><b>Oracles trace to ADR-0012 and the cited ACs, never to the enum's code.</b> ADR-0012 pins
 * the phase order (requirements&rarr;design&rarr;tasks&rarr;implement) and the per-phase approval
 * gates; AC-1.1/AC-1.4 pin that the requirements/design dialogue precedes any source write; AC-2.3
 * pins that implementation begins only after approval. Each test's expected value is read from
 * those spec symbols.
 */
class GreenfieldPhaseTest {

    @Test
    @DisplayName("ADR-0012: the initial phase is requirements (the dialogue begins before source, AC-1.1)")
    void initialPhaseIsRequirements() {
        // Oracle: AC-1.1 — "the agent shall begin a requirements-gathering dialogue before creating
        // or editing any source file". The machine's first phase is requirements.
        assertEquals(GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.initial(),
                "AC-1.1: a greenfield session begins in the requirements phase");
    }

    @Test
    @DisplayName("ADR-0012: the phases advance requirements->design->tasks->implement in order")
    void phasesAdvanceInTheAdrOrder() {
        // Oracle: ADR-0012 "Phases + artifacts" — requirements -> design -> formal/tasks ->
        // implementation. Following next() from the initial phase must visit exactly that order and
        // terminate at implement.
        List<GreenfieldPhase> visited = new ArrayList<>();
        GreenfieldPhase phase = GreenfieldPhase.initial();
        visited.add(phase);
        while (phase.next().isPresent()) {
            phase = phase.next().orElseThrow();
            visited.add(phase);
        }

        assertEquals(
                List.of(GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN,
                        GreenfieldPhase.TASKS, GreenfieldPhase.IMPLEMENT),
                visited,
                "ADR-0012: the phase order is requirements->design->tasks->implement");
    }

    @Test
    @DisplayName("ADR-0012: implement is the terminal phase (no further phase follows)")
    void implementIsTerminal() {
        // Oracle: ADR-0012 — implementation is the last phase; reaching it means every approval
        // gate was passed. The terminal phase has no successor.
        assertTrue(GreenfieldPhase.IMPLEMENT.isTerminal(),
                "ADR-0012: implement is the terminal phase");
        assertTrue(GreenfieldPhase.IMPLEMENT.next().isEmpty(),
                "the terminal phase has no next phase");
    }

    @Test
    @DisplayName("ADR-0012/AC-2.3: only requirements/design/tasks are non-terminal (gated) phases")
    void nonTerminalPhasesAreGated() {
        // Oracle: ADR-0012 "the agent does not advance a phase without explicit developer approval";
        // AC-2.3 — implementation begins only after the design + task breakdown are approved. Every
        // phase except the terminal implement phase is non-terminal (has a gate to pass).
        assertFalse(GreenfieldPhase.REQUIREMENTS.isTerminal());
        assertFalse(GreenfieldPhase.DESIGN.isTerminal());
        assertFalse(GreenfieldPhase.TASKS.isTerminal());
    }

    @Test
    @DisplayName("AC-1.4: requirements/design/tasks are pre-approval phases (no source write); implement is not")
    void preApprovalPhasesAreTheDialoguePhases() {
        // Oracle: AC-1.4 — "While in the requirements dialogue, the agent shall not execute any
        // Class X operation against source files"; ADR-0012 extends this to the design dialogue
        // ("the agent does not write source — only design markdown — until the breakdown is
        // approved"). The requirements, design, and tasks phases are pre-approval (no source write);
        // implementation — reached only after approval (AC-2.3) — is not.
        assertTrue(GreenfieldPhase.REQUIREMENTS.isPreApproval(),
                "AC-1.1/AC-1.4: requirements is a pre-approval (no-source-write) phase");
        assertTrue(GreenfieldPhase.DESIGN.isPreApproval(),
                "ADR-0012: design is a pre-approval (no-source-write) phase");
        assertTrue(GreenfieldPhase.TASKS.isPreApproval(),
                "ADR-0012: tasks is the last pre-approval phase before implementation");
        assertFalse(GreenfieldPhase.IMPLEMENT.isPreApproval(),
                "AC-2.3: implementation is the post-approval phase where source writing begins");
    }

    @Test
    @DisplayName("AC-2.3: the gate leaving tasks advances into implementation (where source begins)")
    void tasksAdvancesIntoImplementation() {
        // Oracle: AC-2.3 — "When the design or task breakdown is presented, the agent shall request
        // developer approval before implementation begins". The advance out of the tasks phase is
        // the gate into the implementation phase, the boundary the approval guards.
        assertEquals(GreenfieldPhase.IMPLEMENT, GreenfieldPhase.TASKS.next().orElseThrow(),
                "AC-2.3: approving the tasks phase advances into implementation");
    }
}
