package com.srk.codingagent.cli;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.context.Compactor;
import com.srk.codingagent.context.LearningHarvester;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.CompactionSeam;
import com.srk.codingagent.loop.TokenBudgetGuard;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.model.ModelCapabilityProfile;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.model.credentials.BedrockClientFactory;
import com.srk.codingagent.model.credentials.BedrockCredentials;
import com.srk.codingagent.model.credentials.CredentialResolver;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.SessionReplay;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import com.srk.codingagent.tool.ToolRegistry;
import com.srk.codingagent.tool.memory.LearningApprover;
import com.srk.codingagent.tool.memory.LearningExtractor;
import com.srk.codingagent.tool.memory.LearningProposer;
import com.srk.codingagent.tool.memory.MemoryLearningHarvester;
import com.srk.codingagent.workflow.ArtifactApprovalGate;
import com.srk.codingagent.workflow.GreenfieldArtifact;
import com.srk.codingagent.workflow.GreenfieldDriver;
import com.srk.codingagent.workflow.GreenfieldPhase;
import com.srk.codingagent.workflow.GreenfieldPhaseState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;

/**
 * Assembles the production {@link AgentLoop} for a one-shot run from a resolved
 * configuration: it resolves SigV4 credentials (ADR-0011), builds the Bedrock client
 * (C4), composes the full live tool set (file/search/run C7/C9/C10, the memory tools C12,
 * and the sub-agent tool C13) into a registry via {@link ToolRegistryComposer}, wires the
 * permission gate (C8) over the configured mode with the supplied one-shot {@link Approver},
 * opens the session event log (C14/C15), and hands the collaborators to the {@link AgentLoop}
 * (C2).
 *
 * <p>This is the production-only composition root the one-shot launcher uses; it makes the
 * single un-unit-testable step — constructing a real Bedrock client from real credentials —
 * a thin, isolated seam, so the testable run-and-map logic lives entirely in
 * {@link OneShotRunner} (driven by a scripted Bedrock double). The factory makes no
 * Converse call itself; the loop does, only when run.
 *
 * <p><b>Tool composition lives in a gate-covered seam.</b> The set of tools the live run
 * offers the model — and the sub-agent {@link ToolRegistryComposer} child-loop wiring — is
 * assembled by {@link ToolRegistryComposer}, which (unlike this factory) is NOT coverage-
 * excluded: it is constructible without a live AWS call (the already-built {@link ModelClient}
 * is passed in), so a unit test pins the wiring contract (which tools the model sees, with
 * which operation classes) under the coverage gate. This factory's job narrows to the one live
 * step plus handing the composer its collaborators.
 *
 * <p><b>Credential failure surfaces (AC-8.9 → exit 4).</b> {@link #create} lets a
 * {@link com.srk.codingagent.model.credentials.CredentialResolutionException} propagate so
 * the launcher's {@link OneShotRunner} maps it to exit {@code 4}; the factory does not
 * swallow it (no credentials means no usable Bedrock, a model-backend problem).
 */
public final class AgentLoopFactory {

    /**
     * The conservative safe-minimum input-token window used for a model id with no known
     * capability profile (ADR-0002 "a safe minimum context window"). v1 ships only Claude
     * profiles populated, so the live default model ({@code us.anthropic.claude-opus-4-8})
     * resolves to its real window through the registry; this fallback only applies to an
     * unrecognized id and is set low so the compaction threshold fires conservatively rather
     * than risking a context overflow on an unvalidated model. T-4.3 (the full capability
     * registry + schema validation) sources this from config {@code NFR-MODEL-CONTEXT-WINDOW}
     * instead; until then it is a compiled-in default in this composition root.
     */
    private static final int CONSERVATIVE_DEFAULT_CONTEXT_WINDOW_TOKENS = 100_000;

    /**
     * How many recent verbatim turns the live compaction carries forward into the derived session
     * (the configurable tail ADR-0006 names). v1 carries the last four message turns so the derived
     * conversation keeps the immediate working context (the model's last move and its tool results)
     * verbatim — preserving their reasoning signatures (INV-7) — alongside the summary. There is no
     * config key for this yet (no NFR pins the tail size); it is a compiled-in default in this
     * composition root until one exists, mirroring the conservative-window default above.
     */
    private static final int LIVE_COMPACTION_RECENT_TAIL_TURNS = 4;

    private final CredentialResolver credentialResolver;
    private final BedrockClientFactory clientFactory;

