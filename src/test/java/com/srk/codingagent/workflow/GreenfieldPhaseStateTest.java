package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link GreenfieldPhaseState}: the greenfield mid-flow resume re-derivation (DCR-3, AC-7.6,
 * ADR-0012). It pins the load-bearing contract that a fresh {@code --mode greenfield} run
 * reconstructs its phase-state from the target repo's on-disk approval-stamped artifacts and resumes
 * at the first unstamped/absent phase, rather than always restarting at requirements.
 *
 * <p><b>Oracles trace to the spec, never to the impl:</b>
 * <ul>
 *   <li><b>AC-7.6 / ADR-0012:</b> "resume at the first phase whose artifact is unstamped or absent
 *       ... A phase whose artifact bears the AC-1.5 approval stamp is treated as approved ... If all
 *       pre-approval phases are stamped, resume at IMPLEMENT" — the resume-phase derivation.</li>
 *   <li><b>AC-7.6 (retry-in-place):</b> "an interrupted mid-phase (whose artifact is unstamped) is
 *       re-entered, so a transient failure is retryable in place" — an unstamped/absent phase is the
 *       resume phase.</li>
 *   <li><b>DCR-1 (kept):</b> the already-approved phases' artifacts are pre-seeded as approved
 *       upstream for the resume phase's cross-phase-continuity injection.</li>
 * </ul>
 *
 * <p>The SUT is the real {@link GreenfieldPhaseState}; the only collaborator is a scripted
 * {@link GreenfieldPhaseState.Probe} standing in for the on-disk artifact store (the external
 * filesystem boundary), never a mock of the SUT.
 */
class GreenfieldPhaseStateTest {

    /** A scripted probe: a fixed set of artifacts are approval-stamped on disk, each with content. */
    private static GreenfieldPhaseState.Probe probeWithApproved(
            Set<GreenfieldArtifact> approved, Map<GreenfieldArtifact, String> content) {
        return new GreenfieldPhaseState.Probe() {
            @Override
            public boolean isApproved(GreenfieldArtifact artifact) {
                return approved.contains(artifact);
            }

            @Override
            public String content(GreenfieldArtifact artifact) {
                return content.getOrDefault(artifact, "");
            }
        };
    }

    // --- AC-7.6 : resume at the first unstamped/absent phase --------------------------------------

    @Test
    @DisplayName("AC-7.6: a project with an approval-stamped requirements artifact resumes at DESIGN, not requirements")
    void approvedRequirementsResumesAtDesign() {
        // Oracle: AC-7.6 — "reconstruct its phase-state from those on-disk artifacts and resume at the
        // first phase whose artifact is unstamped or absent". With ONLY requirements stamped, the
        // first unstamped/absent phase is DESIGN, so the session resumes at DESIGN — it does NOT
        // restart at requirements. Expected phase traces to AC-7.6's resume rule, not to impl.
        GreenfieldPhaseState.Probe probe = probeWithApproved(
                EnumSet.of(GreenfieldArtifact.REQUIREMENTS),
                Map.of(GreenfieldArtifact.REQUIREMENTS, "# Approved requirements\n"));

        GreenfieldPhaseState state = GreenfieldPhaseState.reconstruct(probe);

        assertEquals(GreenfieldPhase.DESIGN, state.resumePhase(),
                "AC-7.6: a stamped requirements artifact is treated as approved, so resume at the "
                        + "first unstamped phase (design), not at requirements");
    }

    @Test
    @DisplayName("AC-7.6: a project with approved requirements + design resumes at TASKS")
    void approvedRequirementsAndDesignResumesAtTasks() {
        // Oracle: AC-7.6 / ADR-0012 — "a project with approved requirements + design resumes at
        // tasks". With requirements AND design stamped, the first unstamped/absent phase is TASKS.
        GreenfieldPhaseState.Probe probe = probeWithApproved(
                EnumSet.of(GreenfieldArtifact.REQUIREMENTS, GreenfieldArtifact.DESIGN),
                Map.of(GreenfieldArtifact.REQUIREMENTS, "# reqs\n",
                        GreenfieldArtifact.DESIGN, "# design\n"));

        GreenfieldPhaseState state = GreenfieldPhaseState.reconstruct(probe);

        assertEquals(GreenfieldPhase.TASKS, state.resumePhase(),
                "AC-7.6: approved requirements + design resumes at the first unstamped phase (tasks)");
    }

