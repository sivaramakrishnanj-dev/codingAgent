package com.srk.codingagent.workflow;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.CommandRunner;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.loop.RemedyAttempt;
import com.srk.codingagent.loop.VerifyLoop;
import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.CommandResult;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The brownfield workflow driver (component C3, ADR-0012 brownfield side): the
 * "understand &rarr; change" playbook orchestrated over the shared engine (the
 * {@link AgentLoop}, the file/search tools, and the {@link VerifyLoop}). It lets a developer
 * point the agent at an existing repository, have it explore and understand the code, make a
 * requested change, and verify that change via the configured build/test commands (US-4, US-5).
 *
 * <p><b>Orchestration over the loop, not a separate engine (ADR-0012).</b> The brownfield
 * understand&rarr;change arc is largely emergent model behaviour: primed by the
 * {@link BrownfieldPlaybook} system prompt (explore-before-edit AC-4.1/AC-5.1, report-not-
 * fabricate AC-4.3, ask-when-ambiguous AC-5.4, verify-after-change AC-5.3), the model drives
 * explore&rarr;change itself through the agent loop's tool-use cycle within a single
 * {@link AgentLoop#run(String)} turn (the loop already cycles {@code tool_use} &harr;
 * {@code end_turn} internally). The driver adds exactly one explicit piece of orchestration on
 * top: after the change-turn completes, it invokes the configured {@link VerifyLoop} on the test
 * command and feeds the outcome back (AC-5.3), bounded by {@code NFR-VERIFY-MAX-ITERATIONS}. It
 * is not a rigid hard-coded state machine that fights the model's agency.
 *
 * <p><b>The verify wiring T-1.4 deferred (AC-20.3).</b> The {@link RemedyAttempt} the driver
 * supplies to the verify loop is the load-bearing seam the verify loop deferred to the workflow
 * driver: on a failing verification attempt it drives <em>another</em> agent-loop turn
 * (the {@link LoopTurn} seam) with a prompt built from the failure output, so the model reads
 * the failure, fixes the cause, and the verify loop retries. The verify loop itself bounds the
 * number of attempts; the remedy is invoked only between failing attempts (never after the last
 * one), so the fix-and-retry cycle is bounded by {@code verifyMaxIterations}.
 *
 * <p><b>Single-run-with-tools (the multi-turn-continuation choice).</b> The brownfield arc fits
 * in one {@link AgentLoop#run} call because the loop already loops tool-use until
 * {@code end_turn}: the model explores via many tool calls then makes the change then ends its
 * turn, all within one run. The verify step is a driver-invoked {@link VerifyLoop} after the
 * turn returns, and the remedy is a fresh {@code run(prompt)} turn fed the failure. This needs
 * no {@link AgentLoop} change. (The recurring question of a continued-conversation entry that
 * accepts a prior transcript is a separate seam, surfaced for design discussion, not built here.)
 *
 * <p><b>Seams (testability).</b> The driver's orchestration is exercised in isolation through
 * two injected seams: the {@link LoopTurn} (the {@link AgentLoop#run(String)} shape, so the
 * understand&rarr;change turn and the remedy turn are scripted without a live model) and the
 * {@link VerifierFactory} (which, given the driver's remedy, yields a {@link Verifier} &mdash;
 * the {@link VerifyLoop#verify()} shape &mdash; so verify outcomes are scripted without shelling
 * out to a real build). Production wiring composes the real {@link AgentLoop} and a real
 * {@link VerifyLoop} via {@link #overConfig(LoopTurn, CommandExecutor, ResolvedConfig)}.
 *
 * <p>Not thread-safe: one driver runs one brownfield session on a single thread (the C2
 * invariant the loop inherits &mdash; one in-flight model call per conversation).
 */
public final class BrownfieldDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrownfieldDriver.class);

    private final LoopTurn loop;
    private final VerifierFactory verifierFactory;

    /**
     * Creates a brownfield driver over its two injected seams.
     *
     * @param loop            the agent-loop turn seam ({@link AgentLoop#run(String)} shape) the
     *                        driver runs the understand&rarr;change turn and each remedy turn
     *                        through; must not be {@code null}.
     * @param verifierFactory builds the verify step from the driver's {@link RemedyAttempt}: in
     *                        production a {@link VerifyLoop} on the configured test command, in
     *                        tests a scripted verifier. The factory receives the remedy so the
     *                        driver's failure-feedback wiring (AC-20.3) is the one the verify
     *                        loop invokes; must not be {@code null}.
     * @throws NullPointerException if either argument is {@code null}.
     */
    public BrownfieldDriver(LoopTurn loop, VerifierFactory verifierFactory) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.verifierFactory = Objects.requireNonNull(verifierFactory, "verifierFactory");
    }

    /**
     * Composes the production brownfield driver: the understand&rarr;change turn (and each
     * remedy turn) runs through the given {@link AgentLoop} (which must have been built with the
     * {@link BrownfieldPlaybook} system prompt), and the verify step is a {@link VerifyLoop}
     * wired to the configured test command via {@link VerifyLoop#forConfig}, using the driver's
     * remedy seam so a failing verification drives another loop turn (AC-20.3).
     *
     * @param loop     the agent-loop turn seam (typically {@code agentLoop::run}); must not be
     *                 {@code null}.
     * @param executor the command executor rooted at the workspace, for the verify loop's test
     *                 command; must not be {@code null}.
     * @param config   the resolved configuration supplying the test command, timeout, and the
     *                 verify-iteration bound; must not be {@code null}.
     * @return a composed brownfield driver ready to run one session; never {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public static BrownfieldDriver overConfig(
            LoopTurn loop, CommandExecutor executor, ResolvedConfig config) {
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(config, "config");
        VerifierFactory factory =
                remedy -> VerifyLoop.forConfig(executor, config, remedy)::verify;
        return new BrownfieldDriver(loop, factory);
    }

    /**
     * Runs one brownfield understand&rarr;change&rarr;verify cycle from the developer's request.
     *
     * <p>The flow:
     * <ol>
     *   <li>Run the understand&rarr;change turn through the loop (the model, primed by the
     *       playbook, explores then makes the change then ends its turn within this one run).</li>
     *   <li>If the turn surfaced an edge condition rather than completing, there is no completed
     *       change to verify: return {@link BrownfieldOutcome.Disposition#NOT_VERIFIED} carrying
     *       the surfaced loop outcome.</li>
     *   <li>If the turn completed, invoke the verify step (AC-5.3) with a remedy that drives
     *       another loop turn on each failing attempt (AC-20.3), bounded by the verify loop's own
     *       iteration bound. Map the verify outcome: {@code VERIFIED} &rarr; the clean success;
     *       {@code EXHAUSTED} &rarr; surface with the failure output (AC-20.5);
     *       {@code NO_TEST_COMMAND} &rarr; not-verified (AC-20.6).</li>
     * </ol>
     *
     * @param request the developer's brownfield request (the change to make); must not be
     *                {@code null} or blank.
     * @return the terminal {@link BrownfieldOutcome}; never {@code null}.
     * @throws NullPointerException     if {@code request} is {@code null}.
     * @throws IllegalArgumentException if {@code request} is blank.
     */
    public BrownfieldOutcome run(String request) {
        if (Objects.requireNonNull(request, "request").isBlank()) {
            throw new IllegalArgumentException("request must be non-blank");
        }
        LOGGER.info("Brownfield session started: understand->change->verify");

        // The understand->change turn: the model explores (AC-4.1/AC-5.1) then makes the change,
        // driven by the playbook within this single run.
        LoopOutcome changeOutcome = loop.run(request);

        if (!changeOutcome.completed()) {
            // The turn surfaced an edge condition (e.g. context window exceeded): there is no
            // completed change to verify. Surface the loop outcome for the run path to map; the
            // verify step does not run.
            LOGGER.warn("Understand->change turn surfaced ({}) before completing; not verifying",
                    changeOutcome.stopReason());
            return BrownfieldOutcome.turnSurfaced(changeOutcome);
        }

        // AC-5.3: the change is applied; verify it via the configured build/test command. The
        // remedy feeds a failing run back into another loop turn (AC-20.3); the verify loop bounds
        // the retries (NFR-VERIFY-MAX-ITERATIONS).
        Verifier verifier = verifierFactory.create(remedyFeedingFailureBack());
        VerifyOutcome verifyOutcome = Objects.requireNonNull(
                verifier.verify(), "verifier returned a null outcome");
        return disposition(changeOutcome, verifyOutcome);
    }

    /**
     * The remedy seam the verify loop invokes between failing attempts (AC-20.3): it drives
     * another agent-loop turn with a prompt built from the failing command's output, so the
     * model reads the failure, fixes the cause, and the verify loop's next attempt re-runs the
     * test. The verify loop bounds how many times this runs.
     */
    private RemedyAttempt remedyFeedingFailureBack() {
        return failure -> {
            String prompt = RemedyPrompt.forFailure(failure);
            LOGGER.info("Verification failed (exit {}); driving a remedy turn", failure.exitCode());
            loop.run(prompt);
        };
    }

    private static BrownfieldOutcome disposition(LoopOutcome change, VerifyOutcome verify) {
        return switch (verify.kind()) {
            case VERIFIED -> {
                LOGGER.info("Brownfield change verified on attempt {} (AC-5.3)", verify.iterations());
                yield BrownfieldOutcome.verified(change, verify);
            }
            case EXHAUSTED -> {
                LOGGER.warn("Brownfield change did not verify within {} attempt(s); surfacing (AC-20.5)",
                        verify.iterations());
                yield BrownfieldOutcome.verifyExhausted(change, verify);
            }
            case NO_TEST_COMMAND -> {
                LOGGER.info("No test command configured; change made but not verified (AC-20.6)");
                yield BrownfieldOutcome.noTestCommand(change, verify);
            }
        };
    }

    /**
     * The agent-loop turn seam: the {@link AgentLoop#run(String)} shape, isolated so the
     * driver's orchestration (the understand&rarr;change turn and each remedy turn) is testable
     * with a scripted loop, mirroring {@link com.srk.codingagent.cli.OneShotRunner.OneShotLoop}
     * and {@link com.srk.codingagent.cli.ReplRunner.ReplLoop}.
     */
    @FunctionalInterface
    public interface LoopTurn {

        /**
         * Runs the agent loop for one prompt to its terminal {@link LoopOutcome}.
         *
         * @param prompt the prompt for this turn (the developer request, or a remedy prompt);
         *               non-blank.
         * @return the terminal outcome; never {@code null}.
         */
        LoopOutcome run(String prompt);
    }

    /**
     * The verify step the driver invokes after the change-turn completes (AC-5.3): the
     * {@link VerifyLoop#verify()} shape, isolated so the driver's branch-on-outcome logic is
     * testable with scripted {@link VerifyOutcome}s without shelling out to a real build.
     */
    @FunctionalInterface
    public interface Verifier {

        /**
         * Runs the bounded verify cycle for the change just applied.
         *
         * @return the terminal {@link VerifyOutcome}; never {@code null}.
         */
        VerifyOutcome verify();
    }

    /**
     * Builds the {@link Verifier} from the driver's {@link RemedyAttempt}. The driver constructs
     * its remedy (which drives a loop turn on a failing attempt, AC-20.3) and hands it here so
     * the verify loop the factory builds invokes <em>that</em> remedy between attempts. In
     * production the factory is {@code remedy -> VerifyLoop.forConfig(executor, config, remedy)::verify}
     * (see {@link #overConfig}); in tests it is a scripted verifier that may also invoke the
     * remedy to assert the failure-feedback wiring.
     */
    @FunctionalInterface
    public interface VerifierFactory {

        /**
         * Builds a verifier wired with the driver's remedy.
         *
         * @param remedy the driver's remedy seam, invoked by the resulting verifier between
         *               failing attempts (AC-20.3); never {@code null}.
         * @return the verify step; never {@code null}.
         */
        Verifier create(RemedyAttempt remedy);
    }

    /**
     * Builds the remedy prompt fed back to the model when a verification attempt fails (AC-20.3):
     * the failing command and its captured output, with an instruction to fix the cause. Kept as
     * a small tested artifact so the suite can assert the failure output is actually carried into
     * the remedy turn (rather than a fixed canned string that ignores the failure).
     *
     * <p>Uses {@link CommandRunner}-produced {@link CommandResult}s; the failing run's
     * {@code stdout}/{@code stderr} are the relevant output to reason over (AC-20.5).
     */
    static final class RemedyPrompt {

        private RemedyPrompt() {
            // Holder for the prompt builder; not instantiable.
        }

        static String forFailure(CommandResult failure) {
            Objects.requireNonNull(failure, "failure");
            StringBuilder prompt = new StringBuilder()
                    .append("The verification command failed. Read the output below, fix the "
                            + "cause in the code, and the command will be run again.\n\n")
                    .append("Command: ").append(failure.command()).append('\n')
                    .append("Exit code: ").append(failure.exitCode()).append('\n');
            appendIfPresent(prompt, "stdout", failure.stdout());
            appendIfPresent(prompt, "stderr", failure.stderr());
            return prompt.toString();
        }

        private static void appendIfPresent(StringBuilder prompt, String label, String output) {
            if (output != null && !output.isBlank()) {
                prompt.append('\n').append(label).append(":\n").append(output).append('\n');
            }
        }
    }
}
