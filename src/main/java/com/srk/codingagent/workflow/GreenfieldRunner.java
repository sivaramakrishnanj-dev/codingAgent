package com.srk.codingagent.workflow;

import com.srk.codingagent.loop.LoopOutcome;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts the {@link GreenfieldDriver} to the {@link LoopOutcome}-returning seam shape the run path
 * consumes ({@link com.srk.codingagent.cli.OneShotRunner.OneShotLoop} and
 * {@link com.srk.codingagent.cli.ReplRunner.ReplLoop} are both {@code LoopOutcome run(String)}),
 * the same role {@link BrownfieldRunner} plays for the brownfield driver. This is what lets the
 * greenfield workflow driver be wired into the actual {@code codingagent --mode greenfield} run
 * path without changing the runners' exit-code mapping &mdash; the runners keep mapping a
 * {@link LoopOutcome} to an exit code; this adapter produces the right {@link LoopOutcome} from a
 * {@link GreenfieldOutcome}.
 *
 * <p><b>Disposition &rarr; {@link LoopOutcome} mapping.</b>
 * <ul>
 *   <li><b>{@link GreenfieldOutcome.Disposition#COMPLETED}.</b> Every phase ran and every approval
 *       gate passed (the machine reached implementation). Returns the implementation phase's
 *       completed loop outcome unchanged (exit {@code 0}, US-6).</li>
 *   <li><b>{@link GreenfieldOutcome.Disposition#AWAITING_APPROVAL}.</b> A phase completed and its
 *       deliverable was presented, but the developer did not approve advancing (ADR-0012, AC-2.3).
 *       The agent did its job for this phase and wrote no source (AC-1.4); the missing approval is
 *       not an agent-process failure. Returns a <em>completed</em> outcome (exit {@code 0}) whose
 *       final text appends a note that the session is paused awaiting approval at this phase &mdash;
 *       so the developer sees the gate &mdash; rather than masking it as an error exit.</li>
 *   <li><b>{@link GreenfieldOutcome.Disposition#TURN_SURFACED}.</b> A phase's turn surfaced an edge
 *       condition (e.g. context exhausted). Passes the surfaced loop outcome through so the run
 *       path's existing surfaced-reason mapping applies (e.g. {@code 5} for context exhausted).</li>
 * </ul>
 */
public final class GreenfieldRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(GreenfieldRunner.class);

    private final GreenfieldDriver driver;

    /**
     * Creates a runner over a composed greenfield driver.
     *
     * @param driver the greenfield workflow driver; must not be {@code null}.
     * @throws NullPointerException if {@code driver} is {@code null}.
     */
    public GreenfieldRunner(GreenfieldDriver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    /**
     * Runs one greenfield requirements&rarr;design&rarr;tasks&rarr;implement session for the prompt
     * and maps the result to the {@link LoopOutcome} the run path's exit-code mapper consumes. This
     * is the {@code LoopOutcome run(String)} seam {@link com.srk.codingagent.cli.OneShotRunner} and
     * {@link com.srk.codingagent.cli.ReplRunner} drive.
     *
     * @param prompt the developer's initial use-case; non-blank.
     * @return the loop outcome the run path maps to an exit code; never {@code null}.
     */
    public LoopOutcome run(String prompt) {
        GreenfieldOutcome outcome = driver.run(prompt);
        return switch (outcome.disposition()) {
            case COMPLETED -> outcome.loopOutcome();
            case TURN_SURFACED -> outcome.loopOutcome();
            case AWAITING_APPROVAL -> awaitingApprovalOutcome(outcome);
        };
    }

    /**
     * Folds an awaiting-approval disposition into a completed loop outcome whose final text carries
     * the phase's answer plus a note that the session is paused at this phase's approval gate
     * (ADR-0012, AC-2.3). The process exit stays {@code 0} (the missing approval is not a process
     * failure), but the developer sees plainly that approval is required to advance.
     */
    private LoopOutcome awaitingApprovalOutcome(GreenfieldOutcome outcome) {
        GreenfieldPhase phase = outcome.phase();
        LOGGER.info("Greenfield session paused at the {} approval gate; surfacing to the developer "
                + "(AC-2.3)", phase);
        String phaseText = outcome.loopOutcome().finalTextIfPresent().orElse("");
        String note = "The " + phase.name().toLowerCase()
                + " phase is complete and awaiting your approval before the next phase begins.";
        String report = phaseText.isBlank() ? note : phaseText + "\n\n" + note;
        return LoopOutcome.completed(report);
    }
}
