package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GreenfieldPlaybook} — the per-phase greenfield system-prompt artifact that
 * steers the model through the {@link GreenfieldPhase} state machine (component C3, ADR-0012
 * greenfield side). The SUT is the playbook itself; there are no collaborators.
 *
 * <p><b>M0-lesson discipline.</b> The G3 phase-gating gate depends on the prompt actually
 * instructing each greenfield behaviour, not merely "a prompt exists". These tests assert the
 * prompt content carries the behaviour each cited AC pins (requirements-before-source, ask-don't-
 * write-source-early, no-source-write during pre-approval, approval-before-implementation), so a
 * refactor that silently dropped one would fail here.
 *
 * <p><b>Oracles trace to ADR-0012 and the cited ACs, never to the playbook's exact wording:</b>
 * <ul>
 *   <li><b>AC-1.1:</b> begin a requirements-gathering dialogue before creating or editing any
 *       source file.</li>
 *   <li><b>AC-1.3:</b> if implementation is requested while requirements are unconfirmed, ask for
 *       confirmation rather than writing source.</li>
 *   <li><b>AC-1.4:</b> while in the pre-approval dialogue, do not write source — the source-change
 *       tools are named only for the implementation phase.</li>
 *   <li><b>AC-2.3:</b> request developer approval before implementation begins.</li>
 * </ul>
 * The assertions match on the behaviour keywords the AC pins (requirements, source, approval,
 * implement), not a brittle full-string equality, so the wording can evolve while the contract
 * stays enforced.
 */
class GreenfieldPlaybookTest {

