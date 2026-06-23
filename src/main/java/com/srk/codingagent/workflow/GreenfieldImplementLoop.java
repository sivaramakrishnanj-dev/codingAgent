package com.srk.codingagent.workflow;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.loop.RemedyAttempt;
import com.srk.codingagent.loop.RemedyPrompt;
import com.srk.codingagent.loop.VerifyLoop;
import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.CommandResult;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The greenfield <b>implement-one-task-at-a-time</b> loop (component C3 over C2, ADR-0012 implement
 * clause; US-3, AC-3.1/3.2/3.3/3.4): the orchestration the IMPLEMENT phase &mdash; the terminal phase
 * of the {@link GreenfieldPhase} machine, reached only after the design and task breakdown are
 * approved (AC-2.3) &mdash; drives once it is entered. It reads the approved task breakdown, then for
 * each task in breakdown order (AC-3.1): drives an agent-loop turn to implement that one task, verifies
 * it via the configured build/test command (AC-3.2) <em>reusing the T-1.4 {@link VerifyLoop}</em>, and
 * on a verifying task marks it complete in the task-breakdown artifact (AC-3.3) before starting the
 * next. If a task fails verification within the bound (NFR-VERIFY-MAX-ITERATIONS), the loop stops and
 * surfaces rather than advancing to the next task (AC-3.4).
 *
 * <p><b>Orchestration over the loop, reusing the proven verify seam (the explicit T-3.3 directive).</b>
 * This is not a separate engine (ADR-0012): each task's implementation is one {@link AgentLoop} turn
 * (the {@link LoopTurn} seam, the same {@link AgentLoop#run(String)} shape {@link BrownfieldDriver}
 * uses), and each task's verification is the same {@link VerifyLoop} the {@link BrownfieldDriver}
 * reuses &mdash; built via {@link VerifyLoop#forConfig} with a {@link RemedyAttempt} that drives
 * <em>another</em> agent-loop turn from the failure output (AC-20.3). The {@link #overConfig} factory
 * builds exactly the {@code remedy -> VerifyLoop.forConfig(executor, config, remedy)::verify} verifier
 * {@link BrownfieldDriver#overConfig} builds; the only difference is that this loop runs that
 * implement&rarr;verify&rarr;mark-complete cycle once <em>per task</em>, in order, instead of once for
 * a single brownfield change. Verification is reused, never reimplemented.
 *
 * <p><b>Marking complete reuses the T-3.2 artifact store (AC-3.3).</b> When a task verifies, its
 * completion is recorded by appending a completion line to the task-breakdown artifact through the
 * same {@link GreenfieldArtifactStore} T-3.2 authored the artifact with &mdash; not a parallel writer.
 * The append happens <em>before</em> the next task's turn begins (AC-3.3 "mark it complete … before
 * starting the next").
 *
 * <p><b>The IMPLEMENT-phase loop turn (how it plugs into the driver).</b> The
 * {@link GreenfieldDriver} runs the terminal phase as a {@link GreenfieldDriver.LoopTurn}; this loop
 * is what that turn does. {@link #asLoopTurn()} adapts the rich {@link ImplementOutcome} this loop
 * produces to the {@link LoopOutcome} the phase seam returns: an all-verified run completes cleanly
 * (the driver maps it to a {@code COMPLETED} greenfield outcome, exit 0); a verify-exhausted run
 * surfaces the failing task and its output in the completed text (AC-3.4/AC-20.5) &mdash; the same
 * "verification signal distinct from the agent-process exit" stance {@link BrownfieldRunner} takes for
 * the brownfield verify-exhaustion (exit-code contract G4), so the developer sees the stuck task
 * rather than a masked internal error.
 *
 * <p>Not thread-safe: one implement loop runs one greenfield session's implementation phase on a
 * single thread (the C2 invariant the loop inherits &mdash; one in-flight model call per conversation).
 */
public final class GreenfieldImplementLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(GreenfieldImplementLoop.class);

    /** The greenfield task-breakdown artifact the approved tasks are read from / marked complete in. */
    static final GreenfieldArtifact TASKS_ARTIFACT = GreenfieldArtifact.TASKS;

    private final LoopTurn loop;
    private final VerifierFactory verifierFactory;
    private final GreenfieldArtifactStore store;

    /**
     * Creates an implement loop over its injected seams and the target-repo artifact store.
     *
     * @param loop            the agent-loop turn seam ({@link AgentLoop#run(String)} shape) each
     *                        task's implementation turn (and each remedy turn) runs through; must not
     *                        be {@code null}.
     * @param verifierFactory builds the per-task verify step from the loop's {@link RemedyAttempt}:
     *                        in production a {@link VerifyLoop} on the configured test command, in
     *                        tests a scripted verifier. The factory receives the remedy so the loop's
     *                        failure-feedback wiring (AC-20.3) is the one the verify loop invokes;
     *                        must not be {@code null}.
     * @param store           the target-repo artifact store the approved breakdown is read from and
     *                        each completed task is marked complete in (AC-3.3, reusing T-3.2); must
     *                        not be {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public GreenfieldImplementLoop(
            LoopTurn loop, VerifierFactory verifierFactory, GreenfieldArtifactStore store) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.verifierFactory = Objects.requireNonNull(verifierFactory, "verifierFactory");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Composes the production implement loop: each task's implementation turn (and each remedy turn)
     * runs through the given {@link AgentLoop} (the IMPLEMENT-phase loop, which carries the full
     * source-write toolset, AC-2.3), and each task's verify step is a {@link VerifyLoop} wired to the
     * configured test command via {@link VerifyLoop#forConfig}, using the loop's remedy seam so a
     * failing verification drives another loop turn (AC-20.3) &mdash; the SAME verifier composition
     * {@link BrownfieldDriver#overConfig} builds (verification reused, not reimplemented).
     *
     * @param loop     the agent-loop turn seam (typically {@code agentLoop::run}); must not be
     *                 {@code null}.
     * @param executor the command executor rooted at the target repo, for the verify loop's test
     *                 command; must not be {@code null}.
     * @param config   the resolved configuration supplying the test command, timeout, and the
     *                 verify-iteration bound (NFR-VERIFY-MAX-ITERATIONS); must not be {@code null}.
     * @param store    the target-repo artifact store (AC-3.3, reusing T-3.2); must not be
     *                 {@code null}.
     * @return a composed implement loop ready to run the IMPLEMENT phase; never {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public static GreenfieldImplementLoop overConfig(
            LoopTurn loop, CommandExecutor executor, ResolvedConfig config,
            GreenfieldArtifactStore store) {
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(store, "store");
        VerifierFactory factory = remedy -> VerifyLoop.forConfig(executor, config, remedy)::verify;
        return new GreenfieldImplementLoop(loop, factory, store);
    }

    /**
     * Runs the implementation phase: implement the approved tasks one at a time, verifying each before
     * the next, marking each verified task complete before starting the next, and stopping if a task
     * fails verification within the bound.
     *
     * <p>The flow:
     * <ol>
     *   <li>Read the approved task-breakdown artifact and enumerate its tasks in breakdown order
     *       (AC-3.1). An empty / task-less breakdown yields {@link ImplementOutcome#noTasks()}.</li>
     *   <li>For each task, in order: drive an agent-loop turn to implement that one task (AC-3.1), then
     *       verify it via the {@link VerifyLoop} reuse (AC-3.2), with a remedy that drives another loop
     *       turn on each failing attempt (AC-20.3), bounded by NFR-VERIFY-MAX-ITERATIONS.</li>
     *   <li>On a task that verifies ({@link VerifyOutcome.Kind#VERIFIED}): mark it complete in the
     *       task-breakdown artifact (AC-3.3) and proceed to the next task.</li>
     *   <li>On a task that does not verify within the bound ({@link VerifyOutcome.Kind#EXHAUSTED}):
     *       stop and surface, carrying the failing task id and the last failure's output (AC-3.4,
     *       AC-20.5) &mdash; do <em>not</em> advance to the next task.</li>
     *   <li>If no test command is configured ({@link VerifyOutcome.Kind#NO_TEST_COMMAND}): report it
     *       (AC-20.6); a task cannot be verified before the next on an unverifiable basis.</li>
     * </ol>
     *
     * @param prompt the prompt that opens the implementation phase (the phase-advance prompt the
     *               driver supplies when it enters IMPLEMENT, naming the per-task implementation it
     *               primes); non-blank. It is used as the priming context for the first task's turn.
     * @return the terminal {@link ImplementOutcome}; never {@code null}.
     * @throws NullPointerException     if {@code prompt} is {@code null}.
     * @throws IllegalArgumentException if {@code prompt} is blank.
     */
    public ImplementOutcome run(String prompt) {
        if (Objects.requireNonNull(prompt, "prompt").isBlank()) {
            throw new IllegalArgumentException("prompt must be non-blank");
        }

        List<String> tasks = readTasksInOrder();
        if (tasks.isEmpty()) {
            LOGGER.warn("Greenfield implement phase entered but the approved breakdown ({}) has no "
                    + "recognizable task to implement", TASKS_ARTIFACT.relativePath());
            return ImplementOutcome.noTasks();
        }

        LOGGER.info("Greenfield implement phase: {} task(s) to implement one at a time, verifying "
                + "each before the next (AC-3.1/3.2/3.3)", tasks.size());

        List<String> completed = new ArrayList<>();
        VerifyOutcome lastVerified = null;
        for (String taskId : tasks) {
            VerifyOutcome verify = implementAndVerify(taskId, prompt);
            switch (verify.kind()) {
                case VERIFIED -> {
                    markComplete(taskId, verify);
                    completed.add(taskId);
                    lastVerified = verify;
                }
                case EXHAUSTED -> {
                    LOGGER.warn("Greenfield task {} did not verify within {} attempt(s); stopping "
                            + "without advancing to the next task (AC-3.4)", taskId,
                            verify.iterations());
                    return ImplementOutcome.verifyExhausted(completed, taskId, verify);
                }
                case NO_TEST_COMMAND -> {
                    LOGGER.info("No test command configured; the implement loop cannot verify task {} "
                            + "before the next (AC-20.6)", taskId);
                    return ImplementOutcome.noTestCommand(verify);
                }
            }
        }

        LOGGER.info("Greenfield implement phase complete: {} task(s) implemented, verified, and "
                + "marked complete in order (AC-3.1/3.3)", completed.size());
        return ImplementOutcome.allVerified(completed, lastVerified);
    }

    /**
     * Adapts this implement loop to the {@link GreenfieldDriver.LoopTurn} the driver runs the terminal
     * IMPLEMENT phase through: it runs the loop and maps the {@link ImplementOutcome} to the
     * {@link LoopOutcome} the phase seam returns. An all-verified (or no-tasks / no-test-command) run
     * completes cleanly so the driver reaches its {@code COMPLETED} greenfield outcome; a
     * verify-exhausted run completes with the failing task and its output surfaced in the text
     * (AC-3.4/AC-20.5) &mdash; the verification signal is distinct from the agent-process exit (G4), so
     * the developer sees the stuck task rather than a masked error.
     *
     * @return the IMPLEMENT-phase loop turn for the {@link GreenfieldDriver.PhaseLoopFactory}; never
     *         {@code null}.
     */
    public GreenfieldDriver.LoopTurn asLoopTurn() {
        return prompt -> LoopOutcome.completed(report(run(prompt)));
    }

    private List<String> readTasksInOrder() {
        Optional<String> breakdown = store.read(TASKS_ARTIFACT.relativePath());
        if (breakdown.isEmpty()) {
            LOGGER.warn("Greenfield implement phase entered but no task-breakdown artifact exists at "
                    + "{}", TASKS_ARTIFACT.relativePath());
            return List.of();
        }
        return TaskTraceability.tasksInOrder(breakdown.get());
    }

    /**
     * Implements one task (one agent-loop turn) then verifies it via the reused {@link VerifyLoop},
     * with a remedy that drives another loop turn on each failing attempt (AC-20.3). The verify loop
     * bounds the fix-and-retry cycle (NFR-VERIFY-MAX-ITERATIONS).
     */
    private VerifyOutcome implementAndVerify(String taskId, String basePrompt) {
        LOGGER.info("Implementing greenfield task {} (one task at a time, AC-3.1)", taskId);
        loop.run(taskPrompt(taskId, basePrompt));
        Verifier verifier = verifierFactory.create(remedyFeedingFailureBack());
        return Objects.requireNonNull(verifier.verify(), "verifier returned a null outcome");
    }

    /**
     * The remedy seam the verify loop invokes between failing attempts (AC-20.3): it drives another
     * agent-loop turn with a prompt built from the failing command's output, so the model reads the
     * failure, fixes the cause, and the verify loop's next attempt re-runs the test. Mirrors
     * {@link BrownfieldDriver}'s {@code remedyFeedingFailureBack}; the verify loop bounds how many
     * times this runs.
     */
    private RemedyAttempt remedyFeedingFailureBack() {
        return failure -> {
            String prompt = RemedyPrompt.forFailure(failure);
            LOGGER.info("Task verification failed (exit {}); driving a remedy turn", failure.exitCode());
            loop.run(prompt);
        };
    }

    /**
     * Marks a verified task complete in the task-breakdown artifact (AC-3.3), reusing the T-3.2
     * {@link GreenfieldArtifactStore#appendLine} writer. The completion line records the task id so a
     * reader can see which tasks the implement loop verified-and-completed.
     */
    private void markComplete(String taskId, VerifyOutcome verify) {
        String line = CompletionStamp.line(taskId, verify);
        store.appendLine(TASKS_ARTIFACT.relativePath(), line);
        LOGGER.info("Marked greenfield task {} complete in {} after verifying on attempt {} (AC-3.3)",
                taskId, TASKS_ARTIFACT.relativePath(), verify.iterations());
    }

    /**
     * Builds the IMPLEMENT-phase prompt for one task: the phase priming context plus the specific task
     * id to implement now, so the model implements exactly one task this turn (AC-3.1). Kept small and
     * tested so the suite can assert each task's turn names the task it is to implement.
     */
    static String taskPrompt(String taskId, String basePrompt) {
        return basePrompt + "\n\nImplement task " + taskId + " now. Implement only this one task, "
                + "then stop so it can be verified before the next task begins.";
    }

    /** Renders the user-facing report for the implement-phase loop outcome (AC-3.4 surfacing). */
    private static String report(ImplementOutcome outcome) {
        return switch (outcome.disposition()) {
            case ALL_VERIFIED -> "Implemented and verified " + outcome.completedTasks().size()
                    + " task(s) one at a time, marking each complete in order: "
                    + String.join(", ", outcome.completedTasks()) + ".";
            case VERIFY_EXHAUSTED -> verifyExhaustedReport(outcome);
            case NO_TEST_COMMAND -> "No test command is configured, so tasks cannot be verified one "
                    + "at a time before the next; configure a test command to run the implement loop "
                    + "(AC-20.6).";
            case NO_TASKS -> "The approved task breakdown has no task to implement.";
        };
    }

    private static String verifyExhaustedReport(ImplementOutcome outcome) {
        VerifyOutcome verify = outcome.verifyOutcome();
        CommandResult failure = verify.result();
        StringBuilder report = new StringBuilder();
        if (!outcome.completedTasks().isEmpty()) {
            report.append("Implemented and verified ").append(outcome.completedTasks().size())
                    .append(" task(s) (").append(String.join(", ", outcome.completedTasks()))
                    .append(") before stopping.\n\n");
        }
        report.append("Task ").append(outcome.stoppedTask())
                .append(" did not pass verification after ").append(verify.iterations())
                .append(" attempt(s); stopping rather than continuing to the next task (AC-3.4). "
                        + "Last failure (exit ").append(failure.exitCode()).append("):");
        appendIfPresent(report, failure.stdout());
        appendIfPresent(report, failure.stderr());
        return report.toString();
    }

    private static void appendIfPresent(StringBuilder report, String output) {
        if (output != null && !output.isBlank()) {
            report.append('\n').append(output);
        }
    }

    /**
     * The agent-loop turn seam: the {@link AgentLoop#run(String)} shape, isolated so the implement
     * loop's per-task orchestration (each task's implementation turn and each remedy turn) is testable
     * with a scripted loop, mirroring {@link BrownfieldDriver.LoopTurn} and
     * {@link GreenfieldDriver.LoopTurn}.
     */
    @FunctionalInterface
    public interface LoopTurn {

        /**
         * Runs the agent loop for one prompt to its terminal {@link LoopOutcome}.
         *
         * @param prompt the prompt for this turn (a task-implementation prompt, or a remedy prompt);
         *               non-blank.
         * @return the terminal outcome; never {@code null}.
         */
        LoopOutcome run(String prompt);
    }

    /**
     * The per-task verify step the loop invokes after each task's implementation turn (AC-3.2): the
     * {@link VerifyLoop#verify()} shape, isolated so the loop's per-task branch-on-outcome logic is
     * testable with scripted {@link VerifyOutcome}s without shelling out to a real build. Mirrors
     * {@link BrownfieldDriver.Verifier}.
     */
    @FunctionalInterface
    public interface Verifier {

        /**
         * Runs the bounded verify cycle for the task just implemented.
         *
         * @return the terminal {@link VerifyOutcome}; never {@code null}.
         */
        VerifyOutcome verify();
    }

    /**
     * Builds the {@link Verifier} from the loop's {@link RemedyAttempt}. The loop constructs its remedy
     * (which drives a loop turn on a failing attempt, AC-20.3) and hands it here so the verify loop the
     * factory builds invokes <em>that</em> remedy between attempts. In production the factory is
     * {@code remedy -> VerifyLoop.forConfig(executor, config, remedy)::verify} (see {@link #overConfig};
     * the SAME verifier {@link BrownfieldDriver.VerifierFactory} builds); in tests it is a scripted
     * verifier. Mirrors {@link BrownfieldDriver.VerifierFactory}.
     */
    @FunctionalInterface
    public interface VerifierFactory {

        /**
         * Builds a verifier wired with the loop's remedy.
         *
         * @param remedy the loop's remedy seam, invoked by the resulting verifier between failing
         *               attempts (AC-20.3); never {@code null}.
         * @return the verify step; never {@code null}.
         */
        Verifier create(RemedyAttempt remedy);
    }

    /**
     * Builds the completion line appended to the task-breakdown artifact when a task verifies (AC-3.3):
     * a stable, greppable marker naming the task id and the attempt it verified on. Kept as a small
     * tested artifact so the suite can assert the marker carries the task id (rather than an opaque
     * string), the same discipline {@link ApprovalStamp} uses for the approval line.
     */
    static final class CompletionStamp {

        static final String MARKER = "Implemented and verified";

        private CompletionStamp() {
            // Holder for the completion-line builder; not instantiable.
        }

        static String line(String taskId, VerifyOutcome verify) {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(verify, "verify");
            return "- [x] " + taskId + " " + MARKER + " (verified on attempt "
                    + verify.iterations() + ")";
        }
    }
}