    /**
     * Creates a factory with the production credential resolver and client factory.
     */
    public AgentLoopFactory() {
        this(new CredentialResolver(), new BedrockClientFactory());
    }

    /**
     * Creates a factory with injected collaborators (used to exercise the wiring without
     * touching {@code ~/.aws} or constructing a real client).
     *
     * @param credentialResolver resolves SigV4 credentials from the configured profile /
     *                           default chain (ADR-0011); must not be {@code null}.
     * @param clientFactory      builds the Bedrock client from the resolved credentials and
     *                           region; must not be {@code null}.
     * @throws NullPointerException if either argument is {@code null}.
     */
    AgentLoopFactory(CredentialResolver credentialResolver, BedrockClientFactory clientFactory) {
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    /**
     * Builds the agent loop for a one-shot run in the given workspace, writing events to
     * the supplied session log.
     *
     * @param config         the resolved configuration (model id, region, profile,
     *                       permission mode, command timeout); must not be {@code null}.
     * @param workspaceRoot  the target repository directory (AC-6.2 — the working
     *                       directory commands and file tools are rooted at); must not be
     *                       {@code null}.
     * @param sessionLineage the session lineage the permission gate's grant store is scoped
     *                       to (INV-10); it is also the repo key + origin session the memory
     *                       tools and the sub-agent orchestrator are scoped to at v1; non-blank.
     * @param log            the open session event log the loop appends to (and the parent log
     *                       the memory-write provenance + sub-agent events are recorded in);
     *                       must not be {@code null}.
     * @param approver       the approval seam (the one-shot {@link NonInteractiveApprover}
     *                       in production); must not be {@code null}.
     * @param sessions       the session store the sub-agent orchestrator opens each child's OWN
     *                       log from (ADR-0010 — the child never shares the parent's log);
     *                       must not be {@code null}.
     * @return a composed {@link AgentLoop} ready to run a prompt; never {@code null}.
     * @throws NullPointerException if a required argument is {@code null}.
     * @throws com.srk.codingagent.model.credentials.CredentialResolutionException if no
     *         usable SigV4 credentials can be resolved (AC-8.9 → exit 4).
     */
    public AgentLoop create(ResolvedConfig config, Path workspaceRoot, String sessionLineage,
            EventLog log, Approver approver, SessionStore sessions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(approver, "approver");
        Objects.requireNonNull(sessions, "sessions");

        ToolRegistryComposer composer = composer(config, workspaceRoot, log, approver, sessions,
                sessionLineage);
        ToolRegistry tools = composer.parentRegistry();
        return assembleLoop(composer, composer.modelClient(), config, log, sessionLineage, approver,
                sessions, tools, composer.parentSystemPrompt());
    }

    /**
     * Builds the production greenfield phase-loop factory (C3, ADR-0012): the
     * {@link GreenfieldDriver.PhaseLoopFactory} the {@link GreenfieldDriver} runs each
     * {@link com.srk.codingagent.workflow.GreenfieldPhase} through. The one un-coverable step
     * (resolving SigV4 credentials and constructing the live {@link ModelClient}) happens once here;
     * the per-phase loop is then assembled from the gate-covered {@link ToolRegistryComposer}, which
     * decides &mdash; testably &mdash; the phase-scoped tool registry (pre-approval phases withhold
     * the Class-X source tools, AC-1.4) and the per-phase greenfield system prompt. Each phase gets
     * its own {@link AgentLoop} (different registry + prompt); they share the live {@link ModelClient},
     * permission gate, log, and compaction seam.
     *
     * @param config         the resolved configuration; must not be {@code null}.
     * @param workspaceRoot  the target repository directory the file tools are rooted at; must not
     *                       be {@code null}.
     * @param sessionLineage the session lineage / repo key / origin session the gate, memory, and
     *                       sub-agent orchestrator are scoped to; non-blank.
     * @param log            the open session event log; must not be {@code null}.
     * @param approver       the approval seam the gate uses; must not be {@code null}.
     * @param sessions       the session store the sub-agent orchestrator opens child logs from;
     *                       must not be {@code null}.
     * @return a phase-loop factory yielding a phase-scoped {@link AgentLoop} turn per greenfield
     *         phase; never {@code null}.
     * @throws NullPointerException if a required argument is {@code null}.
     * @throws com.srk.codingagent.model.credentials.CredentialResolutionException if no usable SigV4
     *         credentials can be resolved (AC-8.9 → exit 4).
     */
    public GreenfieldDriver.PhaseLoopFactory createGreenfieldPhaseLoopFactory(
            ResolvedConfig config, Path workspaceRoot, String sessionLineage, EventLog log,
            Approver approver, SessionStore sessions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(approver, "approver");
        Objects.requireNonNull(sessions, "sessions");

        ToolRegistryComposer composer = composer(config, workspaceRoot, log, approver, sessions,
                sessionLineage);
        return phaseLoopFactory(composer, config, log, sessionLineage, approver, sessions);
    }

    /**
     * Builds the production greenfield workflow driver (C3, ADR-0012, T-3.2, DCR-1): the
     * {@link GreenfieldDriver} wired over all three of its seams from the gate-covered
     * {@link ToolRegistryComposer} — the phase-loop factory (phase-scoped registry + per-phase
     * prompt; the pre-approval phases withhold the Class-X source tools, AC-1.4), the
     * {@link GreenfieldDriver.PhaseArtifactWriter} that authors each pre-approval phase's deliverable
     * to its design-doc artifact in code on the phase {@code END_TURN} (DCR-1; driver-guaranteed
     * persistence, AC-1.2/AC-2.1 — the {@code write_artifact} tool stays registered but is no longer
     * the persistence path), and the {@link ArtifactApprovalGate} that records the approval timestamp
     * into each phase's artifact (AC-1.5) and enforces task-breakdown traceability over the
     * driver-written tasks artifact (AC-2.5). The one un-coverable step (credentials &rarr; Bedrock
     * client &rarr; {@link ModelClient}) happens once in the composer this assembles; all three seams
     * are then built from that gate-covered composer, so the driver-authored-persistence +
     * timestamp-recording + traceability + phase-gating contracts are pinned by unit tests on the
     * composer, not lost in this excluded factory.
     *
     * @param config         the resolved configuration; must not be {@code null}.
     * @param workspaceRoot  the target repository directory the file tools and artifact store are
     *                       rooted at (AC-6.2); must not be {@code null}.
     * @param sessionLineage the session lineage / repo key / origin session; non-blank.
     * @param log            the open session event log; must not be {@code null}.
     * @param approver       the permission-gate approval seam the phase loops use; must not be
     *                       {@code null}.
     * @param sessions       the session store the sub-agent orchestrator opens child logs from; must
     *                       not be {@code null}.
     * @param decision       the per-round developer approval decision the timestamped gate wraps (an
     *                       interactive prompt in the REPL, a deny-all in one-shot); must not be
     *                       {@code null}.
     * @param turnSource     the per-turn developer-input source for the multi-turn phase dialogue
     *                       (DCR-2): the REPL's input supplier in the interactive path (the developer
     *                       types each refining turn), or an end-of-input source in one-shot (no
     *                       terminal — the phase pauses awaiting approval after its first turn); must
     *                       not be {@code null}.
     * @return a fully-wired greenfield driver over its four seams; never {@code null}.
     * @throws NullPointerException if a required argument is {@code null}.
     * @throws com.srk.codingagent.model.credentials.CredentialResolutionException if no usable SigV4
     *         credentials can be resolved (AC-8.9 → exit 4).
     */
    public GreenfieldDriver createGreenfieldDriver(
            ResolvedConfig config, Path workspaceRoot, String sessionLineage, EventLog log,
            Approver approver, SessionStore sessions, ArtifactApprovalGate.ApprovalDecision decision,
            GreenfieldDriver.DeveloperTurnSource turnSource) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(approver, "approver");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(turnSource, "turnSource");

        ToolRegistryComposer composer = composer(config, workspaceRoot, log, approver, sessions,
                sessionLineage);
        GreenfieldDriver.PhaseLoopFactory phaseLoops =
                phaseLoopFactory(composer, config, log, sessionLineage, approver, sessions);
        // DCR-3 / AC-7.6: the production mid-flow-resume seam re-derives the resumable phase-state
        // from the target repo's on-disk approval-stamped artifacts at the start of each run, sharing
        // the SAME AC-1.5 stamp-detection the D13 clobber guard uses (GreenfieldArtifactStore over the
        // target workspaceRoot — one durable on-disk fact). The re-derivation LOGIC itself lives in
        // the coverage-counted GreenfieldPhaseState; this excluded factory only wires the probe.
        GreenfieldArtifactStore artifactStore = new GreenfieldArtifactStore(workspaceRoot);
        GreenfieldDriver.PhaseStateReconstructor reconstructor =
                () -> GreenfieldPhaseState.reconstruct(resumeProbe(artifactStore));
        return new GreenfieldDriver(phaseLoops, composer.greenfieldArtifactWriter(),
                composer.greenfieldApprovalGate(decision), turnSource, reconstructor);
    }