    private static String joined(GreenfieldPhase phase) {
        return String.join("\n", GreenfieldPlaybook.systemPrompt(phase)).toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("the playbook is a non-empty list of system-prompt blocks for every phase (the loop's `system` arg shape)")
    void systemPromptIsNonEmptyForEveryPhase() {
        // Oracle: dep API — AgentLoop/ModelClient accept the system prompt as List<String> blocks.
        // The playbook must produce a usable, non-empty, no-blank-block list for EVERY phase (an
        // empty/absent prompt would leave the model un-primed and the phase behaviour accidental).
        for (GreenfieldPhase phase : GreenfieldPhase.values()) {
            List<String> prompt = GreenfieldPlaybook.systemPrompt(phase);
            assertFalse(prompt.isEmpty(), "the greenfield playbook carries blocks for phase " + phase);
            for (String block : prompt) {
                assertFalse(block.isBlank(), "no prompt block may be blank (phase " + phase + ")");
            }
        }
    }

    @Test
    @DisplayName("AC-1.1: the playbook instructs gathering requirements before writing any source")
    void instructsRequirementsBeforeSource() {
        // Oracle: AC-1.1 — "the agent shall begin a requirements-gathering dialogue before creating
        // or editing any source file." The common block (present in every phase) must instruct
        // gathering/discussing requirements before any source is written.
        String prompt = joined(GreenfieldPhase.REQUIREMENTS);

        assertTrue(prompt.contains("requirements"),
                "AC-1.1: the playbook instructs gathering requirements");
        assertTrue(prompt.contains("before") && prompt.contains("source"),
                "AC-1.1: requirements are gathered BEFORE any source is written");
    }

    @Test
    @DisplayName("AC-1.3: the playbook instructs asking for confirmation rather than writing source if implementation is requested early")
    void instructsAskRatherThanWriteSourceEarly() {
        // Oracle: AC-1.3 — "If the developer requests implementation while requirements are
        // unconfirmed, then the agent shall ask for confirmation rather than writing source code."
        // The common block must instruct asking/confirming rather than jumping to writing source.
        String prompt = joined(GreenfieldPhase.REQUIREMENTS);

        assertTrue(prompt.contains("confirm") || prompt.contains("ask"),
                "AC-1.3: the playbook instructs asking/confirming when implementation is requested early");
        assertTrue(prompt.contains("source"),
                "AC-1.3: the instruction contrasts asking against writing source prematurely");
    }

    @Test
    @DisplayName("AC-2.3: the playbook instructs requesting developer approval before implementation begins")
    void instructsApprovalBeforeImplementation() {
        // Oracle: AC-2.3 — "When the design or task breakdown is presented, the agent shall request
        // developer approval before implementation begins." The common block must instruct
        // requesting approval before advancing into implementation.
        String prompt = joined(GreenfieldPhase.TASKS);

        assertTrue(prompt.contains("approv"),
                "AC-2.3: the playbook instructs requesting developer approval");
        assertTrue(prompt.contains("implement"),
                "AC-2.3: approval is requested before implementation begins");
    }

    @Test
    @DisplayName("AC-1.4: the pre-approval phase prompts do NOT name the source-change tools; the implement phase does")
    void preApprovalPhasesDoNotNameSourceChangeTools() {
        // Oracle: AC-1.4 — "While in the requirements dialogue, the agent shall not execute any
        // Class X operation against source files"; ADR-0012 extends this to the design/tasks
        // dialogue. The prompt must steer only toward tools that are actually offered: a pre-approval
        // phase prompt must NOT name the source-change tools (write_file/edit_file/run_command),
        // matching the driver's structural withholding of them; the implementation phase prompt
        // (post-approval, where source writing begins per AC-2.3) is the only one that introduces them.
        String implementTools = GreenfieldPlaybook.SOURCE_CHANGE_TOOLS.toLowerCase(Locale.ROOT);

        for (GreenfieldPhase phase : List.of(
                GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS)) {
            String prompt = joined(phase);
            assertFalse(prompt.contains("write_file"),
                    "AC-1.4: the " + phase + " phase prompt does not name write_file (no source write)");
            assertFalse(prompt.contains("edit_file"),
                    "AC-1.4: the " + phase + " phase prompt does not name edit_file (no source write)");
            assertFalse(prompt.contains("run_command"),
                    "AC-1.4: the " + phase + " phase prompt does not name run_command (no source write)");
        }

        String implement = joined(GreenfieldPhase.IMPLEMENT);
        assertTrue(implement.contains("write_file") && implement.contains("edit_file")
                        && implement.contains("run_command"),
                "AC-2.3: the implementation phase (post-approval) names the source-change tools ("
                        + implementTools + ") — the only phase where source writing begins");
    }

    @Test
    @DisplayName("the playbook names the greenfield mode so the model knows it is building from scratch")
    void namesGreenfieldMode() {
        // Oracle: 02-architecture § 1.2 (C3) "Mode is fixed for a session" + US-1/2/3 (a brand-new
        // project). The common block must establish the session is greenfield (building a new
        // project), which is what makes the requirements-before-source framing meaningful.
        String prompt = joined(GreenfieldPhase.REQUIREMENTS);

        assertTrue(prompt.contains("greenfield") || prompt.contains("brand-new")
                        || prompt.contains("from scratch"),
                "the playbook establishes the session builds a brand-new project (greenfield)");
    }

    @Test
    @DisplayName("ADR-0012: each phase's prompt names its own phase so the model knows the current job")
    void phaseBlockNamesItsOwnPhase() {
        // Oracle: ADR-0012 "Phases + artifacts" — the playbook has a phase-specific block framing the
        // current phase's job. Each phase's prompt must name its own phase (requirements/design/
        // tasks/implement) so the model picks up the right job for the turn.
        assertTrue(joined(GreenfieldPhase.REQUIREMENTS).contains("requirements"),
                "the requirements phase prompt names the requirements phase");
        assertTrue(joined(GreenfieldPhase.DESIGN).contains("design"),
                "the design phase prompt names the design phase");
        assertTrue(joined(GreenfieldPhase.TASKS).contains("task"),
                "the tasks phase prompt names the tasks phase");
        assertTrue(joined(GreenfieldPhase.IMPLEMENT).contains("implement"),
                "the implementation phase prompt names the implementation phase");
    }

    @Test
    @DisplayName("the explore/source-change tool name constants match the registered toolset (RD-4)")
    void namedToolsMatchRegisteredToolset() {
        // Oracle: dep API + RD-4 — the registered tools are read_file/grep/glob/list (Class R,
        // auto-approved in all modes) and write_file/edit_file/run_command (Class X, gated). The
        // playbook's tool-name constants must line up with the registry: the explore constant names
        // the Class-R tools available in every phase, the source-change constant names the Class-X
        // tools introduced only in implementation (AC-1.4).
        assertEquals("read_file, grep, glob, and list", GreenfieldPlaybook.EXPLORE_TOOLS,
                "RD-4: the explore tools are the Class-R read/search tools the registry offers");
        assertEquals("write_file, edit_file, and run_command", GreenfieldPlaybook.SOURCE_CHANGE_TOOLS,
                "RD-4/AC-1.4: the source-change tools are the Class-X tools the registry offers");
    }

    @Test
    @DisplayName("the playbook rejects a null phase")
    void rejectsNullPhase() {
        assertThrows(NullPointerException.class, () -> GreenfieldPlaybook.systemPrompt(null));
    }
}
