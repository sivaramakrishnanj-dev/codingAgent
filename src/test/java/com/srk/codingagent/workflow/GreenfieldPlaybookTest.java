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
    @DisplayName("RD-7/AC-1.2/AC-2.1: each pre-approval phase prompt directs writing the deliverable to its artifact via write_artifact")
    void preApprovalPhasesDirectArtifactWrite() {
        // Oracle: RD-7 — "greenfield persists requirements, design, and task-breakdown as markdown in
        // the target project"; AC-1.2 — "persist the agreed requirements as a markdown artifact";
        // AC-2.1 — "produce a design artifact and a task-breakdown artifact as markdown". The
        // deliverable of each pre-approval phase IS the persisted artifact (the D7 regression: the
        // prompt previously never told the model to write it, so write_artifact was never called and
        // the artifacts held only the approval stamp). Each pre-approval phase prompt must name the
        // write_artifact tool AND that phase's artifact path, so the model is steered to persist the
        // deliverable. The artifact paths trace to GreenfieldArtifact (the spec'd per-phase artifacts),
        // not to the playbook's wording.
        for (GreenfieldPhase phase : List.of(
                GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS)) {
            String prompt = joined(phase);
            assertTrue(prompt.contains(GreenfieldPlaybook.ARTIFACT_WRITE_TOOL),
                    "RD-7: the " + phase + " phase prompt names the artifact-write tool ("
                            + GreenfieldPlaybook.ARTIFACT_WRITE_TOOL + ") so the deliverable is persisted");
            String artifactPath = GreenfieldArtifact.forPhase(phase).orElseThrow()
                    .relativePath().toLowerCase(Locale.ROOT);
            assertTrue(prompt.contains(artifactPath),
                    "AC-1.2/AC-2.1: the " + phase + " phase prompt names its artifact path ("
                            + artifactPath + ") so the deliverable is written to the right file");
        }
    }

    @Test
    @DisplayName("RD-7: the artifact-write tool constant is write_artifact and the pre-approval prompts name it without naming a source-change tool (AC-1.4)")
    void artifactWriteToolConstantIsRegisteredAndSafe() {
        // Oracle: dep API (WriteArtifactTool.NAME = "write_artifact") + AC-1.4 — the one Class-X write
        // offered in the pre-approval phases is the path-confined write_artifact, while the
        // source-change tools (write_file/edit_file/run_command) stay withheld. The artifact-write
        // constant must equal the registered tool name, and naming it must NOT introduce a
        // source-change tool name into a pre-approval prompt (the AC-1.4 property
        // preApprovalPhasesDoNotNameSourceChangeTools also guards). Asserting the constant value here
        // (not in the equality-pinned namedToolsMatchRegisteredToolset) keeps that test's existing
        // EXPLORE_TOOLS/SOURCE_CHANGE_TOOLS equalities valid.
        assertEquals("write_artifact", GreenfieldPlaybook.ARTIFACT_WRITE_TOOL,
                "RD-7: the artifact-write constant matches the registered write_artifact tool name");
        assertFalse(GreenfieldPlaybook.ARTIFACT_WRITE_TOOL.contains("write_file")
                        || GreenfieldPlaybook.ARTIFACT_WRITE_TOOL.contains("edit_file")
                        || GreenfieldPlaybook.ARTIFACT_WRITE_TOOL.contains("run_command"),
                "AC-1.4: the artifact-write tool is not one of the withheld source-change tools");
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

    // --- DCR-5 : the prompt emits the strict traceability gate's vocabulary ----------------------
    // AC-2.2/AC-2.5/ADR-0012 (DCR-5): the burden of conformance to the strict TaskTraceability gate
    // sits on this prompt, not on a relaxed gate. The requirements phase must direct authoring the
    // gate-recognizable requirement-symbol shapes (AC-<n>.<m> / US-<n> / NFR-<NAME>); the tasks phase
    // must direct the T-<n>/T-<n>.<m> stable-id form (hyphen mandatory) AND citing a requirement
    // symbol on each task line. (Note: joined(phase) lowercases the prompt, so the asserted symbol
    // shapes are lowercased.)

    @Test
    @DisplayName("AC-2.5 (DCR-5): the requirements phase prompt directs authoring AC-<n>.<m>/US-<n>/NFR-<NAME> requirement symbols")
    void requirementsPhaseEmitsGateRequirementVocabulary() {
        // Oracle: AC-2.5 (DCR-5) — "the requirements phase authors gate-recognizable
        // AC-<n>.<m>/US-<n>/NFR-<NAME> symbols". The requirements-phase prompt must name each of the
        // three gate-recognizable requirement-symbol shapes so the model authors them as the
        // traceability catalog its tasks will cite. The shapes (US-<n>, AC-<n>.<m>, NFR-<NAME>) trace
        // to AC-2.5/ADR-0012's fixed requirement-symbol vocabulary, not to the prompt's wording.
        String prompt = joined(GreenfieldPhase.REQUIREMENTS);

        assertTrue(prompt.contains("us-<n>"),
                "AC-2.5 (DCR-5): the requirements prompt directs authoring user stories as US-<n>");
        assertTrue(prompt.contains("ac-<n>.<m>"),
                "AC-2.5 (DCR-5): the requirements prompt directs authoring acceptance criteria as AC-<n>.<m>");
        assertTrue(prompt.contains("nfr-<name>"),
                "AC-2.5 (DCR-5): the requirements prompt directs authoring NFRs as NFR-<NAME>");
    }

    @Test
    @DisplayName("AC-2.2 (DCR-5): the tasks phase prompt directs the T-<n>/T-<n>.<m> stable-id form (hyphen mandatory)")
    void tasksPhaseEmitsGateTaskIdForm() {
        // Oracle: AC-2.2 (DCR-5) — "a stable identifier of the form T-<n> or T-<n>.<m> (the hyphen is
        // mandatory). ... the greenfield playbook prompt ... emits this id form". The tasks-phase prompt
        // must name the T-<n>/T-<n>.<m> id form so the model authors hyphen-bearing ids the strict gate
        // recognizes. The id form traces to AC-2.2, not to the prompt's wording.
        String prompt = joined(GreenfieldPhase.TASKS);

        assertTrue(prompt.contains("t-<n>"),
                "AC-2.2 (DCR-5): the tasks prompt directs giving each task a T-<n> stable id");
        assertTrue(prompt.contains("t-<n>.<m>"),
                "AC-2.2 (DCR-5): the tasks prompt names the T-<n>.<m> sub-task id form");
        assertTrue(prompt.contains("hyphen"),
                "AC-2.2 (DCR-5): the tasks prompt states the hyphen is mandatory (T-<n>, not T<n>)");
    }

    @Test
    @DisplayName("AC-2.5 (DCR-5): the tasks phase prompt directs each task to cite a requirement symbol from the requirements phase")
    void tasksPhaseDirectsCitingRequirementSymbol() {
        // Oracle: AC-2.5 (DCR-5) — "the tasks phase emits T-<n>/T-<n>.<m> task ids each citing >= 1
        // such [requirement] symbol". The tasks-phase prompt must direct citing a requirement symbol
        // on each task line (an AC-<n>.<m> / US-<n> / NFR-<NAME>) so the model-authored breakdown
        // traces to a stated requirement and self-conforms to the strict gate. The instruction traces
        // to AC-2.5/ADR-0012's traceability requirement, not to the prompt's exact wording.
        String prompt = joined(GreenfieldPhase.TASKS);

        assertTrue(prompt.contains("cite") || prompt.contains("trace"),
                "AC-2.5 (DCR-5): the tasks prompt directs citing/tracing a requirement on each task line");
        assertTrue(prompt.contains("requirement"),
                "AC-2.5 (DCR-5): the cited target is a requirement (the requirements-phase symbol catalog)");
        assertTrue(prompt.contains("ac-<n>.<m>") || prompt.contains("us-<n>") || prompt.contains("nfr-<name>"),
                "AC-2.5 (DCR-5): the tasks prompt names the requirement-symbol vocabulary tasks must cite");
    }

    // --- DCR-6 : the tasks prompt forces a single-line task row and forbids the three miscounting
    // shapes -------------------------------------------------------------------------------------
    // AC-2.2/AC-2.5/ADR-0012 (DCR-6): because TaskTraceability enforces the strict same-line-ref rule
    // (no block scan), the breakdown self-conforms only when each task is one single-line row carrying
    // its requirement symbol inline. The tasks prompt must (1) name the single-line task-row format,
    // and forbid (2) range headings ("T-3 through T-8"), (3) multi-line **Refs:** blocks, (4) the
    // arrow/sequencing-diagram-as-task-list ("T-1 -> T-2"). (joined(phase) lowercases the prompt.)

    @Test
    @DisplayName("AC-2.2/AC-2.5 (DCR-6): the tasks prompt names the single canonical single-line task-row format")
    void tasksPhaseNamesSingleLineRowFormat() {
        // Oracle: AC-2.2/AC-2.5 (DCR-6) — "force a single canonical single-line task row per task". The
        // tasks-phase prompt must direct emitting exactly one task per line, each carrying its
        // requirement symbol inline on the same line, so the strict line-by-line gate counts the
        // breakdown as the author intends. The instruction traces to DCR-6's single-line-row contract,
        // not to the prompt's exact wording.
        String prompt = joined(GreenfieldPhase.TASKS);

        assertTrue(prompt.contains("one task per line") || prompt.contains("single-line task row"),
                "DCR-6: the tasks prompt names the single canonical single-line task-row format");
        assertTrue(prompt.contains("own line"),
                "DCR-6: the prompt directs each task onto its own line with its requirement symbol inline");
    }

    @Test
    @DisplayName("AC-2.2/AC-2.5 (DCR-6): the tasks prompt forbids range headings (T-3 through T-8)")
    void tasksPhaseForbidsRangeHeadings() {
        // Oracle: AC-2.2/AC-2.5 (DCR-6) — "forbid range headings". A range heading stands in for
        // several tasks the strict gate would have to expand; the prompt must steer the model away from
        // authoring one. The prompt must forbid the "through" range form and direct emitting each task
        // individually. The forbiddance traces to DCR-6, not to the prompt's wording.
        String prompt = joined(GreenfieldPhase.TASKS);

        assertTrue(prompt.contains("through"),
                "DCR-6: the tasks prompt addresses the range-heading 'through' form so it can forbid it");
        assertTrue(prompt.contains("never") || prompt.contains("do not"),
                "DCR-6: the prompt forbids (never / do not) using a range heading for several tasks");
    }

    @Test
    @DisplayName("AC-2.2/AC-2.5 (DCR-6): the tasks prompt forbids a multi-line **Refs:** block (the ref must be inline on the task's own line)")
    void tasksPhaseForbidsMultiLineRefsBlock() {
        // Oracle: AC-2.2/AC-2.5 (DCR-6) — "forbid multi-line **Refs:** blocks". Because the gate checks
        // refs line by line (no block scan), the requirement symbols must be inline on the task's own
        // line, not on a following Refs: line. The prompt must forbid the separate-Refs-line shape and
        // require the inline ref. The constraint traces to DCR-6's same-line-ref guarantee.
        String prompt = joined(GreenfieldPhase.TASKS);

        assertTrue(prompt.contains("refs:"),
                "DCR-6: the tasks prompt addresses the 'Refs:' line shape so it can forbid it");
        assertTrue(prompt.contains("inline") && prompt.contains("own line"),
                "DCR-6: the prompt requires the requirement symbols inline on the task's own line");
    }

    @Test
    @DisplayName("AC-2.2/AC-2.5 (DCR-6): the tasks prompt forbids using an arrow/sequencing diagram (T-1 -> T-2) as the task list")
    void tasksPhaseForbidsArrowDiagramTaskList() {
        // Oracle: AC-2.2/AC-2.5 (DCR-6) — "forbid arrow-diagram-as-task-list". A sequencing diagram line
        // (T-1 -> T-2) is not a task row; the prompt must steer the model away from using a diagram as
        // the task list. The prompt must name the arrow/sequencing-diagram form and forbid it as the
        // task list. The forbiddance traces to DCR-6, not to the prompt's wording.
        String prompt = joined(GreenfieldPhase.TASKS);

        assertTrue(prompt.contains("arrow") || prompt.contains("sequencing") || prompt.contains("->"),
                "DCR-6: the tasks prompt names the arrow/sequencing-diagram form so it can forbid it");
        assertTrue(prompt.contains("diagram"),
                "DCR-6: the prompt forbids using a diagram line as a task row / the task list");
    }
}
