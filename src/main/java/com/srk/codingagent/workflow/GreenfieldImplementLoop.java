package com.srk.codingagent.workflow;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The greenfield <b>implement-every-task / mark-complete-on-implementation</b> loop (component C3 over
 * C2, ADR-0012 implement clause amended by DCR-7; US-3, AC-3.2/3.3): the orchestration of the
 * IMPLEMENT phase &mdash; the terminal phase of the {@link GreenfieldPhase} machine, reached only
 * after the design and task breakdown are approved (AC-2.3) &mdash; drives once it is entered. It
 * reads the approved task breakdown, then for each task in breakdown order: drives an agent-loop turn
 * to implement that one task and <em>immediately marks it complete</em> in the task-breakdown artifact
 * (AC-3.3 &mdash; "mark it complete … as it is implemented … before starting the next"), as it is
 * implemented, before the next task begins.
 *
 * <p><b>Verify at end of phase, not per task (DCR-7, resolves D3).</b> The greenfield IMPLEMENT phase
 * is a flat task list with no milestone substructure, so the verify boundary is <em>end-of-phase</em>
 * (a single configured build/test run after the last task), not after each individual task (AC-3.2,
 * ADR-0012). There is therefore <b>no per-task verify cycle in the loop body</b>: a task that is not
 * independently testable &mdash; an early scaffold, or a {@code pom.xml} that is not buildable until
 * later tasks land &mdash; is implemented <em>without</em> per-task verification, so a scaffold-first
 * breakdown implements every task in order and never hard-stops at the not-yet-buildable first task.
 * Each task is marked complete <em>on implementation</em>, not on passing an individual verify
 * (AC-3.3). The end-of-phase verification that gates the phase (AC-3.2/AC-3.4) is added around this
 * loop by T-3.9; this loop reports the tasks it implemented for that end verify to wrap.
 *
 * <p><b>Orchestration over the loop, not a separate engine (ADR-0012).</b> Each task's implementation
 * is one {@link AgentLoop} turn (the {@link LoopTurn} seam, the same {@link AgentLoop#run(String)}
 * shape {@link BrownfieldDriver} uses); the loop runs that implement&rarr;mark-complete step once per
 * task, in order. The verify engine ({@link com.srk.codingagent.loop.VerifyLoop}) is reused for the
 * end-of-phase verify (T-3.9), never reimplemented; the {@link #overConfig} factory carries the
 * collaborators that end verify will need.
 *
 * <p><b>Marking complete reuses the T-3.2 artifact store (AC-3.3).</b> When a task is implemented, its
 * completion is recorded by appending a completion line to the task-breakdown artifact through the
 * same {@link GreenfieldArtifactStore} T-3.2 authored the artifact with &mdash; not a parallel writer.
 * The append happens <em>before</em> the next task's turn begins (AC-3.3 "before starting the next")
 * and is durable on disk so a later intra-IMPLEMENT resume (T-3.10, AC-7.6) can read the markers back
 * and skip already-completed tasks.
 *
 * <p><b>The IMPLEMENT-phase loop turn (how it plugs into the driver).</b> The
 * {@link GreenfieldDriver} runs the terminal phase as a {@link GreenfieldDriver.LoopTurn}; this loop
 * is what that turn does. {@link #asLoopTurn()} adapts the {@link ImplementOutcome} this loop produces
 * to the {@link LoopOutcome} the phase seam returns: an all-implemented run (or a no-tasks run)
 * completes cleanly so the driver reaches its {@code COMPLETED} greenfield outcome (exit 0).
 *
 * <p>Not thread-safe: one implement loop runs one greenfield session's implementation phase on a
 * single thread (the C2 invariant the loop inherits &mdash; one in-flight model call per conversation).
 */
public final class GreenfieldImplementLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(GreenfieldImplementLoop.class);

    /** The greenfield task-breakdown artifact the approved tasks are read from / marked complete in. */
    static final GreenfieldArtifact TASKS_ARTIFACT = GreenfieldArtifact.TASKS;

    private final LoopTurn loop;
    private final GreenfieldArtifactStore store;

    /**
     * The target-repo command executor and resolved config for the <em>end-of-phase</em> verify
     * (AC-3.2/AC-3.4, ADR-0012) that T-3.9 adds after the per-task loop, reusing the proven T-1.4
     * {@link com.srk.codingagent.loop.VerifyLoop} via {@link #overConfig}. T-3.8 introduces no
     * per-task verify (DCR-7), so the loop body does not consult these yet; they are carried so the
     * end-of-phase verify can be wired in without re-threading the composition root. The four-arg
     * {@link #GreenfieldImplementLoop(LoopTurn, GreenfieldArtifactStore)} constructor (used by the
     * unit tests, which drive only the per-task loop) leaves them {@code null}.
     */
    private final CommandExecutor verifyExecutor;
    private final ResolvedConfig verifyConfig;

    /**
     * Creates an implement loop over just the per-task seams &mdash; the agent-loop turn and the
     * target-repo artifact store &mdash; with no end-of-phase verify collaborators. Suitable for
     * exercising the per-task implement-and-mark-complete behaviour (DCR-7) in isolation; the
     * production composition uses {@link #overConfig} so the end-of-phase verify T-3.9 adds has its
     * collaborators.
     *
     * @param loop  the agent-loop turn seam ({@link AgentLoop#run(String)} shape) each task's
     *              implementation turn runs through; must not be {@code null}.
     * @param store the target-repo artifact store the approved breakdown is read from and each
     *              implemented task is marked complete in (AC-3.3, reusing T-3.2); must not be
     *              {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public GreenfieldImplementLoop(LoopTurn loop, GreenfieldArtifactStore store) {
        this(loop, store, null, null);
    }

    private GreenfieldImplementLoop(LoopTurn loop, GreenfieldArtifactStore store,
            CommandExecutor verifyExecutor, ResolvedConfig verifyConfig) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.store = Objects.requireNonNull(store, "store");
        this.verifyExecutor = verifyExecutor;
        this.verifyConfig = verifyConfig;
    }

    /**
     * Composes the production implement loop: each task's implementation turn runs through the given
     * {@link AgentLoop} (the IMPLEMENT-phase loop, which carries the full source-write toolset,
     * AC-2.3), and the {@code executor} + {@code config} are carried for the <em>end-of-phase</em>
     * verify (AC-3.2/AC-3.4) that T-3.9 wires via the reused
     * {@link com.srk.codingagent.loop.VerifyLoop}. There is no per-task verify (DCR-7).
     *
     * @param loop     the agent-loop turn seam (typically {@code agentLoop::run}); must not be
     *                 {@code null}.
     * @param executor the command executor rooted at the target repo, for the end-of-phase verify's
     *                 build/test command (T-3.9); must not be {@code null}.
     * @param config   the resolved configuration supplying the test command, timeout, and the
     *                 verify-iteration bound (NFR-VERIFY-MAX-ITERATIONS) the end-of-phase verify uses
     *                 (T-3.9); must not be {@code null}.
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
        return new GreenfieldImplementLoop(loop, store, executor, config);
    }

    /**
     * Runs the implementation phase: implement <em>every</em> task in the approved breakdown one at a
     * time in breakdown order, marking each complete in the task-breakdown artifact as it is
     * implemented, before the next task begins (DCR-7; AC-3.2/3.3).
     *
     * <p>The flow:
     * <ol>
     *   <li>Read the approved task-breakdown artifact and enumerate its tasks in breakdown order
     *       (AC-3.2). An empty / task-less breakdown yields {@link ImplementOutcome#noTasks()}.</li>
     *   <li>For each task, in order: drive an agent-loop turn to implement that one task, then
     *       <em>immediately</em> mark it complete in the task-breakdown artifact (AC-3.3) before the
     *       next task's turn begins. A task that is not independently testable (an early scaffold, a
     *       not-yet-buildable {@code pom.xml}) is implemented without per-task verification &mdash;
     *       there is no per-task verify cycle (DCR-7), so the loop never hard-stops at such a task.</li>
     *   <li>When every task has been implemented and marked complete, return
     *       {@link ImplementOutcome#allImplemented(List)} carrying the completed ids. End-of-phase
     *       verification (T-3.9, AC-3.2/AC-3.4) gates the phase around this result.</li>
     * </ol>
     *
     * @param prompt the prompt that opens the implementation phase (the phase-advance prompt the
     *               driver supplies when it enters IMPLEMENT, naming the per-task implementation it
     *               primes); non-blank. It is used as the priming context for each task's turn.
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

        LOGGER.info("Greenfield implement phase: {} task(s) to implement one at a time in breakdown "
                + "order, marking each complete on implementation; verification runs once at end of "
                + "phase (AC-3.2/3.3, ADR-0012/DCR-7)", tasks.size());

        List<String> implemented = new ArrayList<>();
        for (String taskId : tasks) {
            implementTask(taskId, prompt);
            markComplete(taskId);
            implemented.add(taskId);
        }

        LOGGER.info("Greenfield implement phase: {} task(s) implemented and marked complete in order "
                + "(AC-3.2/3.3); end-of-phase verification gates the phase (ADR-0012/DCR-7)",
                implemented.size());
        return ImplementOutcome.allImplemented(implemented);
    }

    /**
     * Adapts this implement loop to the {@link GreenfieldDriver.LoopTurn} the driver runs the terminal
     * IMPLEMENT phase through: it runs the loop and maps the {@link ImplementOutcome} to the
     * {@link LoopOutcome} the phase seam returns. An all-implemented (or no-tasks) run completes
     * cleanly so the driver reaches its {@code COMPLETED} greenfield outcome (exit 0).
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
     * Implements one task as a single agent-loop turn (DCR-7; AC-3.2). There is no per-task verify:
     * a task that is not independently testable is still implemented, and the end-of-phase verify
     * (T-3.9) gates the phase.
     */
    private void implementTask(String taskId, String basePrompt) {
        LOGGER.info("Implementing greenfield task {} (one task at a time, AC-3.2)", taskId);
        loop.run(taskPrompt(taskId, basePrompt));
    }

    /**
     * Marks an implemented task complete in the task-breakdown artifact (AC-3.3), reusing the T-3.2
     * {@link GreenfieldArtifactStore#appendLine} writer. The completion line records the task id so a
     * reader (and the T-3.10 intra-IMPLEMENT resume) can see which tasks the loop implemented.
     */
    private void markComplete(String taskId) {
        store.appendLine(TASKS_ARTIFACT.relativePath(), CompletionStamp.line(taskId));
        LOGGER.info("Marked greenfield task {} complete in {} on implementation (AC-3.3, DCR-7)",
                taskId, TASKS_ARTIFACT.relativePath());
    }

    /**
     * Builds the IMPLEMENT-phase prompt for one task: the phase priming context plus the specific task
     * id to implement now, so the model implements exactly one task this turn (AC-3.2). Kept small and
     * tested so the suite can assert each task's turn names the task it is to implement.
     */
    static String taskPrompt(String taskId, String basePrompt) {
        return basePrompt + "\n\nImplement task " + taskId + " now. Implement only this one task, "
                + "then stop so the next task can begin.";
    }

    /** Renders the user-facing report for the implement-phase loop outcome. */
    private static String report(ImplementOutcome outcome) {
        return switch (outcome.disposition()) {
            case ALL_IMPLEMENTED -> "Implemented " + outcome.implementedTasks().size()
                    + " task(s) one at a time, marking each complete in order: "
                    + String.join(", ", outcome.implementedTasks()) + ".";
            case NO_TASKS -> "The approved task breakdown has no task to implement.";
        };
    }

    /**
     * The agent-loop turn seam: the {@link AgentLoop#run(String)} shape, isolated so the implement
     * loop's per-task orchestration (each task's implementation turn) is testable with a scripted
     * loop, mirroring {@link BrownfieldDriver.LoopTurn} and {@link GreenfieldDriver.LoopTurn}.
     */
    @FunctionalInterface
    public interface LoopTurn {

        /**
         * Runs the agent loop for one prompt to its terminal {@link LoopOutcome}.
         *
         * @param prompt the prompt for this turn (a task-implementation prompt); non-blank.
         * @return the terminal outcome; never {@code null}.
         */
        LoopOutcome run(String prompt);
    }

    /**
     * Builds the completion line appended to the task-breakdown artifact when a task is implemented
     * (AC-3.3, DCR-7): a stable, greppable marker naming the task id, with the conventional completed-
     * checkbox shape ({@code - [x] <taskId> …}) so a reader (and the T-3.10 intra-IMPLEMENT resume)
     * can read back which tasks the loop implemented. Kept as a small tested artifact so the suite can
     * assert the marker carries the task id (rather than an opaque string), the same discipline
     * {@link ApprovalStamp} uses for the approval line.
     */
    static final class CompletionStamp {

        static final String MARKER = "Implemented";

        private CompletionStamp() {
            // Holder for the completion-line builder; not instantiable.
        }

        static String line(String taskId) {
            Objects.requireNonNull(taskId, "taskId");
            return "- [x] " + taskId + " " + MARKER;
        }
    }
}