    /**
     * The production mid-flow-resume probe (DCR-3, AC-7.6): reads, per greenfield artifact, whether
     * it carries the AC-1.5 approval stamp and its current content, from the target repo's
     * {@link GreenfieldArtifactStore}. {@link GreenfieldArtifactStore#isApprovalStamped(String)} is
     * the SAME stamp-detection the D13 clobber guard uses, so resume re-derivation and clobber
     * protection share one durable on-disk fact rather than duplicating the detection.
     */
    private static GreenfieldPhaseState.Probe resumeProbe(GreenfieldArtifactStore store) {
        return new GreenfieldPhaseState.Probe() {
            @Override
            public boolean isApproved(GreenfieldArtifact artifact) {
                return store.isApprovalStamped(artifact.relativePath());
            }

            @Override
            public String content(GreenfieldArtifact artifact) {
                return store.read(artifact.relativePath()).orElse("");
            }
        };
    }

    /**
     * Builds the {@link GreenfieldDriver.PhaseLoopFactory} over an already-built composer: each phase
     * gets its own {@link AgentLoop} from the gate-covered composer (phase-scoped registry +
     * per-phase prompt), sharing the live {@link ModelClient}, gate, log, and compaction seam. Shared
     * by {@link #createGreenfieldPhaseLoopFactory} (T-3.1) and {@link #createGreenfieldDriver} (T-3.2)
     * so the per-phase loop wiring is assembled once.
     *
     * <p><b>The terminal IMPLEMENT phase drives the implement loop (T-3.3).</b> For every pre-approval
     * phase the phase turn is the bare phase-scoped {@link AgentLoop} ({@code loop::run}). For the
     * terminal {@link GreenfieldPhase#IMPLEMENT} phase, the turn is instead the gate-covered
     * implement-one-task-at-a-time loop ({@link ToolRegistryComposer#greenfieldImplementLoopTurn}),
     * which runs each task's implementation through that same full-toolset implementation loop and
     * verifies each task before the next (reusing the T-1.4 verify loop, AC-3.1/3.2/3.3/3.4). The
     * implement orchestration is assembled in the gate-covered composer (not this JaCoCo-excluded
     * factory); the factory only selects the implement-loop turn for the terminal phase.
     */
    private GreenfieldDriver.PhaseLoopFactory phaseLoopFactory(ToolRegistryComposer composer,
            ResolvedConfig config, EventLog log, String sessionLineage, Approver approver,
            SessionStore sessions) {
        return phase -> {
            ToolRegistry tools = composer.greenfieldRegistry(phase);
            // DCR-2 (D1): the greenfield phase loops use the greenfield-budget ModelClient
            // (inferenceConfig.maxTokens = 16384) so a large deliverable is not truncated.
            AgentLoop loop = assembleLoop(composer, composer.greenfieldModelClient(), config, log,
                    sessionLineage, approver, sessions, tools, composer.greenfieldSystemPrompt(phase));
            if (phase.isTerminal()) {
                // AC-3.1/3.2/3.3/3.4: the IMPLEMENT phase's turn is the implement-one-task-at-a-time
                // loop, which drives each task's implementation through the full-toolset loop and
                // verifies each before the next (reusing the T-1.4 verify loop). The per-task
                // implementation turn is this same implementation-phase AgentLoop.
                return composer.greenfieldImplementLoopTurn(loop::run);
            }
            return loop::run;
        };
    }

