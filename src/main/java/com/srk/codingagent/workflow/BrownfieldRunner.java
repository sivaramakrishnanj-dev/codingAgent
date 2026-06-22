package com.srk.codingagent.workflow;

import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.tool.CommandResult;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts the {@link BrownfieldDriver} to the {@link LoopOutcome}-returning seam shape the run
 * path consumes ({@link com.srk.codingagent.cli.OneShotRunner.OneShotLoop} and
 * {@link com.srk.codingagent.cli.ReplRunner.ReplLoop} are both {@code LoopOutcome run(String)}).
 * This is what lets the brownfield workflow driver be wired into the actual {@code codingagent -p}
 * / REPL run path without changing the runners' exit-code mapping &mdash; the runners keep mapping
 * a {@link LoopOutcome} to an exit code; this adapter produces the right {@link LoopOutcome} from
 * a {@link BrownfieldOutcome}.
 *
 * <p><b>Disposition &rarr; {@link LoopOutcome} mapping (honouring exit-code contract G4).</b> The
 * exit-code contract pins that the agent-process exit codes are <em>distinct from</em> the
 * build/test command's verification signal (cli-exit-codes G4): a change that does not pass tests
 * is a verification result to surface, not an agent-process failure code. So:
 * <ul>
 *   <li><b>{@link BrownfieldOutcome.Disposition#VERIFIED}.</b> The change was made and verified
 *       &mdash; the clean success. Returns the completed loop outcome unchanged (exit {@code 0},
 *       US-6).</li>
 *   <li><b>{@link BrownfieldOutcome.Disposition#VERIFY_EXHAUSTED}.</b> The agent completed the
 *       change-turn and ran verification, which did not pass within the bound. The agent process
 *       did its job; the verification signal is separate from the process exit (G4). Returns a
 *       <em>completed</em> outcome (exit {@code 0}) whose final text appends the verification
 *       failure with its relevant output (AC-20.5) so the developer sees the change was made but
 *       does not yet pass &mdash; rather than masking it as an internal-error exit.</li>
 *   <li><b>{@link BrownfieldOutcome.Disposition#NOT_VERIFIED}.</b> Either the turn surfaced an
 *       edge condition (the loop outcome is itself surfaced &mdash; pass it through so the run
 *       path's existing surfaced-reason mapping applies, e.g. {@code 5} for context exhausted),
 *       or no test command was configured (AC-20.6 &mdash; the change-turn completed; return the
 *       completed outcome unchanged, exit {@code 0}).</li>
 * </ul>
 *
 * <p>The verify-exhausted &rarr; exit-{@code 0}-with-surfaced-output choice is the spec-silent
 * disposition (the exit-code contract pins no code for "agent ran but the change did not pass
 * tests"); it is grounded in G4's separation of the verification signal from the process exit and
 * AC-20.5's "surface with the relevant output". See the driver's handoff Discussion note.
 */
public final class BrownfieldRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrownfieldRunner.class);

    private final BrownfieldDriver driver;

    /**
     * Creates a runner over a composed brownfield driver.
     *
     * @param driver the brownfield workflow driver; must not be {@code null}.
     * @throws NullPointerException if {@code driver} is {@code null}.
     */
    public BrownfieldRunner(BrownfieldDriver driver) {
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    /**
     * Runs one brownfield understand&rarr;change&rarr;verify cycle for the prompt and maps the
     * result to the {@link LoopOutcome} the run path's exit-code mapper consumes. This is the
     * {@code LoopOutcome run(String)} seam {@link com.srk.codingagent.cli.OneShotRunner} and
     * {@link com.srk.codingagent.cli.ReplRunner} drive.
     *
     * @param prompt the developer's brownfield request; non-blank.
     * @return the loop outcome the run path maps to an exit code; never {@code null}.
     */
    public LoopOutcome run(String prompt) {
        BrownfieldOutcome outcome = driver.run(prompt);
        return switch (outcome.disposition()) {
            case VERIFIED -> outcome.loopOutcome();
            case NOT_VERIFIED -> outcome.loopOutcome();
            case VERIFY_EXHAUSTED -> verifyExhaustedOutcome(outcome);
        };
    }

    /**
     * Folds a verify-exhausted disposition into a completed loop outcome whose final text carries
     * the change-turn's answer plus the verification failure and its relevant output (AC-20.5).
     * The process exit stays {@code 0} per G4 (the verification signal is not the process exit
     * code), but the developer sees plainly that the change did not pass tests.
     */
    private LoopOutcome verifyExhaustedOutcome(BrownfieldOutcome outcome) {
        VerifyOutcome verify = outcome.verifyOutcome();
        LOGGER.warn("Brownfield change did not verify within {} attempt(s); surfacing the failure "
                + "output to the developer (AC-20.5)", verify.iterations());
        String changeText = outcome.loopOutcome().finalTextIfPresent().orElse("");
        String report = changeText.isBlank()
                ? verifyFailureReport(verify)
                : changeText + "\n\n" + verifyFailureReport(verify);
        return LoopOutcome.completed(report);
    }

    /** The user-facing verification-failure report carrying the last attempt's relevant output. */
    private static String verifyFailureReport(VerifyOutcome verify) {
        CommandResult failure = verify.result();
        StringBuilder report = new StringBuilder()
                .append("The change was made but did not pass verification after ")
                .append(verify.iterations())
                .append(" attempt(s). Last failure (exit ")
                .append(failure.exitCode())
                .append("):");
        appendIfPresent(report, failure.stdout());
        appendIfPresent(report, failure.stderr());
        return report.toString();
    }

    private static void appendIfPresent(StringBuilder report, String output) {
        if (output != null && !output.isBlank()) {
            report.append('\n').append(output);
        }
    }
}