    @Test
    @DisplayName("AC-7.6: when every pre-approval phase artifact is stamped, resume at IMPLEMENT")
    void allPreApprovalStampedResumesAtImplement() {
        // Oracle: AC-7.6 / ADR-0012 — "If all pre-approval phases are stamped, resume at IMPLEMENT".
        // With requirements + design + tasks all stamped, no pre-approval phase remains unstamped, so
        // the resume phase is the terminal IMPLEMENT phase.
        GreenfieldPhaseState.Probe probe = probeWithApproved(
                EnumSet.of(GreenfieldArtifact.REQUIREMENTS, GreenfieldArtifact.DESIGN,
                        GreenfieldArtifact.TASKS),
                Map.of(GreenfieldArtifact.REQUIREMENTS, "# reqs\n",
                        GreenfieldArtifact.DESIGN, "# design\n",
                        GreenfieldArtifact.TASKS, "# tasks\n"));

        GreenfieldPhaseState state = GreenfieldPhaseState.reconstruct(probe);

        assertEquals(GreenfieldPhase.IMPLEMENT, state.resumePhase(),
                "AC-7.6: all pre-approval phases stamped resumes at the terminal implement phase");
    }

    // --- AC-7.6 (retry-in-place) : an unstamped/absent phase is re-entered ------------------------

    @Test
    @DisplayName("AC-7.6 retry-in-place: an unstamped/absent requirements artifact re-enters requirements (a fresh project starts at requirements)")
    void unstampedRequirementsReEntersRequirements() {
        // Oracle: AC-7.6 — "an unstamped/absent requirements artifact starts (or re-enters)
        // requirements (retry-in-place)". A fresh project (nothing stamped) — or an interrupted
        // requirements phase whose artifact is unstamped — re-derives requirements as the resume
        // phase. Expected phase traces to AC-7.6's symmetric (negative) clause + retry-in-place.
        GreenfieldPhaseState.Probe probe = probeWithApproved(EnumSet.noneOf(GreenfieldArtifact.class),
                Map.of());

        GreenfieldPhaseState state = GreenfieldPhaseState.reconstruct(probe);

        assertEquals(GreenfieldPhase.REQUIREMENTS, state.resumePhase(),
                "AC-7.6: an unstamped/absent requirements artifact re-enters requirements "
                        + "(retry-in-place); a fresh project starts at requirements");
    }

    @Test
    @DisplayName("AC-7.6 retry-in-place: approved requirements but an UNSTAMPED design (interrupted mid-design) re-enters DESIGN")
    void approvedRequirementsUnstampedDesignReEntersDesign() {
        // Oracle: AC-7.6 — "a transient failure is retryable in place ... an interrupted mid-phase
        // (whose artifact is unstamped) is re-entered". With requirements stamped but design left
        // unstamped (the failure mode: a transient interruption mid-design wrote a draft but never
        // got the approval stamp), the resume phase is DESIGN — the same phase re-entered. The fact
        // that design has on-disk content but no stamp must NOT promote it past the gate.
        GreenfieldPhaseState.Probe probe = probeWithApproved(
                EnumSet.of(GreenfieldArtifact.REQUIREMENTS),
                Map.of(GreenfieldArtifact.REQUIREMENTS, "# reqs\n",
                        GreenfieldArtifact.DESIGN, "# half-written design (no stamp)\n"));

        GreenfieldPhaseState state = GreenfieldPhaseState.reconstruct(probe);

        assertEquals(GreenfieldPhase.DESIGN, state.resumePhase(),
                "AC-7.6: an interrupted-mid-design (unstamped) phase is re-entered (retry-in-place), "
                        + "regardless of any unstamped draft content on disk");
    }

