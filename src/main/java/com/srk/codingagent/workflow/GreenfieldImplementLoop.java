package com.srk.codingagent.workflow;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.loop.RemedyAttempt;
import com.srk.codingagent.loop.RemedyPrompt;
import com.srk.codingagent.loop.VerifyFailureReport;
import com.srk.codingagent.loop.VerifyLoop;
import com.srk.codingagent.loop.VerifyOutcome;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.CommandResult;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * (AC-3.3). Once every task is implemented, the loop runs the configured build/test command
 * <em>once</em> (testable-only, AC-3.2) over the whole implemented phase and maps the single verify
 * outcome (T-3.9): a passing verify is the clean phase success; a verify that does not pass within
 * {@code NFR-VERIFY-MAX-ITERATIONS} attempts stops and surfaces the failure (AC-3.4/AC-20.5); and
 * <em>no configured test command</em> skips the end verify with a single warning and terminates the
 * phase deterministically &mdash; a complete-with-warning terminal success (AC-3.6), not a hard-stop
 * and not a re-loop into a fresh implement attempt.
 *
 * <p><b>Orchestration over the loop, not a separate engine (ADR-0012).</b> Each task's implementation
 * is one {@link AgentLoop} turn (the {@link LoopTurn} seam, the same {@link AgentLoop#run(String)}
 * shape {@link BrownfieldDriver} uses); the loop runs that implement&rarr;mark-complete step once per
 * task, in order. The verify engine ({@link VerifyLoop}) is <em>reused</em> for the end-of-phase
 * verify, never reimplemented &mdash; the loop builds it via {@link VerifyLoop#forConfig} over the
 * {@link #overConfig} factory's carried collaborators, with a remedy that drives another agent-loop
 * turn between failing attempts (AC-20.3), exactly as {@link BrownfieldDriver} does for its
 * change-verify. The two-arg constructor (used by the unit tests, which drive only the per-task loop)
 * leaves the verify collaborators {@code null}, so a loop built without them runs no end verify.
 *
 * <p><b>Marking complete reuses the T-3.2 artifact store (AC-3.3).</b> When a task is implemented, its
 * completion is recorded by appending a completion line to the task-breakdown artifact through the
 * same {@link GreenfieldArtifactStore} T-3.2 authored the artifact with &mdash; not a parallel writer.
 * The append happens <em>before</em> the next task's turn begins (AC-3.3 "before starting the next")
 * and is durable on disk so a later intra-IMPLEMENT resume (T-3.10, AC-7.6) can read the markers back
 * and skip already-completed tasks.
 *
 * <p><b>Intra-IMPLEMENT resume skips already-completed tasks (T-3.10, resolves D2; AC-7.6, AC-3.3,
 * ADR-0012).</b> The completion marker {@link #markComplete} writes is now also <em>read back</em>:
 * before running, the loop reads the per-task completion markers from the current task-breakdown
 * artifact ({@link #readCompletedTaskIds()}) and skips the ids already marked complete, resuming at
 * the first incomplete task rather than restarting at the first task. So a greenfield re-entry whose
 * reconstructed phase is {@link GreenfieldPhase#IMPLEMENT} (the phase boundary is handled by
 * {@link GreenfieldPhaseState}, T-3.4/DCR-3) makes within-IMPLEMENT progress: it implements only the
 * remaining incomplete tasks, in breakdown order, then runs the end-of-phase verify. A re-entry over a
 * <em>fully</em>-completed breakdown has nothing left to implement; it still runs the end-of-phase
 * verify once over the already-complete phase (AC-3.2 gates the <em>phase</em>, not each task) and
 * reaches the same terminal disposition a single uninterrupted run would. The same phase-boundary
 * tradeoff applies (AC-7.6): task-completion granularity is durable on disk, but the in-phase implement
 * conversation is not preserved across the interruption.
 *
 * <p><b>The completion marker is distinguished from a planned-task line by its own shape, not by the
 * {@code [x]} checkbox alone (T-3.10).</b> The markers ({@code - [x] <taskId> Implemented}) live in the
 * <em>same</em> {@code 02-tasks.md} artifact the planned tasks are read from, and a checked-checkbox
 * line is itself recognized as a task by {@link TaskTraceability}. So the loop separates the two: a
 * completion-marker line is recognized specifically by {@link CompletionStamp#isCompletionLine} (the
 * checked {@code - [x]} box <em>plus</em> the trailing {@code Implemented} {@link CompletionStamp#MARKER}
 * token), and those marker lines are excluded before the planned tasks are enumerated &mdash; so the
 * planned-task enumeration ({@link TaskTraceability#tasksInOrder}) is never polluted by the markers and
 * the traceability gate's {@link TaskTraceability#check} contract (which runs over the breakdown
 * <em>before</em> any marker is appended, at tasks-phase approval) is unaffected.
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
     * (AC-3.2/AC-3.4/AC-3.6, ADR-0012/DCR-7) the loop runs after the per-task loop, reusing the proven
     * T-1.4 {@link VerifyLoop} via {@link VerifyLoop#forConfig} (wired through {@link #overConfig}).
     * The two-arg {@link #GreenfieldImplementLoop(LoopTurn, GreenfieldArtifactStore)} constructor
     * (used by the unit tests, which drive only the per-task loop) leaves them {@code null}; a loop
     * built without them runs no end verify and reports {@link ImplementOutcome#allImplemented(List)}.
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
     * Runs the implementation phase: implement the not-yet-completed tasks of the approved breakdown
     * one at a time in breakdown order, marking each complete in the task-breakdown artifact as it is
     * implemented, before the next task begins (DCR-7; AC-3.2/3.3), then verify the phase once at the
     * end (T-3.9).
     *
     * <p>The flow:
     * <ol>
     *   <li>Read the approved task-breakdown artifact and enumerate its <em>planned</em> tasks in
     *       breakdown order (AC-3.2/AC-3.1) &mdash; excluding the completion-marker lines a prior
     *       (interrupted) run may have appended, so the enumeration is the planned tasks, not the
     *       markers. An empty / task-less breakdown yields {@link ImplementOutcome#noTasks()}.</li>
     *   <li>Read back the per-task completion markers ({@link #readCompletedTaskIds()}, T-3.10) and
     *       <em>skip</em> the planned ids already marked complete &mdash; resuming at the first
     *       incomplete task rather than restarting at the first task (AC-7.6, AC-3.3, resolves D2). A
     *       fresh (uninterrupted) run has no markers, so nothing is skipped and every task is
     *       implemented; a re-entry over a partially-completed breakdown implements only the remaining
     *       incomplete tasks; a re-entry over a fully-completed breakdown implements nothing.</li>
     *   <li>For each <em>incomplete</em> task, in order: drive an agent-loop turn to implement that one
     *       task, then <em>immediately</em> mark it complete in the task-breakdown artifact (AC-3.3)
     *       before the next task's turn begins. A task that is not independently testable (an early
     *       scaffold, a not-yet-buildable {@code pom.xml}) is implemented without per-task
     *       verification &mdash; there is no per-task verify cycle (DCR-7), so the loop never hard-stops
     *       at such a task.</li>
     *   <li>When every planned task is complete (the ones implemented this run plus any already-marked
     *       on re-entry), run the configured build/test command <em>once</em> over the whole phase
     *       (testable-only, AC-3.2) via the reused {@link VerifyLoop} and map the single verify outcome
     *       (T-3.9): a passing verify is {@link ImplementOutcome#verified(List, VerifyOutcome)} (the
     *       clean phase success); a verify that does not pass within {@code NFR-VERIFY-MAX-ITERATIONS}
     *       attempts is {@link ImplementOutcome#verifyFailed(List, VerifyOutcome)} (stop and surface,
     *       AC-3.4/AC-20.5); no configured test command is
     *       {@link ImplementOutcome#completeWithWarning(List, VerifyOutcome)} (skip with one warning,
     *       terminate deterministically &mdash; AC-3.6). When the loop carries no verify collaborators
     *       (the per-task unit path), the end verify is skipped and the result is
     *       {@link ImplementOutcome#allImplemented(List)}. The end-of-phase verify gates the
     *       <em>phase</em> (AC-3.2), so it runs once even on a re-entry that implemented nothing
     *       (every planned task was already complete) &mdash; verifying the already-complete work,
     *       consistent with a single uninterrupted run.</li>
     * </ol>
     *
     * <p>The {@code implementedTasks} the terminal {@link ImplementOutcome} carries are the phase's
     * complete tasks in breakdown order &mdash; the union of the tasks implemented this run and the
     * tasks already marked complete on re-entry &mdash; so the report reflects the whole completed
     * phase, not only the tasks this particular (resumed) run touched.
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

        List<String> plannedTasks = readPlannedTasksInOrder();
        if (plannedTasks.isEmpty()) {
            LOGGER.warn("Greenfield implement phase entered but the approved breakdown ({}) has no "
                    + "recognizable task to implement", TASKS_ARTIFACT.relativePath());
            return ImplementOutcome.noTasks();
        }

        Set<String> alreadyComplete = readCompletedTaskIds();
        List<String> remaining = new ArrayList<>(plannedTasks);
        remaining.removeAll(alreadyComplete);

        if (alreadyComplete.isEmpty()) {
            LOGGER.info("Greenfield implement phase: {} task(s) to implement one at a time in breakdown "
                    + "order, marking each complete on implementation; verification runs once at end of "
                    + "phase (AC-3.2/3.3, ADR-0012/DCR-7)", plannedTasks.size());
        } else {
            LOGGER.info("Greenfield intra-IMPLEMENT resume: {} of {} planned task(s) already marked "
                    + "complete on disk; resuming at the first incomplete task and implementing the "
                    + "remaining {} (skipping completed, not restarting at the first task) "
                    + "(AC-7.6/AC-3.3, T-3.10)",
                    alreadyComplete.size(), plannedTasks.size(), remaining.size());
        }

        for (String taskId : remaining) {
            implementTask(taskId, prompt);
            markComplete(taskId);
        }

        List<String> completedPhase = plannedTasksThatAreComplete(plannedTasks);
        LOGGER.info("Greenfield implement phase: {} task(s) complete in order ({} implemented this "
                + "run, {} already complete on re-entry); running the end-of-phase verify once over "
                + "the whole phase (AC-3.2, ADR-0012/DCR-7)",
                completedPhase.size(), remaining.size(), alreadyComplete.size());
        return verifyEndOfPhase(completedPhase);
    }

    /**
     * The planned tasks (the union of {@code plannedTasks}) whose ids are now marked complete, in
     * breakdown order. After the per-task loop ran, every remaining task was implemented and marked, so
     * this is the whole set of planned tasks that are complete &mdash; the tasks implemented this run
     * plus any already marked on re-entry &mdash; deduplicated in breakdown order. This is what the
     * terminal {@link ImplementOutcome} reports as the phase's complete tasks (AC-3.2 gates the phase).
     */
    private List<String> plannedTasksThatAreComplete(List<String> plannedTasks) {
        Set<String> complete = readCompletedTaskIds();
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String taskId : plannedTasks) {
            if (complete.contains(taskId) && seen.add(taskId)) {
                ordered.add(taskId);
            }
        }
        return ordered;
    }

    /**
     * Runs the single end-of-phase verify over the whole implemented phase and maps its outcome
     * (T-3.9; AC-3.2 testable-only, AC-3.4/AC-20.5, AC-3.6). The configured build/test command runs
     * <em>once</em> through the reused {@link VerifyLoop}, with a remedy that drives another agent-loop
     * turn between failing attempts (AC-20.3) so the verify loop's bounded retry can converge; the
     * verify loop bounds the attempts by {@code NFR-VERIFY-MAX-ITERATIONS}.
     *
     * <p>A loop built without verify collaborators (the two-arg unit-test constructor) runs no end
     * verify and reports {@link ImplementOutcome#allImplemented(List)} &mdash; the per-task path the
     * unit tests exercise.
     */
    private ImplementOutcome verifyEndOfPhase(List<String> implemented) {
        if (verifyExecutor == null || verifyConfig == null) {
            return ImplementOutcome.allImplemented(implemented);
        }
        VerifyOutcome verify = Objects.requireNonNull(
                VerifyLoop.forConfig(verifyExecutor, verifyConfig, remedyFeedingFailureBack())
                        .verify(),
                "verify loop returned a null outcome");
        return switch (verify.kind()) {
            case VERIFIED -> {
                LOGGER.info("Greenfield implement phase verified at end of phase on attempt {} "
                        + "(AC-3.2); the phase is complete (ADR-0012/DCR-7)", verify.iterations());
                yield ImplementOutcome.verified(implemented, verify);
            }
            case EXHAUSTED -> {
                LOGGER.warn("Greenfield end-of-phase verify did not pass within {} attempt(s); "
                        + "stopping and surfacing the failure (AC-3.4/AC-20.5)", verify.iterations());
                yield ImplementOutcome.verifyFailed(implemented, verify);
            }
            case NO_TEST_COMMAND -> {
                // AC-3.6 (DCR-7): no configured test command. Every task was implemented and marked
                // complete; skip the end verify with ONE warning and terminate the phase
                // deterministically — a complete-with-warning terminal success, NOT a hard-stop and
                // NOT a re-loop into a fresh implement attempt. (The shared VerifyLoop's NO_TEST_COMMAND
                // is a generic config state; the greenfield end-of-phase consumer binds it to AC-3.6 —
                // AC-20.6 is "prefer named commands", not the no-test-command behaviour.)
                LOGGER.warn("Greenfield implement phase: no test command configured; skipping the "
                        + "end-of-phase verify and completing with a warning (AC-3.6, DCR-7)");
                yield ImplementOutcome.completeWithWarning(implemented, verify);
            }
        };
    }

    /**
     * The remedy seam the end-of-phase {@link VerifyLoop} invokes between failing attempts (AC-20.3):
     * it drives another agent-loop turn (the {@link LoopTurn} seam) with a prompt built from the
     * failing command's output via the shared {@link RemedyPrompt}, so the model reads the failure,
     * fixes the cause, and the verify loop's next attempt re-runs the command. The verify loop bounds
     * how many times this runs. Mirrors {@link BrownfieldDriver}'s change-verify remedy &mdash; the
     * one failure-feedback prompt builder both drivers reuse.
     */
    private RemedyAttempt remedyFeedingFailureBack() {
        return failure -> {
            CommandResult result = Objects.requireNonNull(failure, "failure");
            LOGGER.info("Greenfield end-of-phase verify failed (exit {}); driving a remedy turn "
                    + "(AC-20.3)", result.exitCode());
            loop.run(RemedyPrompt.forFailure(result));
        };
    }

    /**
     * Adapts this implement loop to the {@link GreenfieldDriver.LoopTurn} the driver runs the terminal
     * IMPLEMENT phase through: it runs the loop and maps the {@link ImplementOutcome} to the
     * {@link LoopOutcome} the phase seam returns.
     *
     * <p>Every terminal disposition maps to a <em>completed</em> {@link LoopOutcome} (exit 0) whose
     * final text carries the disposition's report &mdash; the same "verification signal distinct from
     * the agent-process exit" stance the brownfield change-verify takes (exit-code contract G4):
     * <ul>
     *   <li>an all-implemented run (verified or no-verify) or a no-tasks run completes cleanly so the
     *       driver reaches its {@code COMPLETED} greenfield outcome (exit 0);</li>
     *   <li>a {@link ImplementOutcome.Disposition#COMPLETE_WITH_WARNING} run (no configured test
     *       command, AC-3.6) is a <b>terminal</b> completed outcome whose text carries the single
     *       warning &mdash; so the driver's {@code COMPLETED} mapping treats it as a finished turn and
     *       the REPL keep-alive does not re-enter implement (the D1 livelock fix);</li>
     *   <li>a {@link ImplementOutcome.Disposition#VERIFY_FAILED} run surfaces the end-verify failure
     *       and its relevant output (AC-20.5) in the completed text, so the developer sees the stuck
     *       phase &mdash; the process exit stays 0 (G4), as the brownfield verify-exhaustion does.</li>
     * </ul>
     *
     * @return the IMPLEMENT-phase loop turn for the {@link GreenfieldDriver.PhaseLoopFactory}; never
     *         {@code null}.
     */
    public GreenfieldDriver.LoopTurn asLoopTurn() {
        return prompt -> LoopOutcome.completed(report(run(prompt)));
    }

    /**
     * Enumerates the <em>planned</em> tasks of the breakdown in breakdown order (AC-3.2/AC-3.1),
     * reusing the shared {@link TaskTraceability#tasksInOrder} recognition but <em>excluding</em> the
     * completion-marker lines a prior interrupted run may have appended (T-3.10). The markers
     * ({@code - [x] <taskId> Implemented}) live in the same artifact and a checked-checkbox line is
     * itself recognized as a task by {@link TaskTraceability}; without this exclusion a post-completion
     * re-read would double-count each marked id (once for its planned line, once for its marker line).
     * Stripping the recognized markers before enumeration keeps the planned-task list exactly the
     * planned tasks, while {@link #readCompletedTaskIds()} computes the completed-id set from those same
     * markers. {@link TaskTraceability#tasksInOrder} itself is left marker-unaware so its contract (and
     * {@link TaskTraceability#check}'s, which runs at tasks-phase approval before any marker exists)
     * is unchanged.
     */
    private List<String> readPlannedTasksInOrder() {
        Optional<String> breakdown = store.read(TASKS_ARTIFACT.relativePath());
        if (breakdown.isEmpty()) {
            LOGGER.warn("Greenfield implement phase entered but no task-breakdown artifact exists at "
                    + "{}", TASKS_ARTIFACT.relativePath());
            return List.of();
        }
        return TaskTraceability.tasksInOrder(withoutCompletionMarkers(breakdown.get()));
    }

    /**
     * The set of task ids already marked complete on disk (T-3.10, the read-back side of
     * {@code markComplete}; AC-3.3 "completion markers are read back on resume", AC-7.6). Reads the
     * current task-breakdown artifact and collects the id from every completion-marker line &mdash;
     * recognized specifically by {@link CompletionStamp#isCompletionLine} (the checked {@code - [x]}
     * box plus the trailing {@code Implemented} marker token), so a planned {@code - [x] T-1 …} or
     * unchecked {@code - [ ] T-1 …} task line is never mistaken for a completion marker. An absent
     * artifact (or one with no markers, the fresh-run case) yields an empty set. The order is not
     * significant for skip-set membership, but a {@link LinkedHashSet} preserves first-seen order for
     * deterministic logging.
     */
    private Set<String> readCompletedTaskIds() {
        String content = store.read(TASKS_ARTIFACT.relativePath()).orElse("");
        Set<String> completed = new LinkedHashSet<>();
        for (String line : content.split("\n", -1)) {
            CompletionStamp.taskIdOf(line).ifPresent(completed::add);
        }
        return completed;
    }

    /**
     * Returns the breakdown content with the completion-marker lines removed (T-3.10), so the planned
     * tasks can be enumerated without the markers polluting them. Only lines recognized as completion
     * markers ({@link CompletionStamp#isCompletionLine}) are dropped; every other line (including the
     * original planned-task lines, even when they use a {@code - [x]} checkbox) is preserved verbatim,
     * so the planned-task enumeration over the result is identical to the enumeration over the original
     * pre-completion breakdown.
     */
    private static String withoutCompletionMarkers(String breakdown) {
        StringBuilder kept = new StringBuilder(breakdown.length());
        boolean first = true;
        for (String line : breakdown.split("\n", -1)) {
            if (CompletionStamp.isCompletionLine(line)) {
                continue;
            }
            if (!first) {
                kept.append('\n');
            }
            kept.append(line);
            first = false;
        }
        return kept.toString();
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
     * {@link GreenfieldArtifactStore#appendLine} writer. The completion line records the task id in the
     * {@link CompletionStamp} shape so a reader can see which tasks the loop implemented.
     *
     * <p><b>Write paired with read (T-3.10).</b> The marker this method writes is read back by
     * {@link #readCompletedTaskIds()} on a later (resumed) run &mdash; the prior write-only marker is
     * now a durable write-and-read fact: the write here and the read-back there recognize the same
     * {@link CompletionStamp} shape, so an intra-IMPLEMENT resume skips exactly the tasks this method
     * marked (AC-3.3 "completion markers are read back on resume", AC-7.6, resolves D2).
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
            case ALL_IMPLEMENTED -> implementedReport(outcome)
                    + " The end-of-phase verification passed.";
            case COMPLETE_WITH_WARNING -> implementedReport(outcome)
                    + " No test command is configured, so the end-of-phase verification was skipped "
                    + "with a warning; the phase is complete (AC-3.6).";
            case VERIFY_FAILED -> implementedReport(outcome) + "\n\n"
                    + VerifyFailureReport.forExhaustedVerify(
                            "End-of-phase verification did not pass", outcome.verifyOutcome());
            case NO_TASKS -> "The approved task breakdown has no task to implement.";
        };
    }

    /** The "implemented every task in order" prefix shared by the terminal reports. */
    private static String implementedReport(ImplementOutcome outcome) {
        return "Implemented " + outcome.implementedTasks().size()
                + " task(s) one at a time, marking each complete in order: "
                + String.join(", ", outcome.implementedTasks()) + ".";
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
     * Builds <em>and parses</em> the completion line appended to the task-breakdown artifact when a
     * task is implemented (AC-3.3, DCR-7; the read-back side is T-3.10): a stable, greppable marker
     * naming the task id, with the conventional completed-checkbox shape ({@code - [x] <taskId>
     * Implemented}) so a reader (the T-3.10 intra-IMPLEMENT resume) can read back which tasks the loop
     * implemented. Kept as a small tested artifact so the suite can assert the marker carries the task
     * id (rather than an opaque string), the same discipline {@link ApprovalStamp} uses for the
     * approval line.
     *
     * <p><b>One shape, written and recognized here (T-3.10).</b> The marker is distinguished from a
     * planned-task line by this <em>whole</em> shape &mdash; the checked {@code - [x]} box <em>plus</em>
     * the trailing {@link #MARKER} token &mdash; not by the {@code [x]} checkbox alone (a real planned
     * task may itself be a checked or unchecked checkbox, which {@link TaskTraceability} recognizes as a
     * task). {@link #line} writes that shape and {@link #isCompletionLine} / {@link #taskIdOf} recognize
     * exactly it, so the writer and the reader cannot drift: a unit test pins that {@code line(id)} is
     * recognized as a completion line for {@code id}.
     */
    static final class CompletionStamp {

        static final String MARKER = "Implemented";

        /**
         * Recognizes the completion-line shape this stamp writes (T-3.10): optional leading whitespace,
         * the checked {@code - [x]} list-checkbox, a stable task id ({@code T-<n>}/{@code T-<n>.<m>},
         * the same id form {@link TaskTraceability} uses), then the trailing {@link #MARKER} token as
         * the final content of the line. Group 1 captures the bare task id. Matching the trailing marker
         * (not just the {@code [x]} box) is what keeps a planned {@code - [x] T-1 Build the parser} or
         * an unchecked {@code - [ ] T-1 …} task line from being mistaken for a completion marker.
         */
        private static final Pattern COMPLETION_LINE = Pattern.compile(
                "^\\s*-\\s*\\[[xX]\\]\\s+(T-\\d+(?:\\.\\d+)*)\\s+" + MARKER + "\\s*$");

        private CompletionStamp() {
            // Holder for the completion-line builder + parser; not instantiable.
        }

        static String line(String taskId) {
            Objects.requireNonNull(taskId, "taskId");
            return "- [x] " + taskId + " " + MARKER;
        }

        /**
         * Whether {@code line} is a completion-marker line in this stamp's shape (a checked
         * {@code - [x]} box, a task id, then the trailing {@link #MARKER} token). Used to exclude the
         * markers when enumerating the planned tasks (T-3.10).
         *
         * @param line one line of the task-breakdown artifact; must not be {@code null}.
         * @return {@code true} if {@code line} is a completion marker.
         * @throws NullPointerException if {@code line} is {@code null}.
         */
        static boolean isCompletionLine(String line) {
            return COMPLETION_LINE.matcher(Objects.requireNonNull(line, "line")).matches();
        }

        /**
         * The task id named by {@code line} when it is a completion-marker line in this stamp's shape,
         * or {@link Optional#empty()} otherwise (T-3.10). The read-back side of {@link #line}: a marker
         * {@code - [x] T-1 Implemented} yields {@code T-1}; any other line yields empty.
         *
         * @param line one line of the task-breakdown artifact; must not be {@code null}.
         * @return the bare task id of the completion marker, or empty when {@code line} is not a marker.
         * @throws NullPointerException if {@code line} is {@code null}.
         */
        static Optional<String> taskIdOf(String line) {
            Matcher matcher = COMPLETION_LINE.matcher(Objects.requireNonNull(line, "line"));
            return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
        }
    }
}
