package com.srk.codingagent.workflow;

import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.util.Objects;
import java.util.Optional;

/**
 * The greenfield design-doc artifacts each phase persists into the <em>target</em> project (component
 * C3, ADR-0012 greenfield side; RD-7): the target-repo-relative path of the requirements, design,
 * and task-breakdown markdown the agent authors as it moves through the {@link GreenfieldPhase} state
 * machine.
 *
 * <p><b>One artifact per authoring phase (AC-1.2, AC-2.1).</b> AC-1.2 requires the agreed
 * requirements to be persisted as markdown in the target project; AC-2.1 requires a design artifact
 * and a task-breakdown artifact. The three authoring phases of the machine map to those three
 * artifacts; the terminal {@link GreenfieldPhase#IMPLEMENT} phase authors no new design-doc artifact
 * (it writes source instead), so it has no artifact here.
 *
 * <p><b>Reflexive consistency (ADR-0012).</b> The artifacts mirror the shape of codingAgent's own
 * {@code design/} tree — a requirements markdown, a design markdown, a tasks markdown — but are
 * written into the target project's {@code design/} directory
 * ({@link GreenfieldArtifactStore#ARTIFACT_DIR}), which is distinct from codingAgent's own tree. The
 * numeric file-name prefixes preserve the same author-ordering a careful designer would use.
 */
public enum GreenfieldArtifact {

    /**
     * The requirements artifact the {@link GreenfieldPhase#REQUIREMENTS} phase persists (AC-1.2):
     * the agreed requirements (personas, user stories, acceptance criteria, NFRs) as markdown in the
     * target project.
     */
    REQUIREMENTS(GreenfieldPhase.REQUIREMENTS, "00-requirements.md", "Requirements"),

    /**
     * The design artifact the {@link GreenfieldPhase#DESIGN} phase persists (AC-2.1): the design
     * (overview, architecture, data model, APIs, operations) as markdown in the target project.
     */
    DESIGN(GreenfieldPhase.DESIGN, "01-design.md", "Design"),

    /**
     * The task-breakdown artifact the {@link GreenfieldPhase#TASKS} phase persists (AC-2.1): the
     * discrete, traceable tasks as markdown in the target project. Every task traces to at least one
     * requirement (AC-2.5) and carries a stable identifier (AC-2.2).
     */
    TASKS(GreenfieldPhase.TASKS, "02-tasks.md", "Tasks");

    private final GreenfieldPhase phase;
    private final String fileName;
    private final String heading;

    GreenfieldArtifact(GreenfieldPhase phase, String fileName, String heading) {
        this.phase = phase;
        this.fileName = fileName;
        this.heading = heading;
    }

    /**
     * The artifact a given phase authors, if any. The three pre-approval phases each author one
     * artifact; the terminal implementation phase authors none.
     *
     * @param phase the greenfield phase; must not be {@code null}.
     * @return the artifact that phase authors, or {@link Optional#empty()} for
     *         {@link GreenfieldPhase#IMPLEMENT}.
     * @throws NullPointerException if {@code phase} is {@code null}.
     */
    public static Optional<GreenfieldArtifact> forPhase(GreenfieldPhase phase) {
        Objects.requireNonNull(phase, "phase");
        for (GreenfieldArtifact artifact : values()) {
            if (artifact.phase == phase) {
                return Optional.of(artifact);
            }
        }
        return Optional.empty();
    }

    /**
     * The phase that authors this artifact.
     *
     * @return the authoring phase; never {@code null}.
     */
    public GreenfieldPhase phase() {
        return phase;
    }

    /**
     * The artifact's target-repo-relative path (under the artifact directory), e.g.
     * {@code design/00-requirements.md}. This is the path the {@link GreenfieldArtifactStore} writes
     * and stamps, confined to the target repo's design-doc directory.
     *
     * @return the target-repo-relative artifact path; never {@code null}.
     */
    public String relativePath() {
        return GreenfieldArtifactStore.ARTIFACT_DIR + "/" + fileName;
    }

    /**
     * A short human heading for the artifact ({@code Requirements} / {@code Design} / {@code Tasks}),
     * used in the approval-stamp line so the recorded approval names which artifact was approved.
     *
     * @return the artifact heading; never {@code null}.
     */
    public String heading() {
        return heading;
    }
}