    @Test
    @DisplayName("AC-7.6: the derivation stops at the FIRST unstamped phase even if a LATER phase is somehow stamped (ordered, gap-free)")
    void stopsAtFirstUnstampedEvenIfLaterPhaseStamped() {
        // Oracle: AC-7.6 / ADR-0012 — resume is "the first phase whose artifact is unstamped or
        // absent". The legal advance order is requirements->design->tasks (GreenfieldPhase order). If
        // requirements is unstamped but tasks is (anomalously) stamped, the resume phase is still the
        // FIRST unstamped phase (requirements) — a later stamp does not skip an earlier unapproved
        // phase. This pins the ORDERED, gap-free derivation against an out-of-order on-disk state.
        GreenfieldPhaseState.Probe probe = probeWithApproved(
                EnumSet.of(GreenfieldArtifact.TASKS),
                Map.of(GreenfieldArtifact.TASKS, "# tasks (anomalously stamped)\n"));

        GreenfieldPhaseState state = GreenfieldPhaseState.reconstruct(probe);

        assertEquals(GreenfieldPhase.REQUIREMENTS, state.resumePhase(),
                "AC-7.6: resume at the FIRST unstamped phase (requirements) — a later out-of-order "
                        + "stamp does not skip an earlier unapproved phase");
    }

    // --- DCR-1 (kept) : the already-approved phases' artifacts are pre-seeded as approved upstream --

    @Test
    @DisplayName("DCR-1 (kept): resume pre-seeds the already-approved phases' artifact content for cross-phase continuity")
    void approvedArtifactsArePreSeeded() {
        // Oracle: ADR-0012 (DCR-1 kept, cited by the DCR-3 amendment) — "on resume, the already-
        // approved phases' artifacts should be loaded into [the approvedArtifacts] map so DESIGN/TASKS
        // still see approved upstream". Resuming at TASKS over approved requirements + design, the
        // reconstructed state must carry BOTH approved upstream artifacts with their on-disk content,
        // keyed by artifact — so the resumed driver injects them as upstream context. Expected
        // content traces to the probe's on-disk content + the DCR-1 cross-phase-continuity contract.
        GreenfieldPhaseState.Probe probe = probeWithApproved(
                EnumSet.of(GreenfieldArtifact.REQUIREMENTS, GreenfieldArtifact.DESIGN),
                Map.of(GreenfieldArtifact.REQUIREMENTS, "APPROVED-REQUIREMENTS-BODY",
                        GreenfieldArtifact.DESIGN, "APPROVED-DESIGN-BODY"));

        GreenfieldPhaseState state = GreenfieldPhaseState.reconstruct(probe);

        Map<GreenfieldArtifact, String> approved = state.approvedArtifacts();
        assertEquals("APPROVED-REQUIREMENTS-BODY", approved.get(GreenfieldArtifact.REQUIREMENTS),
                "DCR-1: the approved requirements content is pre-seeded for cross-phase continuity");
        assertEquals("APPROVED-DESIGN-BODY", approved.get(GreenfieldArtifact.DESIGN),
                "DCR-1: the approved design content is pre-seeded for cross-phase continuity");
        assertFalse(approved.containsKey(GreenfieldArtifact.TASKS),
                "the unapproved (resume) phase's artifact is NOT pre-seeded as approved upstream");
    }

    @Test
    @DisplayName("AC-7.6: a fresh project pre-seeds no approved upstream (resume at requirements, empty map)")
    void freshProjectPreSeedsNothing() {
        // Oracle: AC-7.6 — a fresh project (nothing stamped) resumes at requirements with no approved
        // upstream. The approvedArtifacts map is empty.
        GreenfieldPhaseState state = GreenfieldPhaseState.reconstruct(
                probeWithApproved(EnumSet.noneOf(GreenfieldArtifact.class), Map.of()));

        assertEquals(GreenfieldPhase.REQUIREMENTS, state.resumePhase());
        assertTrue(state.approvedArtifacts().isEmpty(),
                "a fresh project pre-seeds no approved upstream");
    }