    /**
     * Builds the gate-covered {@link ToolRegistryComposer} over the live {@link ModelClient} and the
     * shared lineage-scoped collaborators. The one un-coverable step (credentials → Bedrock client →
     * {@link ModelClient}) happens here; the composer it returns is constructible and exercisable
     * without a live AWS call, so its registry/prompt decisions are pinned by unit tests.
     *
     * <p>INV-10: the parent grant store is shared by the gate and the sub-agent orchestrator (via
     * the composer). ADR-0005: the boundary clock + child-session-id supplier are captured at the
     * boundary (no {@code UUID.randomUUID()} inside the orchestration). The {@link MemoryStore} is
     * shared by the tool registry and the compaction learning harvest (AC-18.5).
     */
    private ToolRegistryComposer composer(ResolvedConfig config, Path workspaceRoot, EventLog log,
            Approver approver, SessionStore sessions, String sessionLineage) {
        BedrockCredentials credentials = credentialResolver.resolve(config.awsProfile());
        BedrockRuntimeClient bedrock = clientFactory.create(credentials, config.region());
        ModelClient modelClient = new ModelClient(bedrock);
        // DCR-2 (D1, ADR-0012 § 2.1): the greenfield phase loops use a ModelClient over the SAME
        // Bedrock wire path whose requests carry inferenceConfig.maxTokens = 16384, so a full
        // requirements/design/tasks deliverable is not truncated at the backend's default 4096
        // output cap. The brownfield/one-shot loops keep the uncapped client.
        ModelClient greenfieldModelClient = ModelClient.forGreenfield(bedrock);
        GrantStore parentGrants = GrantStore.forSession(sessionLineage);
        MemoryStore memoryStore = MemoryStore.forUserHome();
        Supplier<String> clock = () -> Instant.now().toString();
        Supplier<String> childSessionIds = () -> UUID.randomUUID().toString();
        return new ToolRegistryComposer(
                modelClient, greenfieldModelClient, config, workspaceRoot, log, memoryStore,
                sessions, parentGrants, approver, sessionLineage, sessionLineage, clock,
                childSessionIds);
    }

