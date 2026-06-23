package com.srk.codingagent.workflow;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconstructs a greenfield session's phase-state from the target repo's on-disk approval-stamped
 * artifacts (component C3, ADR-0012 greenfield-resume side; AC-7.6, DCR-3). This is the load-bearing
 * resume logic: a fresh {@code codingagent --mode greenfield} run does not always restart at
 * {@link GreenfieldPhase#initial()} &mdash; it reads which phases the target project already has
 * <em>approved</em> on disk and resumes at the first unapproved phase.
 *
 * <p><b>The stamp is the durable resume state (AC-1.5; ADR-0012 — no separate phase-state
 * persistence).</b> A phase whose artifact ({@link GreenfieldArtifact#relativePath()}) bears the
 * AC-1.5 approval stamp is treated as <em>approved</em>; the {@link #resumePhase() resume phase} is
 * the first pre-approval phase whose artifact is <em>unstamped or absent</em>, or
 * {@link GreenfieldPhase#IMPLEMENT} when every pre-approval phase's artifact is stamped. So a fresh
 * run over a project with an approved requirements artifact resumes at design; approved
 * requirements + design resumes at tasks; and all-approved resumes at implement.
 *
 * <p><b>Transient mid-phase failure is retryable in place &mdash; it falls out of the same rule
 * (AC-7.6).</b> A mid-phase failure (a model-backend timeout, any non-approval interruption) leaves
 * the failed phase's artifact <em>unstamped</em> (approval is the only thing that stamps &mdash;
 * {@link ArtifactApprovalGate}), so re-derivation naturally selects that same phase as the resume
 * phase and re-enters it. There is no separate retry contract.
 *
 * <p><b>Approved upstream is pre-seeded for cross-phase continuity (DCR-1 kept).</b> Each
 * already-approved phase's artifact content is collected into {@link #approvedArtifacts()} so the
 * resumed driver injects the approved upstream into the resume phase's prompt &mdash; e.g. resuming
 * at design still sees the approved requirements (the same map {@link GreenfieldDriver} fills as it
 * advances within a single run).
 *
 * <p><b>Accepted tradeoff (ADR-0012).</b> Only phase <em>boundaries</em> are reconstructed from the
 * artifacts &mdash; the in-phase multi-turn transcript of an interrupted phase is not preserved.
 * Resume re-enters at the phase boundary and re-converses.
 *
 * <p>Immutable: a reconstructed state is computed once from a {@link Probe} reading and never
 * mutated.
 */
public final class GreenfieldPhaseState {

    private static final Logger LOGGER = LoggerFactory.getLogger(GreenfieldPhaseState.class);

    private final GreenfieldPhase resumePhase;
    private final Map<GreenfieldArtifact, String> approvedArtifacts;

    private GreenfieldPhaseState(
            GreenfieldPhase resumePhase, Map<GreenfieldArtifact, String> approvedArtifacts) {
        this.resumePhase = resumePhase;
        this.approvedArtifacts = approvedArtifacts;
    }

    /**
     * Reconstructs the phase-state by probing each pre-approval phase's artifact in declared phase
     * order (AC-7.6, ADR-0012). The resume phase is the first phase whose artifact is unstamped or
     * absent; every phase before it (whose artifact is stamped) is recorded as approved with its
     * on-disk content. When every pre-approval phase is stamped, the resume phase is
     * {@link GreenfieldPhase#IMPLEMENT}.
     *
     * @param probe reads, per artifact, whether it carries the AC-1.5 approval stamp and its current
     *              content; must not be {@code null}. In production it is backed by the target-repo
     *              {@link com.srk.codingagent.tool.GreenfieldArtifactStore}.
     * @return the reconstructed phase-state (resume phase + approved upstream); never {@code null}.
     * @throws NullPointerException if {@code probe} (or a content it returns) is {@code null}.
     */
    public static GreenfieldPhaseState reconstruct(Probe probe) {
        Objects.requireNonNull(probe, "probe");
        Map<GreenfieldArtifact, String> approved = new EnumMap<>(GreenfieldArtifact.class);
        GreenfieldPhase phase = GreenfieldPhase.initial();
        while (phase.isPreApproval()) {
            Optional<GreenfieldArtifact> artifact = GreenfieldArtifact.forPhase(phase);
            if (artifact.isEmpty() || !probe.isApproved(artifact.get())) {
                // The first unstamped/absent pre-approval phase is where the session resumes
                // (retry-in-place if it was interrupted mid-phase). Stop accumulating approvals here.
                break;
            }
            approved.put(artifact.get(),
                    Objects.requireNonNull(probe.content(artifact.get()),
                            "approved artifact content"));
            phase = phase.next().orElseThrow();
        }
        LOGGER.info("Greenfield phase-state reconstructed from on-disk artifacts: resume at {} "
                + "({} earlier phase(s) already approved) (AC-7.6, ADR-0012)",
                phase, approved.size());
        return new GreenfieldPhaseState(phase, approved);
    }

    /**
     * A fresh-start phase-state: resume at {@link GreenfieldPhase#initial()} with no approved
     * upstream. The state of a brand-new target project (no stamped artifacts), and the default the
     * non-resume {@link GreenfieldDriver} constructor uses.
     *
     * @return the initial (no-resume) phase-state; never {@code null}.
     */
    public static GreenfieldPhaseState fresh() {
        return new GreenfieldPhaseState(
                GreenfieldPhase.initial(), new EnumMap<>(GreenfieldArtifact.class));
    }

    /**
     * The phase the greenfield session resumes at: the first pre-approval phase whose artifact is
     * unstamped or absent, or {@link GreenfieldPhase#IMPLEMENT} when every pre-approval phase is
     * approved (AC-7.6).
     *
     * @return the resume phase; never {@code null}.
     */
    public GreenfieldPhase resumePhase() {
        return resumePhase;
    }

    /**
     * The already-approved phases' artifact content, keyed by {@link GreenfieldArtifact}, to
     * pre-seed the resumed driver's cross-phase-continuity injection (DCR-1 kept): the resume phase's
     * prompt then carries the approved upstream just as a single uninterrupted run would.
     *
     * @return a fresh mutable {@link EnumMap} of approved artifacts (empty when resuming at
     *         requirements / a fresh project); never {@code null}.
     */
    public Map<GreenfieldArtifact, String> approvedArtifacts() {
        return new EnumMap<>(approvedArtifacts);
    }

    /**
     * The per-artifact on-disk probe the reconstruction reads: whether an artifact carries the
     * AC-1.5 approval stamp, and its current content (read only for an approved artifact, so it can
     * be pre-seeded as approved upstream). In production it is backed by the target-repo
     * {@link com.srk.codingagent.tool.GreenfieldArtifactStore} (sharing the SAME stamp-detection the
     * D13 clobber guard uses &mdash; one durable on-disk fact); in tests it is a scripted double.
     */
    public interface Probe {

        /**
         * Whether {@code artifact} on disk carries the AC-1.5 approval stamp (so its phase is treated
         * as approved on resume, AC-7.6). An absent artifact is unstamped.
         *
         * @param artifact the design-doc artifact to probe; never {@code null}.
         * @return {@code true} if the artifact is approval-stamped; {@code false} if unstamped or
         *         absent.
         */
        boolean isApproved(GreenfieldArtifact artifact);

        /**
         * The current on-disk content of an approved {@code artifact}, for pre-seeding the resumed
         * driver's cross-phase-continuity injection (DCR-1). Called only for an artifact
         * {@link #isApproved(GreenfieldArtifact) reported approved}.
         *
         * @param artifact the approved design-doc artifact to read; never {@code null}.
         * @return the artifact's current content (the empty string if unexpectedly absent); never
         *         {@code null}.
         */
        String content(GreenfieldArtifact artifact);
    }
}