    // --- fresh() : the no-resume default ---------------------------------------------------------

    @Test
    @DisplayName("fresh() is the no-resume state: resume at requirements with no approved upstream")
    void freshIsTheNoResumeDefault() {
        // Oracle: AC-7.6 / ADR-0012 — the non-resume (always-start-at-requirements) baseline is
        // requirements with no approved upstream; fresh() models exactly that.
        GreenfieldPhaseState fresh = GreenfieldPhaseState.fresh();

        assertEquals(GreenfieldPhase.REQUIREMENTS, fresh.resumePhase(),
                "fresh() resumes at requirements (the initial phase)");
        assertTrue(fresh.approvedArtifacts().isEmpty(),
                "fresh() pre-seeds no approved upstream");
    }

    @Test
    @DisplayName("approvedArtifacts() returns a defensive copy — mutating it does not corrupt the state")
    void approvedArtifactsIsADefensiveCopy() {
        // Effective Java Item 50: a returned mutable collection must not expose internal state.
        GreenfieldPhaseState state = GreenfieldPhaseState.reconstruct(probeWithApproved(
                EnumSet.of(GreenfieldArtifact.REQUIREMENTS),
                Map.of(GreenfieldArtifact.REQUIREMENTS, "reqs")));

        Map<GreenfieldArtifact, String> firstView = state.approvedArtifacts();
        firstView.clear();

        assertEquals("reqs", state.approvedArtifacts().get(GreenfieldArtifact.REQUIREMENTS),
                "mutating the returned map must not corrupt the reconstructed state");
    }

    @Test
    @DisplayName("reconstruct rejects a null probe")
    void reconstructRejectsNullProbe() {
        assertThrows(NullPointerException.class, () -> GreenfieldPhaseState.reconstruct(null));
    }

    @Test
    @DisplayName("reconstruct rejects a null approved-artifact content (the probe must not return null for an approved artifact)")
    void reconstructRejectsNullApprovedContent() {
        // Defensive: a probe that reports an artifact approved but returns null content is a bug; the
        // reconstruction fails fast (EJ Item 49) rather than pre-seeding a null upstream that would
        // NPE later in the driver's prompt assembly.
        GreenfieldPhaseState.Probe probe = new GreenfieldPhaseState.Probe() {
            @Override
            public boolean isApproved(GreenfieldArtifact artifact) {
                return artifact == GreenfieldArtifact.REQUIREMENTS;
            }

            @Override
            public String content(GreenfieldArtifact artifact) {
                return null;
            }
        };

        assertThrows(NullPointerException.class, () -> GreenfieldPhaseState.reconstruct(probe));
    }

    @Test
    @DisplayName("reconstruct never queries content() for a phase past the resume phase (only approved upstream is read)")
    void contentReadOnlyForApprovedUpstream() {
        // Oracle: GreenfieldPhaseState.Probe contract — content() is "Called only for an artifact
        // reported approved". Pre-seeding must read content ONLY for the already-approved phases (the
        // upstream), never for the resume phase or beyond. With requirements approved and design
        // unstamped, content() must be queried for requirements and NOT for design/tasks.
        Map<GreenfieldArtifact, Boolean> stamped = new EnumMap<>(GreenfieldArtifact.class);
        stamped.put(GreenfieldArtifact.REQUIREMENTS, true);
        Set<GreenfieldArtifact> contentReads = EnumSet.noneOf(GreenfieldArtifact.class);
        GreenfieldPhaseState.Probe probe = new GreenfieldPhaseState.Probe() {
            @Override
            public boolean isApproved(GreenfieldArtifact artifact) {
                return stamped.getOrDefault(artifact, false);
            }

            @Override
            public String content(GreenfieldArtifact artifact) {
                contentReads.add(artifact);
                return "body";
            }
        };

        GreenfieldPhaseState.reconstruct(probe);

        assertEquals(EnumSet.of(GreenfieldArtifact.REQUIREMENTS), contentReads,
                "content() is read only for the approved upstream (requirements), not the resume "
                        + "phase (design) or beyond");
    }
}
