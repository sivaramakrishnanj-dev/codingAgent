package com.srk.codingagent.workflow;

import java.util.List;
import java.util.Objects;

/**
 * The greenfield "discuss &rarr; design &rarr; tasks &rarr; implement" playbook (component C3,
 * ADR-0012 greenfield side): the per-phase system-prompt artifact that steers the model through
 * the {@link GreenfieldPhase} state machine. Where the {@link BrownfieldPlaybook} is a single
 * static prompt for an emergent arc, greenfield is a genuine phase state machine (ADR-0012), so
 * the playbook has a <em>common</em> block (the always-true greenfield contract) plus a
 * <em>phase-specific</em> block that frames the current phase's job and the approval gate that
 * leaves it.
 *
 * <p><b>The behaviours the prompt instructs (the cited ACs).</b> The common block instructs:
 * requirements-before-source (AC-1.1 &mdash; gather requirements through dialogue before creating
 * or editing any source file), ask-don't-write-source-early (AC-1.3 &mdash; if the developer asks
 * to implement while requirements are unconfirmed, ask for confirmation rather than writing
 * source), the no-source-write rule during the pre-approval dialogue (AC-1.4 &mdash; reinforcing
 * the structural withholding of the source-write tools the driver enforces), and the
 * approval-before-implementation gate (AC-2.3 &mdash; request developer approval when the design
 * or task breakdown is presented, before implementation begins). The phase block names the current
 * phase's deliverable and the gate to advance.
 *
 * <p><b>Why this is a real, tested artifact and not a string literal in the factory.</b> The
 * playbook text <em>is</em> the per-phase orchestration the greenfield ACs require; the gate-
 * covered composer ({@link com.srk.codingagent.cli.ToolRegistryComposer}) only carries it to the
 * {@link com.srk.codingagent.loop.AgentLoop}'s {@code system} argument. Keeping it here, behind a
 * tested accessor, lets the suite assert the playbook actually instructs each behaviour (rather
 * than merely "a prompt was set"), which is the contract the G3 phase-gating gate depends on.
 *
 * <p><b>Source-write tools are named only for the implementation phase.</b> The pre-approval
 * phases' prompts steer the model to read-only exploration and design-markdown discussion; the
 * implementation phase's prompt is where the change tools are introduced, matching the driver's
 * structural withholding of {@code write_file}/{@code edit_file}/{@code run_command} in the
 * pre-approval phases (AC-1.4). Naming the tools per phase keeps the prompt and the phase-scoped
 * registry in step so the model is steered only toward tools that are actually offered.
 *
 * <p><b>The deliverable of each pre-approval phase IS its final answer; the driver persists it
 * (RD-7, AC-1.2, AC-2.1; DCR-1, ADR-0012 amended 2026-06-23).</b> Earlier regressions (D7 prompt,
 * D8 gate, D9 schema) tried to make the model persist each deliverable via a {@code write_artifact}
 * tool call, but a clean live run showed the model reaches {@code END_TURN} in each pre-approval
 * phase <em>without ever</em> emitting that tool call — it answers in prose and stops, so the
 * artifacts held only the approval stamp. DCR-1 (Option A) amends the contract: persistence is now
 * <em>driver-guaranteed</em> &mdash; the {@link GreenfieldDriver} captures the model's settled final
 * answer on each pre-approval phase {@code END_TURN} and writes it to that phase's artifact
 * ({@code design/00-requirements.md} / {@code design/01-design.md} / {@code design/02-tasks.md}) in
 * code (via {@link com.srk.codingagent.tool.GreenfieldArtifactStore#write}) before the gate stamps.
 * So this prompt's job is no longer to make the model call a tool: it is to make the model produce
 * the <em>full, substantive deliverable as its final answer</em> (the prose the driver captures and
 * persists), not a brief summary. The {@code write_artifact} design-doc tool stays
 * <em>registered/available</em> in the pre-approval registry (it is path-confined to the target
 * repo's {@code design/} directory, so it cannot reach source files — AC-1.4 stays enforced), but it
 * is <em>optional</em>, no longer the persistence mechanism; the prompt notes it as available rather
 * than mandating it.
 */
public final class GreenfieldPlaybook {

    /** The read-only explore tools available in every phase (Class R, auto-approved; RD-4). */
    static final String EXPLORE_TOOLS = "read_file, grep, glob, and list";

    /** The source-change tools introduced only in the implementation phase (Class X; AC-1.4). */
    static final String SOURCE_CHANGE_TOOLS = "write_file, edit_file, and run_command";

    /**
     * The optional design-doc artifact-write tool the pre-approval phases still offer (Class X, but
     * path-confined so it cannot reach source files; RD-7/AC-1.2/AC-2.1). Since DCR-1 (ADR-0012
     * amended) the driver persists each phase deliverable in code from the {@code END_TURN} prose, so
     * this tool is no longer the persistence mechanism — it stays registered/available and the prompt
     * mentions it as optional. Kept as its own constant — distinct from {@link #EXPLORE_TOOLS} and
     * {@link #SOURCE_CHANGE_TOOLS} — so naming it in the pre-approval prompts does not name a
     * source-change tool (AC-1.4).
     */
    static final String ARTIFACT_WRITE_TOOL = "write_artifact";

    private GreenfieldPlaybook() {
        // Holder for the immutable per-phase playbook prompt; not instantiable.
    }