    /**
     * Assembles an {@link AgentLoop} from an already-built composer, the given tool registry, and
     * the given system-prompt blocks &mdash; the shared loop-wiring the one-shot/REPL (brownfield)
     * and the greenfield phase loops both use. The permission gate, budget guard, compaction seam,
     * and disposer are wired identically; only the registry and system prompt differ (brownfield's
     * full registry + brownfield playbook vs. greenfield's phase-scoped registry + per-phase
     * greenfield playbook).
     */
    private AgentLoop assembleLoop(ToolRegistryComposer composer, ModelClient modelClient,
            ResolvedConfig config, EventLog log, String sessionLineage, Approver approver,
            SessionStore sessions, ToolRegistry tools, List<String> systemPrompt) {
        // Reuse the composer's shared collaborators so the gate shares the orchestrator's grant
        // store (INV-10), the compaction harvest shares the tool registry's memory store (AC-18.5),
        // and every event draws its timestamp from the one boundary clock (ADR-0005). The passed
        // modelClient is the uncapped one for brownfield/one-shot loops and the greenfield-budget
        // one (inferenceConfig.maxTokens = 16384, DCR-2 — D1) for greenfield phase loops; both run
        // over the SAME Bedrock wire path (D2-safe).
        GrantStore parentGrants = composer.parentGrants();
        MemoryStore memoryStore = composer.memoryStore();
        Supplier<String> clock = composer.clock();
        PermissionGate gate = new PermissionGate(config.permissionMode(), parentGrants, approver);
        // ADR-0006 / § 6.A.1: thread the live session's tool definitions into the compaction seam.
        // The summarizer Converse call replays the original transcript verbatim — which carries
        // toolUse/toolResult blocks — so it must present the same toolConfig the session offers, or
        // Bedrock rejects it ("toolConfig must be defined when using toolUse/toolResult blocks").
        CompactionSeam compaction = compactionSeam(
                modelClient, config, log, memoryStore, sessions, sessionLineage, clock,
                tools.toToolConfiguration());

        // ADR-0005: the loop draws every event's timestamp from this boundary clock.
        // US-19/ADR-0006: the disposer reduces oversized tool/command output for context at
        // the configured inline cap (NFR-OUTPUT-MAX-INLINE) while the log keeps the full copy.
        // ADR-0002/0006 (T-2.1): the real budget guard replaces BudgetGuard.NONE. The active
        // model's context window comes from its capability profile (C5 — the live default
        // us.anthropic.claude-opus-4-8 resolves to the Claude window; an unknown id degrades to
        // the conservative safe-minimum), and the 0.85 trigger fraction from config; the guard
        // then fires COMPACT when measured usage.inputTokens >= 0.85 x window (AC-18.1). The
        // guard arithmetic + profile resolution are tested units (TokenBudgetGuard /
        // ModelCapabilityProfile); this composition root only wires them.
        ModelCapabilityProfile profile = ModelCapabilityProfile.forModelId(
                config.modelId(), CONSERVATIVE_DEFAULT_CONTEXT_WINDOW_TOKENS);
        // ADR-0012: the loop carries the system-prompt blocks the caller assembled in the
        // gate-covered ToolRegistryComposer — the brownfield playbook + memory index for a
        // one-shot/REPL run (T-1.6, T-2.4 D5), or the per-phase greenfield playbook + memory index
        // for a greenfield phase loop (T-3.1). ADR-0007 (T-2.4 D5): the assembled blocks include the
        // always-loaded two-tier memory index (loaded fresh per session start via
        // MemoryStore.loadIndexes, INV-14); an empty index adds no memory section. Keeping the
        // playbook + index-load/render/combine logic in the composer (NOT this JaCoCo-excluded
        // factory) lets a unit test pin that the prompt reaches the model; the factory only carries
        // the assembled blocks to the loop's `system` arg.
        // ADR-0006 (T-2.8): the loop carries the live compaction seam so that on the budget guard's
        // COMPACT (AC-18.1), the loop summarizes->derives->continues in the derived session (T14)
        // instead of surfacing-and-stopping. The summarize/derive/harvest logic is the tested
        // CompactingSeam + Compactor units; this composition root only wires them.
        return new AgentLoop(modelClient, tools, gate, log,
                clock, TokenBudgetGuard.forConfig(config, profile), compaction,
                OutputDisposer.forConfig(config), config.modelId(),
                systemPrompt);
    }

