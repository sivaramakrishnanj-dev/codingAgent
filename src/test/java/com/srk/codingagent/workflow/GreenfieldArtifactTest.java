package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link GreenfieldArtifact}: the mapping from each greenfield phase to the design-doc artifact
 * it persists into the target repo (RD-7, AC-1.2, AC-2.1).
 *
 * <p><b>Oracles trace to the spec:</b>
 * <ul>
 *   <li><b>AC-1.2:</b> the requirements phase persists a requirements artifact.</li>
 *   <li><b>AC-2.1:</b> the design and tasks phases persist a design artifact and a task-breakdown
 *       artifact respectively.</li>
 *   <li><b>ADR-0012:</b> the artifacts are written under the target repo's design-doc directory,
 *       and the terminal implementation phase authors no design-doc artifact (it writes source).</li>
 * </ul>
 */
class GreenfieldArtifactTest {

    @Test
    @DisplayName("AC-1.2: the requirements phase maps to a requirements artifact under the target repo's design/ dir")
    void requirementsPhaseHasRequirementsArtifact() {
        // Oracle: AC-1.2 — persist the agreed requirements as a markdown artifact in the target
        // project. The requirements phase must map to an artifact whose path is under the design-doc
        // directory. The expected path prefix traces to ADR-0012's "design markdown in the target
        // project" / RD-7, not to the enum's literal file name.
        GreenfieldArtifact artifact = GreenfieldArtifact.forPhase(GreenfieldPhase.REQUIREMENTS)
                .orElseThrow();
        assertEquals(GreenfieldPhase.REQUIREMENTS, artifact.phase());
        assertTrue(artifact.relativePath().startsWith(
                        com.srk.codingagent.tool.GreenfieldArtifactStore.ARTIFACT_DIR + "/"),
                "AC-1.2: the requirements artifact lives under the target repo's design-doc directory; was: "
                        + artifact.relativePath());
        assertTrue(artifact.relativePath().endsWith(".md"),
                "RD-7: the requirements artifact is persisted as markdown");
    }

    @Test
    @DisplayName("AC-2.1: the design and tasks phases each map to a distinct markdown artifact in the target repo")
    void designAndTasksPhasesHaveDistinctArtifacts() {
        // Oracle: AC-2.1 — when requirements are confirmed, the agent produces a design artifact AND a
        // task-breakdown artifact as markdown in the target project. So the design phase and the tasks
        // phase must each map to a distinct markdown artifact.
        GreenfieldArtifact design = GreenfieldArtifact.forPhase(GreenfieldPhase.DESIGN).orElseThrow();
        GreenfieldArtifact tasks = GreenfieldArtifact.forPhase(GreenfieldPhase.TASKS).orElseThrow();

        assertTrue(design.relativePath().endsWith(".md") && tasks.relativePath().endsWith(".md"),
                "AC-2.1/RD-7: design and task-breakdown artifacts are markdown");
        assertEquals(2, java.util.Set.of(design.relativePath(), tasks.relativePath()).size(),
                "AC-2.1: the design artifact and the task-breakdown artifact are distinct files");
    }

    @Test
    @DisplayName("ADR-0012: the terminal implementation phase authors no design-doc artifact")
    void implementPhaseAuthorsNoArtifact() {
        // Oracle: ADR-0012 — the phases that author design markdown are requirements/design/tasks; the
        // implementation phase writes source, not a design-doc artifact. So no artifact maps to IMPLEMENT.
        assertEquals(Optional.empty(), GreenfieldArtifact.forPhase(GreenfieldPhase.IMPLEMENT),
                "ADR-0012: the implementation phase has no design-doc artifact to author");
    }

    @Test
    @DisplayName("every authoring phase maps to exactly one artifact and forPhase rejects null")
    void everyAuthoringPhaseHasOneArtifact() {
        // Oracle: AC-1.2 + AC-2.1 — the three authoring phases (requirements/design/tasks) each have
        // one artifact; the round-trip phase()->forPhase() is consistent.
        for (GreenfieldArtifact artifact : GreenfieldArtifact.values()) {
            assertEquals(Optional.of(artifact), GreenfieldArtifact.forPhase(artifact.phase()),
                    "each artifact is the one its authoring phase maps to");
            assertTrue(artifact.phase().isPreApproval(),
                    "ADR-0012: design-doc artifacts are authored in the pre-approval phases");
        }
        assertThrows(NullPointerException.class, () -> GreenfieldArtifact.forPhase(null));
    }
}