    /**
     * The greenfield system-prompt blocks for a given phase: the common greenfield contract blocks
     * (the same across every phase) followed by the phase-specific block. Returned as the
     * {@code List<String>} shape the {@link com.srk.codingagent.loop.AgentLoop} and
     * {@link com.srk.codingagent.model.converse.ModelClient} accept, so it slots directly into the
     * existing wire path the same way {@link BrownfieldPlaybook#systemPrompt()} does.
     *
     * @param phase the phase the session is currently in; must not be {@code null}.
     * @return the immutable list of system-prompt blocks for {@code phase}; never {@code null} or
     *         empty.
     * @throws NullPointerException if {@code phase} is {@code null}.
     */
    public static List<String> systemPrompt(GreenfieldPhase phase) {
        Objects.requireNonNull(phase, "phase");
        return List.of(
                "You are a coding agent building a brand-new project from scratch (greenfield "
                        + "mode). You take the project from idea to requirements to design to a "
                        + "task breakdown to implementation, one phase at a time, the same "
                        + "phase-gated, spec-driven methodology a careful designer would use.",
                "Gather requirements before you write any source. Begin by discussing the "
                        + "developer's use-case and shaping the requirements through dialogue; do "
                        + "not create or edit any source file before the requirements are agreed. "
                        + "In the requirements and design phases you have only the read-only tools "
                        + "(" + EXPLORE_TOOLS + ") and may write design documents — you cannot "
                        + "and must not write source code yet.",
                "If the developer asks you to start implementing while the requirements are still "
                        + "unconfirmed, do not jump to writing source: ask the developer to confirm "
                        + "the requirements first, then proceed. Shaping the requirements before "
                        + "code is the point of greenfield mode.",
                "The deliverable of each pre-approval phase (requirements, design, tasks) IS your "
                        + "final answer for that phase: the real, full, substantive content of that "
                        + "deliverable, not a brief summary. Your final answer for the phase is "
                        + "persisted in full as that phase's design-doc artifact in the target "
                        + "project's design/ directory, so write the complete deliverable content as "
                        + "your final answer before you ask the developer to approve the phase. (The "
                        + ARTIFACT_WRITE_TOOL + " tool is also available if you want to persist the "
                        + "artifact yourself, but it is optional — your final answer is captured and "
                        + "saved as the artifact regardless.)",
                "Advance one phase at a time, and only with explicit approval. The phases are: "
                        + "requirements, then design, then tasks, then implement. When you present "
                        + "the design and the task breakdown, request the developer's approval "
                        + "before implementation begins; do not advance into implementation, or "
                        + "begin writing source, until that approval is given.",
                phaseBlock(phase));
    }

    /**
     * The phase-specific block: it names the current phase's deliverable, the design-doc artifact the
     * deliverable is saved to (for the pre-approval phases), and the approval gate (if any) that
     * leaves it. Since DCR-1 (ADR-0012 amended) the driver persists each phase deliverable in code
     * from the model's final answer, so each pre-approval block directs the model to produce the
     * <em>full</em> deliverable content as its final answer (which becomes the artifact at the named
     * path), naming {@link #ARTIFACT_WRITE_TOOL} only as optional (RD-7/AC-1.2/AC-2.1). The
     * implementation block is the only one that introduces the source-change tools, matching the
     * driver's withholding of those Class-X tools in the pre-approval phases (AC-1.4).
     */
    private static String phaseBlock(GreenfieldPhase phase) {
        return switch (phase) {
            case REQUIREMENTS -> "Current phase: requirements. Discuss the use-case and produce the "
                    + "agreed requirements (personas, user stories, acceptance criteria, and "
                    + "non-functional requirements). Your final answer must be the full requirements "
                    + "content — it is saved as this phase's deliverable artifact at "
                    + artifactPath(phase) + " (you may also persist it yourself with the optional "
                    + ARTIFACT_WRITE_TOOL + " tool). When the requirements are complete, ask the "
                    + "developer to approve them before moving on to design.";
            case DESIGN -> "Current phase: design. Turn the approved requirements into a design "
                    + "(overview, architecture, data model, APIs, operations). Your final answer must "
                    + "be the full design content — it is saved as this phase's deliverable artifact "
                    + "at " + artifactPath(phase) + " (you may also persist it yourself with the "
                    + "optional " + ARTIFACT_WRITE_TOOL + " tool); you write design documents, not "
                    + "source code. When the design is complete, ask the developer to approve it "
                    + "before breaking it into tasks.";
            case TASKS -> "Current phase: tasks. Break the approved design into discrete, "
                    + "reviewable tasks, each with a stable identifier and each tracing to at least "
                    + "one requirement. Your final answer must be the full task breakdown — it is "
                    + "saved as this phase's deliverable artifact at " + artifactPath(phase) + " (you "
                    + "may also persist it yourself with the optional " + ARTIFACT_WRITE_TOOL
                    + " tool), and traceability is checked against it. When the task breakdown is "
                    + "complete, request the developer's approval; implementation begins only after "
                    + "the design and task breakdown are approved.";
            case IMPLEMENT -> "Current phase: implement. The design and task breakdown are approved, "
                    + "so you may now write source: implement the planned tasks one at a time using "
                    + "the change tools (" + SOURCE_CHANGE_TOOLS + "), verifying each task before "
                    + "moving to the next.";
        };
    }

    /**
     * The target-repo-relative artifact path the given pre-approval phase's deliverable is saved to
     * (e.g. {@code design/00-requirements.md}), drawn from {@link GreenfieldArtifact} so the prompt
     * and the artifact enum stay in step. Only the pre-approval phases author an artifact; this is
     * never called for {@link GreenfieldPhase#IMPLEMENT}.
     */
    private static String artifactPath(GreenfieldPhase phase) {
        return GreenfieldArtifact.forPhase(phase).orElseThrow().relativePath();
    }
}