    /**
     * Builds the live {@link CompactionSeam} (T-2.8): a {@link CompactingSeam} over a real
     * {@link Compactor} threaded with the live session identity and a boundary-captured
     * derived-session-id supplier (ADR-0005).
     *
     * <p><b>Summarizer model (ADR-0006).</b> The summary Converse call uses the configured cheaper
     * summarizer model ({@link ResolvedConfig#summarizerModelId()}) when set, else the main model
     * ({@link ResolvedConfig#modelId()}).
     *
     * <p><b>Learning harvest (AC-18.5), sharing the propose-and-approve path.</b> The Compactor's
     * {@link LearningHarvester} is wired as a {@link MemoryLearningHarvester} over the SAME
     * {@link MemoryStore} the tool registry uses and a {@link LearningProposer} built from the same
     * boundary clock + session identity — the one propose-and-approve path the explicit
     * {@code write_memory} tool also funnels through (no duplicate wiring). The extractor is
     * {@link LearningExtractor#NONE} (v1 ships no summary-extraction heuristic — T-2.5 left it a
     * seam; inventing one is out of scope), and the one-shot approver is {@link LearningApprover#DENY_ALL}
     * (the safe default when no developer terminal is present, AC-21.4/INV-13 — never auto-extract).
     * So the harvest seam runs before archive on a live compaction (AC-18.5) yet persists nothing
     * at v1, the correct anti-poisoning stance. When the interactive REPL wires a real approver +
     * extractor (a later task), durable learnings flow through this same path.
     *
     * <p><b>Summarizer toolConfig (§ 6.A.1 wire rule).</b> The {@code sessionToolConfig} is the
     * live session's rendered tool definitions ({@code ToolRegistry.toToolConfiguration()}); the
     * Compactor carries it on the summary Converse call so a replayed transcript containing
     * {@code toolUse}/{@code toolResult} blocks is wire-valid — Bedrock rejects a request whose
     * {@code messages[]} carry tool blocks while {@code toolConfig} is absent. The tool blocks are
     * replayed verbatim (best summary fidelity); only the request's {@code toolConfig} is now set.
     */
    private CompactionSeam compactionSeam(
            ModelClient modelClient, ResolvedConfig config, EventLog log, MemoryStore memoryStore,
            SessionStore sessions, String sessionLineage, Supplier<String> clock,
            ToolConfiguration sessionToolConfig) {
        String summarizerModelId = config.summarizerModelId() == null
                ? config.modelId()
                : config.summarizerModelId();
        SessionReplay replay = new SessionReplay();
        LearningProposer proposer = new LearningProposer(
                memoryStore, log, LearningApprover.DENY_ALL, clock, sessionLineage, sessionLineage);
        LearningHarvester harvester = new MemoryLearningHarvester(LearningExtractor.NONE, proposer);
        Compactor compactor = new Compactor(
                modelClient, sessions, replay, clock, summarizerModelId,
                LIVE_COMPACTION_RECENT_TAIL_TURNS, sessionToolConfig, harvester);
        // ADR-0005: the derived session id is captured at this boundary (never inside the
        // orchestration). v1 derives a timestamp-suffixed id from the lineage + boundary clock so
        // the derived log path is deterministic and differs from the original (INV-4).
        Supplier<String> derivedSessionIds =
                () -> sessionLineage + "-derived-" + UUID.randomUUID();
        return new CompactingSeam(
                compactor, sessions, replay, sessionLineage, sessionLineage, derivedSessionIds);
    }
}
