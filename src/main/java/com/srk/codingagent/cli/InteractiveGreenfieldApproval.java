package com.srk.codingagent.cli;

import com.srk.codingagent.tool.GreenfieldArtifactStore;
import com.srk.codingagent.workflow.ArtifactApprovalGate;
import com.srk.codingagent.workflow.GreenfieldArtifact;
import com.srk.codingagent.workflow.GreenfieldPhase;
import java.io.PrintStream;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The interactive, stdin-backed per-phase {@link ArtifactApprovalGate.ApprovalDecision} used on the
 * greenfield REPL path (component C1 "collect approvals", ADR-0012 greenfield side; AC-1.5, AC-2.3).
 * It is the live counterpart of the deny-by-default decision the one-shot greenfield path uses (a
 * one-shot has no terminal to prompt): at each completed phase it <em>presents that phase's
 * deliverable to the developer</em> and then reads the developer's yes/no from the same REPL input
 * the {@link InteractiveApprover} reads, so the developer confirms (or declines) advancing through
 * requirements&rarr;design&rarr;tasks.
 *
 * <p><b>Present the deliverable before collecting the decision (AC-1.5, AC-10.1 precedent).</b>
 * AC-1.5 is the <em>confirm</em>-then-record contract ("When the developer confirms the requirements,
 * the agent shall record the approval …"); ADR-0012 generalizes the confirmation (and the
 * timestamped record) to each phase. AC-10.1 establishes the present-before-decide discipline for
 * gated operations ("present the exact operation … before executing"). This decision applies that
 * discipline to the phase gate: it writes the completed phase's deliverable — the design-doc artifact
 * that phase authored ({@link GreenfieldArtifact}: requirements/design/tasks markdown), read from the
 * {@link GreenfieldArtifactStore} — to the prompt stream <em>before</em> reading the y/N, so the
 * developer always sees what they are confirming. Only after that does the {@link ArtifactApprovalGate}
 * this decision is wrapped by record the AC-1.5 approval timestamp and (for the tasks phase) enforce
 * traceability.
 *
 * <p><b>Fail-closed (AC-1.4, mirroring {@link InteractiveApprover} / {@link NonInteractiveApprover}).</b>
 * An affirmative {@code y} / {@code yes} (via the shared {@link AffirmativeAnswer}) approves the
 * advance; anything else — a blank line, an unrecognized answer, or end-of-input ({@code null} /
 * Ctrl-D) — declines, so the session pauses at the gate awaiting approval and no source is written
 * (AC-1.4). A closed input is never a silent advance.
 *
 * <p>It is a thin, fully unit-testable seam (the construction lives in this coverage-counted class,
 * not the JaCoCo-excluded {@link Main} / {@link AgentLoopFactory}): the input source is a
 * {@link java.util.function.Supplier} of answer lines (the same one the REPL and
 * {@link InteractiveApprover} read, returning {@code null} at end-of-input) and the output stream is
 * injected, so the present-then-decide round-trip is exercised without a live terminal.
 *
 * <p>Not thread-safe: one decision serves one greenfield REPL session on a single thread.
 */
public final class InteractiveGreenfieldApproval implements ArtifactApprovalGate.ApprovalDecision {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(InteractiveGreenfieldApproval.class);

    private final java.util.function.Supplier<String> answerSource;
    private final PrintStream out;
    private final GreenfieldArtifactStore store;

    /**
     * Creates the interactive greenfield approval decision over the REPL's input/output boundary and
     * the target-repo artifact store the completed phase's deliverable is read from.
     *
     * @param answerSource the source of the developer's answer lines (the same supplier the REPL and
     *                     {@link InteractiveApprover} read; each call returns the next typed line, or
     *                     {@code null} at end-of-input); must not be {@code null}.
     * @param out          the stream the completed phase's deliverable and the y/N prompt are written
     *                     to before the decision is read (the REPL owns user-facing output,
     *                     04-apis &sect; 1.6); must not be {@code null}.
     * @param store        the target-repo artifact store the completed phase's authored deliverable is
     *                     read from to present it (AC-1.5); must not be {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public InteractiveGreenfieldApproval(
            java.util.function.Supplier<String> answerSource, PrintStream out,
            GreenfieldArtifactStore store) {
        this.answerSource = Objects.requireNonNull(answerSource, "answerSource");
        this.out = Objects.requireNonNull(out, "out");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Presents the completed phase's deliverable (AC-1.5 present-before-confirm) and returns the
     * developer's approve/decline answer read from the REPL input.
     *
     * <p>The completed phase's authored deliverable — its design-doc artifact, when it authored one
     * ({@link GreenfieldArtifact#forPhase}) — is written to the output stream before the y/N is read,
     * so the developer sees the requirements/design/tasks they are confirming. An affirmative answer
     * ({@code y} / {@code yes}, case-insensitive, surrounding whitespace ignored) approves the
     * advance; anything else — including a blank line, an unrecognized answer, or end-of-input —
     * declines (fail-closed; the session pauses awaiting approval without writing source, AC-1.4).
     *
     * @param completedPhase the phase whose deliverable was just produced; never {@code null}.
     * @return {@code true} when the developer affirmed advancing, otherwise {@code false}.
     * @throws NullPointerException if {@code completedPhase} is {@code null}.
     */
    @Override
    public boolean approve(GreenfieldPhase completedPhase) {
        Objects.requireNonNull(completedPhase, "completedPhase");
        // AC-1.5 / AC-10.1: present the completed phase's deliverable BEFORE collecting the decision.
        presentDeliverable(completedPhase);
        out.println("Approve the " + completedPhase.name().toLowerCase(java.util.Locale.ROOT)
                + " phase and continue? [y/N]");
        String answer = answerSource.get();
        if (AffirmativeAnswer.isAffirmative(answer)) {
            LOGGER.info("Greenfield {} phase confirmed by developer; advancing (AC-1.5/AC-2.3)",
                    completedPhase);
            return true;
        }
        // Fail-closed (AC-1.4): a non-affirmative answer (incl. EOF / blank) declines; no source is
        // written and the session pauses awaiting approval at the gate.
        LOGGER.info("Greenfield {} phase not confirmed by developer; pausing awaiting approval "
                + "(AC-2.3, AC-1.4)", completedPhase);
        return false;
    }

    /**
     * Writes the completed phase's authored deliverable to the output stream so the developer can read
     * it before deciding (AC-1.5). The deliverable is the phase's design-doc artifact
     * ({@link GreenfieldArtifact}) read from the target-repo store; if the phase authored no artifact
     * or none was found, a short notice is shown instead so the prompt still names the phase being
     * confirmed.
     */
    private void presentDeliverable(GreenfieldPhase completedPhase) {
        Optional<GreenfieldArtifact> artifact = GreenfieldArtifact.forPhase(completedPhase);
        Optional<String> deliverable = artifact.flatMap(a -> store.read(a.relativePath()));
        String heading = artifact.map(GreenfieldArtifact::heading).orElse(completedPhase.name());
        out.println("--- " + heading + " (completed phase deliverable) ---");
        if (deliverable.isPresent()) {
            out.println(deliverable.get());
        } else {
            out.println("(no " + heading + " deliverable artifact was found to present)");
        }
        out.println("--- end of " + heading + " deliverable ---");
    }
}
