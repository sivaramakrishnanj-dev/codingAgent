package com.srk.codingagent.workflow;

import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The greenfield approval gate that <b>records the approval timestamp into the phase's artifact</b>
 * when the developer confirms a phase (component C3, ADR-0012 greenfield side; AC-1.5). It is the
 * implementation of the {@link GreenfieldDriver.ApprovalGate} extension point the driver's seam
 * Javadoc names: "T-3.2 layers approval-timestamp recording over this seam."
 *
 * <p><b>What it adds over the bare yes/no decision (AC-1.5, generalized per ADR-0012).</b> AC-1.5
 * requires that when the developer confirms the requirements, the agent records the approval with a
 * timestamp in the requirements artifact; ADR-0012 generalizes the approval gate (and so the
 * timestamped record) to <em>each</em> phase ("each approval is recorded (timestamped) in the
 * artifact"). This gate wraps the underlying {@link ApprovalDecision} (the developer's per-phase
 * yes/no): on a confirmed phase it appends a timestamped approval line — drawn from the boundary
 * clock (ADR-0005) — to that phase's artifact ({@link GreenfieldArtifact}) before returning
 * {@code true}, so the recorded artifact carries the moment of approval. A declined decision records
 * nothing and returns {@code false}, leaving the driver to stop at the gate without writing source
 * (AC-1.4).
 *
 * <p><b>Traceability is enforced at the tasks gate (AC-2.5).</b> Before recording the
 * {@link GreenfieldPhase#TASKS} approval — the gate that admits the session into implementation
 * (AC-2.3) — this gate checks that every task in the task-breakdown artifact traces to at least one
 * requirement ({@link TaskTraceability}, AC-2.5/AC-2.2). If the breakdown is untraceable the
 * approval is refused (the gate returns {@code false} without stamping), so the session cannot
 * advance into implementation with an untraceable breakdown. The traceability guarantee is thus a
 * property the gate enforces, not merely a behaviour the prompt requests.
 *
 * <p>Not thread-safe: one gate serves one greenfield session on a single thread.
 */
public final class ArtifactApprovalGate implements GreenfieldDriver.ApprovalGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactApprovalGate.class);

    private final ApprovalDecision decision;
    private final GreenfieldArtifactStore store;
    private final Supplier<String> clock;

    /**
     * Creates the approval gate over its decision seam, the target-repo artifact store, and the
     * boundary clock.
     *
     * @param decision the underlying per-phase developer yes/no (an interactive prompt in
     *                 production, scripted in tests); must not be {@code null}.
     * @param store    the target-repo artifact store the approval line is stamped through (AC-1.5);
     *                 must not be {@code null}.
     * @param clock    the boundary clock (ADR-0005) the approval timestamp is drawn from; must not
     *                 be {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public ArtifactApprovalGate(
            ApprovalDecision decision, GreenfieldArtifactStore store, Supplier<String> clock) {
        this.decision = Objects.requireNonNull(decision, "decision");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Consults the developer's decision for advancing out of {@code completedPhase}; on approval,
     * enforces task-breakdown traceability (for the tasks phase) and records the approval timestamp
     * into the phase's artifact before approving the advance.
     *
     * <ol>
     *   <li>If the developer declines, nothing is recorded and {@code false} is returned (the driver
     *       stops at the gate awaiting approval, no source written — AC-1.4).</li>
     *   <li>If the developer approves and the completed phase is {@link GreenfieldPhase#TASKS}, the
     *       task breakdown must be traceable (every task &rarr; &ge; 1 requirement, AC-2.5); an
     *       untraceable breakdown refuses the approval (returns {@code false}) without stamping.</li>
     *   <li>Otherwise the approval timestamp is appended to the completed phase's artifact (AC-1.5)
     *       and {@code true} is returned so the driver advances.</li>
     * </ol>
     *
     * @param completedPhase the phase whose deliverable was just presented; never {@code null}. Its
     *                       artifact (when it authors one) is the one stamped.
     * @return {@code true} to advance (approval recorded), {@code false} to stop at the gate.
     */
    @Override
    public boolean approveAdvance(GreenfieldPhase completedPhase) {
        Objects.requireNonNull(completedPhase, "completedPhase");
        if (!decision.approve(completedPhase)) {
            LOGGER.info("Greenfield {} phase not approved; recording no approval (AC-2.3)",
                    completedPhase);
            return false;
        }
        Optional<GreenfieldArtifact> artifact = GreenfieldArtifact.forPhase(completedPhase);
        if (completedPhase == GreenfieldPhase.TASKS && artifact.isPresent()
                && !enforceTraceability(artifact.get())) {
            return false;
        }
        artifact.ifPresent(this::recordApproval);
        return true;
    }

    /**
     * Checks that the task-breakdown artifact is traceable (AC-2.5) before the tasks approval is
     * recorded. A missing or untraceable breakdown refuses the approval.
     */
    private boolean enforceTraceability(GreenfieldArtifact tasksArtifact) {
        Optional<String> breakdown = store.read(tasksArtifact.relativePath());
        if (breakdown.isEmpty()) {
            LOGGER.warn("Greenfield tasks approval refused: no task-breakdown artifact at {} to "
                    + "verify traceability (AC-2.5)", tasksArtifact.relativePath());
            return false;
        }
        TaskTraceability.Result result = TaskTraceability.check(breakdown.get());
        if (!result.traceable()) {
            LOGGER.warn("Greenfield tasks approval refused: task breakdown is not traceable "
                    + "(AC-2.5); {} task(s), untraced: {}", result.taskCount(), result.untracedTasks());
            return false;
        }
        LOGGER.info("Greenfield task breakdown traceability verified: {} task(s) each trace to a "
                + "requirement (AC-2.5)", result.taskCount());
        return true;
    }

    /**
     * Appends the timestamped approval line to the completed phase's artifact (AC-1.5). The
     * timestamp is the boundary clock's reading at the moment of approval.
     */
    private void recordApproval(GreenfieldArtifact artifact) {
        String timestamp = clock.get();
        String line = ApprovalStamp.line(artifact, timestamp);
        store.appendLine(artifact.relativePath(), line);
        LOGGER.info("Recorded {} approval (timestamp {}) in {} (AC-1.5)",
                artifact.heading(), timestamp, artifact.relativePath());
    }

    /**
     * The per-phase developer approval decision the gate wraps: given the phase just completed and
     * presented, whether the developer confirms advancing. The interactive REPL supplies a real
     * prompt; tests supply a scripted decision. This is the bare yes/no; the {@link ArtifactApprovalGate}
     * layers the timestamp recording (AC-1.5) and traceability enforcement (AC-2.5) over it.
     */
    @FunctionalInterface
    public interface ApprovalDecision {

        /**
         * Whether the developer approves advancing out of the just-completed phase.
         *
         * @param completedPhase the phase whose deliverable was presented; never {@code null}.
         * @return {@code true} to approve the advance, {@code false} to decline.
         */
        boolean approve(GreenfieldPhase completedPhase);
    }
}
