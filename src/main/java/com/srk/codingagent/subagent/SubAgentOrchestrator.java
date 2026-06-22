package com.srk.codingagent.subagent;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EdgeType;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.EventPayload;
import com.srk.codingagent.persistence.SessionMeta;
import com.srk.codingagent.persistence.SessionStatus;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.persistence.SubAgentResultPayload;
import com.srk.codingagent.persistence.SubAgentSpawnPayload;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Sub-agent Orchestrator (component C13, ADR-0010): spawns a sub-agent as a
 * <em>nested</em> {@link AgentLoop} on a worker thread, with its own isolated context, its
 * own session and lineage, a fresh permission gate (no inherited grants), and a wall-clock
 * budget — then returns to the parent ONLY the child's summarized result (AC-17.4, INV-11),
 * never the child's transcript.
 *
 * <p><b>In-process isolation (AC-17.2, OQ-C = in-process).</b> The child runs in the same
 * JVM but is contextually isolated: it gets its own {@link EventLog} (its own session JSONL),
 * its own {@code messages[]} (seeded from the spec's scoped prompt inside the nested loop),
 * and a fresh {@link GrantStore} — so the child cannot see the parent's {@code messages[]} or
 * grants, and vice-versa. The child reuses the parent's existing C2 loop (this orchestrator
 * does not write a new loop): the {@link ChildAgentLoopFactory} assembles a real
 * {@link AgentLoop} over the same {@code ModelClient}/{@code ConverseWireMapper} wire path the
 * parent uses, so the child's toolResult mapping is the D2-safe path by construction.
 *
 * <p><b>Concurrency bound (AC-17.3, INV-12, CT-INV-10).</b> At most {@code subAgentMax}
 * (default {@code 1}, {@link com.srk.codingagent.config.ResolvedConfig#subAgentMax()})
 * children run concurrently, enforced by a {@link Semaphore} permit acquired before the child
 * runs and released when it finishes. v1 ships {@code subAgentMax == 1} — effectively
 * sequential isolation, with the parent blocking on the child result. The semaphore IS the
 * N&gt;1 seam (a bounded pool), but N&gt;1 parallelism is config-gated and not exercised here.
 * A spawn that would exceed the bound is rejected with a failure result rather than queued or
 * run, so the orchestrator never runs more than {@code subAgentMax} concurrently.
 *
 * <p><b>Budget — never hangs (AC-17.6, NFR-SUBAGENT-BUDGET).</b> The child loop runs via a
 * {@link Future} with a wall-clock cap (default 600s, overridable per spec). If the child does
 * not finish within the cap, its task is cancelled (the worker thread interrupted) and a
 * {@linkplain SubAgentResult#failed failure result} is returned to the parent — the parent
 * decides a next step rather than hanging. The cap is injected as a {@link Duration} so the
 * over-budget path is driven deterministically in tests (no real 600s sleep).
 *
 * <p><b>Lineage + summary-only propagation (AC-17.5, INV-11).</b> Each child is its own
 * session linked {@link EdgeType#SPAWNED_BY} the parent (recorded on the child's
 * {@code .meta.json}). The orchestrator logs a {@code SUBAGENT_SPAWN} and, on completion, a
 * {@code SUBAGENT_RESULT} carrying only the summary, in the PARENT's log; the child's full
 * transcript lives in the child's own JSONL. The parent incorporates only the returned
 * {@link SubAgentResult} summary into its context — never the child's per-turn events.
 *
 * <p><b>No fault isolation (ADR-0010 Notes, accepted for N=1).</b> The child shares the JVM;
 * a child that crashes the JVM takes the parent down. That is the accepted N=1 trade.
 */
public final class SubAgentOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubAgentOrchestrator.class);

    /**
     * The default wall-clock budget for a child (NFR-SUBAGENT-BUDGET: "wall-clock 600 s,
     * configurable"). Used when a {@link SubAgentSpec} does not override it.
     */
    public static final Duration DEFAULT_WALL_CLOCK_CAP = Duration.ofSeconds(600);

    private final SessionStore sessionStore;
    private final EventLog parentLog;
    private final ChildAgentLoopFactory childLoopFactory;
    private final GrantStore parentGrants;
    private final PermissionMode permissionMode;
    private final Approver approver;
    private final String repoKey;
    private final String parentModelId;
    private final Supplier<String> childSessionIds;
    private final Supplier<String> clock;
    private final Duration defaultWallClockCap;
    private final Semaphore concurrencyPermits;
    private final AtomicInteger activeChildren = new AtomicInteger(0);

    /**
     * Creates an orchestrator.
     *
     * @param sessionStore     the session store that opens the child's own session log and
     *                         records its {@code SPAWNED_BY} lineage meta (ADR-0005); must not
     *                         be {@code null}.
     * @param parentLog        the parent's session event log, where the {@code SUBAGENT_SPAWN}
     *                         and {@code SUBAGENT_RESULT} events are recorded (AC-17.5,
     *                         INV-11) — the child writes its own transcript to its own log;
     *                         must not be {@code null}.
     * @param childLoopFactory the seam that builds the nested child {@link AgentLoop} over the
     *                         real wire path (D2-safe); must not be {@code null}.
     * @param parentGrants     the parent's grant store, used ONLY to mint the child's fresh
     *                         empty store via {@link GrantStore#forSubAgent(String)} (the child
     *                         inherits no grants, AC-10.6/INV-10); must not be {@code null}.
     * @param permissionMode   the configured permission mode the child runs FRESH; must not be
     *                         {@code null}.
     * @param approver         the approval seam for the child's gate; must not be {@code null}.
     * @param repoKey          the repository key the child session is scoped to; non-blank.
     * @param parentModelId    the parent's model id, the child's default model unless the spec
     *                         overrides it (AC-17.2); non-blank.
     * @param childSessionIds  the boundary-captured source of child session ids (ADR-0005 — the
     *                         orchestrator never generates ids with {@code UUID.randomUUID()});
     *                         each call must return a non-blank id; must not be {@code null}.
     * @param clock            the boundary clock supplying timestamps for the parent's
     *                         {@code SUBAGENT_SPAWN}/{@code SUBAGENT_RESULT} events (ADR-0005);
     *                         must not be {@code null}.
     * @param subAgentMax      the maximum concurrent children (NFR-SUBAGENT-MAX, default 1);
     *                         {@code >= 1}.
     * @param defaultWallClockCap the default child wall-clock budget (NFR-SUBAGENT-BUDGET) when
     *                         a spec does not override it; must be positive.
     * @throws NullPointerException     if a required reference argument is {@code null}.
     * @throws IllegalArgumentException if {@code repoKey}/{@code parentModelId} is blank,
     *                                  {@code subAgentMax < 1}, or {@code defaultWallClockCap}
     *                                  is not positive.
     */
    public SubAgentOrchestrator(
            SessionStore sessionStore,
            EventLog parentLog,
            ChildAgentLoopFactory childLoopFactory,
            GrantStore parentGrants,
            PermissionMode permissionMode,
            Approver approver,
            String repoKey,
            String parentModelId,
            Supplier<String> childSessionIds,
            Supplier<String> clock,
            int subAgentMax,
            Duration defaultWallClockCap) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.parentLog = Objects.requireNonNull(parentLog, "parentLog");
        this.childLoopFactory = Objects.requireNonNull(childLoopFactory, "childLoopFactory");
        this.parentGrants = Objects.requireNonNull(parentGrants, "parentGrants");
        this.permissionMode = Objects.requireNonNull(permissionMode, "permissionMode");
        this.approver = Objects.requireNonNull(approver, "approver");
        this.repoKey = requireNonBlank(repoKey, "repoKey");
        this.parentModelId = requireNonBlank(parentModelId, "parentModelId");
        this.childSessionIds = Objects.requireNonNull(childSessionIds, "childSessionIds");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (subAgentMax < 1) {
            throw new IllegalArgumentException("subAgentMax must be >= 1 (was " + subAgentMax + ")");
        }
        Objects.requireNonNull(defaultWallClockCap, "defaultWallClockCap");
        if (defaultWallClockCap.isZero() || defaultWallClockCap.isNegative()) {
            throw new IllegalArgumentException(
                    "defaultWallClockCap must be positive (was " + defaultWallClockCap + ")");
        }
        this.defaultWallClockCap = defaultWallClockCap;
        // The bound IS the N>1 seam (a bounded pool of permits); v1 ships subAgentMax == 1, so
        // the parent blocks on a single in-flight child (AC-17.3). A fair semaphore is not
        // needed at N=1 and is irrelevant to the bound's correctness.
        this.concurrencyPermits = new Semaphore(subAgentMax);
    }

    /**
     * Spawns one sub-agent for the given scoped prompt (using the parent's model and the
     * orchestrator default budget), blocking until the child completes, fails, or exceeds its
     * budget, and returns the summarized result (AC-17.1, AC-17.4).
     *
     * @param prompt the scoped prompt the child performs; non-blank.
     * @return the child's summary-only result; never {@code null}.
     * @throws IllegalArgumentException if {@code prompt} is blank.
     */
    public SubAgentResult spawn(String prompt) {
        return spawn(SubAgentSpec.of(prompt));
    }

    /**
     * Spawns one sub-agent per the given spec, blocking until the child completes, fails, or
     * exceeds its budget, and returns the summarized result (AC-17.1, AC-17.4, AC-17.6).
     *
     * <p>If spawning would exceed {@code subAgentMax} concurrent children (INV-12), the spawn
     * is rejected with a failure result and no child runs (CT-INV-10).
     *
     * @param spec the spawn request (scoped prompt + optional model/budget overrides); must
     *             not be {@code null}.
     * @return the child's summary-only result; never {@code null}.
     * @throws NullPointerException if {@code spec} is {@code null}.
     */
    public SubAgentResult spawn(SubAgentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        String childSessionId = requireNonBlank(childSessionIds.get(), "childSessionId");
        String modelId = spec.modelIdIfPresent().orElse(parentModelId);

        // AC-17.3 / INV-12 / CT-INV-10: enforce the concurrency bound before anything runs. A
        // non-blocking tryAcquire means a spawn that would exceed subAgentMax is rejected (a
        // failure result the parent can react to), never queued or run concurrently past the
        // bound. v1 N=1: the parent blocks on its single in-flight child, so this rejects a
        // genuinely-concurrent over-the-bound request.
        if (!concurrencyPermits.tryAcquire()) {
            LOGGER.warn("Sub-agent spawn rejected: at concurrency bound (NFR-SUBAGENT-MAX); "
                    + "childSessionId={} not started", childSessionId);
            return SubAgentResult.failed(childSessionId,
                    "sub-agent spawn rejected: concurrent sub-agents at NFR-SUBAGENT-MAX bound");
        }
        try {
            activeChildren.incrementAndGet();
            return runChild(spec, childSessionId, modelId);
        } finally {
            activeChildren.decrementAndGet();
            concurrencyPermits.release();
        }
    }

    /**
     * The number of children currently running (held permits). Used to assert the concurrency
     * bound (CT-INV-10): this never exceeds {@code subAgentMax}.
     *
     * @return the active child count; {@code 0} when idle.
     */
    public int activeChildCount() {
        return activeChildren.get();
    }

    private SubAgentResult runChild(SubAgentSpec spec, String childSessionId, String modelId) {
        Duration cap = spec.wallClockCapIfPresent().orElse(defaultWallClockCap);

        // AC-17.5 / INV-11: record the spawn + the SPAWNED_BY lineage in the PARENT's log, and
        // persist the child's SPAWNED_BY edge on its own session meta (ADR-0005). The child's
        // own transcript will live in the child's own JSONL — never projected into the parent.
        EventLog childLog = sessionStore.openLog(repoKey, childSessionId);
        writeChildLineageMeta(childSessionId);
        appendToParent(new SubAgentSpawnPayload(
                childSessionId, EdgeType.SPAWNED_BY, modelId, spec.prompt()));
        LOGGER.info("Spawning sub-agent: childSessionId={}, modelId={}, cap={}s",
                childSessionId, modelId, cap.toSeconds());

        // AC-10.6 / INV-10 / CT-INV-9: the child gate is backed by a brand-new EMPTY grant
        // store (forSubAgent), so a grant the parent remembered is NOT readable by the child.
        PermissionGate childGate = new PermissionGate(
                permissionMode, parentGrants.forSubAgent(childSessionId), approver);
        ChildLoopContext context = new ChildLoopContext(
                childSessionId, childLog, childGate, modelId, spec.prompt());

        SubAgentResult result = runWithinBudget(context, cap, childSessionId);
        // AC-17.4 / INV-11: only the summary crosses back to the parent. The SUBAGENT_RESULT
        // event carries the summary + success flag, not the child's events.
        appendToParent(new SubAgentResultPayload(
                childSessionId, result.success(), result.summary()));
        return result;
    }

    /**
     * Runs the nested child loop on a worker thread with the wall-clock cap (AC-17.6). On
     * completion within budget, maps the child's {@link LoopOutcome} to a result; on
     * over-budget, cancels (interrupts) the child and returns a failure result; on a child
     * exception, returns a failure result. Never hangs past the cap, never propagates the
     * child's failure as an exception to the parent.
     */
    private SubAgentResult runWithinBudget(
            ChildLoopContext context, Duration cap, String childSessionId) {
        ChildAgentRun childRun = childLoopFactory.create(context);
        ExecutorService worker = Executors.newSingleThreadExecutor(childThreadFactory(childSessionId));
        Future<LoopOutcome> future = worker.submit(childRun::run);
        try {
            LoopOutcome outcome = future.get(cap.toMillis(), TimeUnit.MILLISECONDS);
            return toResult(childSessionId, outcome);
        } catch (TimeoutException e) {
            // AC-17.6: the child exceeded its wall-clock budget. Stop it (interrupt the worker)
            // and return a failure result so the parent decides a next step rather than hanging.
            future.cancel(true);
            LOGGER.warn("Sub-agent exceeded wall-clock budget of {}s; stopped: childSessionId={}",
                    cap.toSeconds(), childSessionId);
            return SubAgentResult.failed(childSessionId,
                    "sub-agent exceeded its wall-clock budget of " + cap.toSeconds() + "s and was stopped");
        } catch (ExecutionException e) {
            // AC-17.6: the child loop threw. The parent receives a failure result rather than
            // the child's exception crashing the parent loop.
            LOGGER.warn("Sub-agent failed: childSessionId={}", childSessionId, e.getCause());
            return SubAgentResult.failed(childSessionId,
                    "sub-agent failed: " + describeCause(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            LOGGER.warn("Interrupted while awaiting sub-agent: childSessionId={}", childSessionId);
            return SubAgentResult.failed(childSessionId, "sub-agent interrupted before completion");
        } finally {
            worker.shutdownNow();
        }
    }

    /**
     * Maps a child loop {@link LoopOutcome} to the parent-facing result (AC-17.4 / AC-17.6).
     * A {@code COMPLETED} child returns its final answer as the summary; a {@code SURFACED}
     * child (an edge stop reason — e.g. budget exhaustion or a guardrail) maps to a failure
     * result so the parent decides a next step.
     */
    private static SubAgentResult toResult(String childSessionId, LoopOutcome outcome) {
        if (outcome.completed()) {
            String summary = outcome.finalTextIfPresent().orElse("");
            // A completed child with empty final text still completed; carry a non-blank summary
            // so the parent always receives a usable block (SubAgentResult requires non-blank).
            String nonBlank = summary.isBlank()
                    ? "sub-agent completed with no final text" : summary;
            return SubAgentResult.completed(childSessionId, nonBlank);
        }
        return SubAgentResult.failed(childSessionId,
                "sub-agent surfaced without completing (stopReason=" + outcome.stopReason() + ")");
    }

    /** Persists the child's {@code SPAWNED_BY} lineage edge on its own session meta (ADR-0005). */
    private void writeChildLineageMeta(String childSessionId) {
        SessionMeta meta = new SessionMeta(
                childSessionId, repoKey, SessionStatus.ACTIVE, 0, 0, 0,
                parentSessionId(), EdgeType.SPAWNED_BY, null);
        sessionStore.writeMeta(meta);
    }

    /**
     * The parent session id for the child's lineage edge. v1 sources it from the grant store's
     * lineage (the parent gate's lineage IS the parent session lineage, INV-10), so the child's
     * meta points at the parent without this orchestrator needing a separate parent-id field.
     */
    private String parentSessionId() {
        return parentGrants.sessionLineage();
    }

    /** Appends an event to the PARENT's log with a boundary-captured timestamp (ADR-0005). */
    private void appendToParent(EventPayload payload) {
        parentLog.append(new Event(parentLog.nextSeq(), clock.get(), payload));
    }

    private static ThreadFactory childThreadFactory(String childSessionId) {
        return runnable -> {
            Thread thread = new Thread(runnable, "subagent-" + childSessionId);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String describeCause(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            return e.getMessage();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }
}
