package com.srk.codingagent.cli;

import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.BudgetGuard;
import com.srk.codingagent.memory.MemoryIndexLine;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.subagent.ChildAgentLoopFactory;
import com.srk.codingagent.subagent.SubAgentOrchestrator;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.EditFileTool;
import com.srk.codingagent.tool.GlobTool;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import com.srk.codingagent.tool.GrepTool;
import com.srk.codingagent.tool.ListTool;
import com.srk.codingagent.tool.ReadFileTool;
import com.srk.codingagent.tool.RunCommandTool;
import com.srk.codingagent.tool.SpawnSubAgentTool;
import com.srk.codingagent.tool.ToolHandler;
import com.srk.codingagent.tool.ToolRegistry;
import com.srk.codingagent.tool.WriteArtifactTool;
import com.srk.codingagent.tool.WriteFileTool;
import com.srk.codingagent.tool.memory.ReadMemoryTool;
import com.srk.codingagent.tool.memory.WriteMemoryTool;
import com.srk.codingagent.workflow.ArtifactApprovalGate;
import com.srk.codingagent.workflow.BrownfieldPlaybook;
import com.srk.codingagent.workflow.GreenfieldDriver;
import com.srk.codingagent.workflow.GreenfieldPhase;
import com.srk.codingagent.workflow.GreenfieldPlaybook;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The production tool-registry composition root, extracted from the (JaCoCo-excluded)
 * {@link AgentLoopFactory} so the full set of tools a live {@code codingagent} run offers the
 * model is exercised under the coverage gate (C7, 02-architecture &sect; 1.2). The factory's one
 * genuinely un-coverable step is constructing a live {@link ModelClient} from real SigV4
 * credentials; everything <em>downstream</em> of that — assembling the file/search/run tools
 * (C9/C10), the memory tools (C12, ADR-0007), and the sub-agent tool (C13, ADR-0010) into a
 * {@link ToolRegistry} — is plain wiring with no live dependency, so it lives here where a test
 * drives it with a {@link ModelClient} over a scripted Bedrock double and stores rooted at a
 * temporary directory.
 *
 * <p><b>Why a separate composer (the T-2.7 gap).</b> The earlier factory registered only the
 * seven file/search/run tools and never the sub-agent or memory tools, and never instantiated the
 * {@link ChildAgentLoopFactory} production seam — a live-vs-mocked gap no unit test caught because
 * the factory was coverage-excluded. Moving the composition here makes the live registry the SUT
 * of {@code LiveToolRegistryCompositionTest}: the wiring contract (which tools the model sees,
 * with which operation classes) is now pinned by a gate-covered test, not by an excluded class.
 *
 * <p><b>Also the system-prompt assembly seam (the T-2.4 D5 gap).</b> For the same coverage-gate
 * reason, {@link #parentSystemPrompt()} assembles the parent loop's system-prompt blocks here —
 * the brownfield playbook priming (C3, ADR-0012) PLUS the always-loaded memory index (ADR-0007
 * "Index + selective load"), loaded fresh per session start via {@link MemoryStore#loadIndexes}
 * (INV-14). The factory previously handed the loop only the static playbook and never injected the
 * memory index, so a live session could not see which curated entries existed and had to guess a
 * slug for {@code read_memory}. Keeping the assembly in this gate-covered seam lets a unit test pin
 * that index content reaches the prompt; the factory now only carries the assembled blocks to the
 * loop.
 *
 * <p><b>The ten production tools.</b> read/grep/glob/list (Class R), write/edit (Class X),
 * run_command (Class X) — the existing seven — plus:
 * <ul>
 *   <li><b>{@code read_memory}</b> (Class R, ADR-0007/C12) over the two-tier {@link MemoryStore}
 *       under {@code ~/.codingagent};</li>
 *   <li><b>{@code write_memory}</b> (Class X, ADR-0007/C12) over the same store, the session
 *       {@link EventLog} (the {@code MEMORY_WRITE} provenance event, AC-12.4), the boundary clock,
 *       the origin session, and the repo key;</li>
 *   <li><b>{@code spawn_subagent}</b> (Class X, ADR-0010/C13) over a {@link SubAgentOrchestrator}
 *       whose {@link ChildAgentLoopFactory} runs each child as its OWN nested {@link AgentLoop}
 *       over the SAME {@link ModelClient} wire path the parent uses (D2-safe by construction), with
 *       the child's OWN session log, a fresh no-inherited-grants gate (AC-10.6/INV-10), and a child
 *       tool registry that does NOT itself offer {@code spawn_subagent} (no unbounded recursive
 *       spawning at the v1 N=1 stance, ADR-0010 / OOS "N&gt;1 sub-agent parallelism (config seam
 *       only)").</li>
 * </ul>
 *
 * <p>The child loop is built with {@link BudgetGuard#NONE} (no compaction in the child at v1,
 * matching the no-compaction wiring the parent's seam documents) and the disposer the parent
 * uses; its model id is the one the orchestrator resolves from the spawn spec (the parent's model
 * unless the spawn overrides it, AC-17.2).
 */
final class ToolRegistryComposer {

    private final ModelClient modelClient;
    private final ResolvedConfig config;
    private final Path workspaceRoot;
    private final EventLog log;
    private final MemoryStore memoryStore;
    private final SessionStore sessionStore;
    private final GrantStore parentGrants;
    private final Approver approver;
    private final String repoKey;
    private final String originSession;
    private final Supplier<String> clock;
    private final Supplier<String> childSessionIds;

    /**
     * Creates a composer over the collaborators the live registry's tools need. Every argument is
     * constructible without a live AWS call (the one live step — building the {@link ModelClient}
     * from real credentials — happens in {@link AgentLoopFactory} and the already-built client is
     * passed in), so this composer and its product are unit-testable against temporary stores.
     *
     * @param modelClient     the Converse adapter the child sub-agent loop reuses (the SAME wire
     *                        path the parent uses — D2-safe by construction); must not be
     *                        {@code null}.
     * @param config          the resolved configuration (model id, command timeout, subAgentMax,
     *                        inline cap, permission mode); must not be {@code null}.
     * @param workspaceRoot   the repository directory the file/search/run tools are rooted at
     *                        (AC-6.2); must not be {@code null}.
     * @param log             the open session event log — the parent's log, used for the
     *                        {@code write_memory} provenance event and as the sub-agent
     *                        orchestrator's PARENT log (SUBAGENT_SPAWN/RESULT, AC-17.5); must not be
     *                        {@code null}.
     * @param memoryStore     the two-tier memory store (ADR-0007) the memory tools read/write;
     *                        must not be {@code null}.
     * @param sessionStore    the session store the orchestrator opens each child's OWN log from and
     *                        writes the child's SPAWNED_BY meta to (ADR-0010 Notes — the child never
     *                        shares the parent's log); must not be {@code null}.
     * @param parentGrants    the parent's grant store, used ONLY to mint each child's fresh empty
     *                        store (AC-10.6/INV-10); must not be {@code null}.
     * @param approver        the approval seam the child gate uses; must not be {@code null}.
     * @param repoKey         the repository key the memory PROJECT tier and the child sessions are
     *                        scoped to; non-blank.
     * @param originSession   the session id recorded as the {@code write_memory} provenance origin
     *                        (AC-12.2); non-blank.
     * @param clock           the boundary clock (ADR-0005) for the memory-write event timestamp and
     *                        the orchestrator's parent SUBAGENT_* event timestamps; must not be
     *                        {@code null}.
     * @param childSessionIds the boundary-captured source of child session ids the orchestrator
     *                        mints child sessions with (ADR-0005 — never {@code UUID.randomUUID()}
     *                        inside the orchestrator); must not be {@code null}.
     * @throws NullPointerException     if a required reference argument is {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} or {@code originSession} is blank.
     */
    ToolRegistryComposer(
            ModelClient modelClient,
            ResolvedConfig config,
            Path workspaceRoot,
            EventLog log,
            MemoryStore memoryStore,
            SessionStore sessionStore,
            GrantStore parentGrants,
            Approver approver,
            String repoKey,
            String originSession,
            Supplier<String> clock,
            Supplier<String> childSessionIds) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.config = Objects.requireNonNull(config, "config");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.log = Objects.requireNonNull(log, "log");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.parentGrants = Objects.requireNonNull(parentGrants, "parentGrants");
        this.approver = Objects.requireNonNull(approver, "approver");
        this.repoKey = requireNonBlank(repoKey, "repoKey");
        this.originSession = requireNonBlank(originSession, "originSession");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.childSessionIds = Objects.requireNonNull(childSessionIds, "childSessionIds");
    }

    /**
     * The live {@link ModelClient} the composer was built over (the one un-coverable step's
     * product). Exposed so {@link AgentLoopFactory} assembles every loop &mdash; the brownfield
     * one-shot/REPL loop and each greenfield phase loop &mdash; over the SAME wire path (D2-safe by
     * construction), and the sub-agent child loop shares it too.
     *
     * @return the model client; never {@code null}.
     */
    ModelClient modelClient() {
        return modelClient;
    }

    /**
     * The lineage-scoped parent {@link GrantStore} the composer was built over. Exposed so the
     * loop's permission gate and the sub-agent orchestrator share the SAME store (INV-10): the gate
     * consults it for remembered grants and the orchestrator mints each child's fresh empty store
     * from it.
     *
     * @return the parent grant store; never {@code null}.
     */
    GrantStore parentGrants() {
        return parentGrants;
    }

    /**
     * The two-tier {@link MemoryStore} the composer was built over. Exposed so the loop's compaction
     * learning-harvest shares the SAME store the memory tools and system-prompt index use (AC-18.5
     * reuses the propose-and-approve path, not a duplicate).
     *
     * @return the memory store; never {@code null}.
     */
    MemoryStore memoryStore() {
        return memoryStore;
    }

    /**
     * The boundary clock (ADR-0005) the composer was built over. Exposed so the loop draws every
     * event's timestamp from the SAME boundary clock the memory-write and sub-agent events use (the
     * loop never calls {@code Instant.now()} itself).
     *
     * @return the boundary clock; never {@code null}.
     */
    Supplier<String> clock() {
        return clock;
    }

    /**
     * Composes the live tool registry the parent agent loop offers the model: the ten tools the
     * model sees on a real {@code codingagent} run (C7).
     *
     * @return the composed registry; never {@code null}.
     */
    ToolRegistry parentRegistry() {
        List<ToolHandler> handlers = new ArrayList<>(fileSearchRunTools(workspaceRoot));
        handlers.add(new ReadMemoryTool(memoryStore, repoKey));
        handlers.add(new WriteMemoryTool(memoryStore, log, clock, originSession, repoKey));
        handlers.add(new SpawnSubAgentTool(orchestrator()));
        return ToolRegistry.of(handlers);
    }

    /**
     * Composes the phase-scoped tool registry a greenfield run offers the model in a given
     * {@link GreenfieldPhase} (C3, ADR-0012 greenfield side). This is the gate-covered seam that
     * <em>structurally</em> enforces AC-1.4: while in a pre-approval phase
     * ({@link GreenfieldPhase#isPreApproval()} &mdash; requirements/design/tasks), the registry
     * <em>withholds</em> the Class-X source tools ({@code write_file}/{@code edit_file}/
     * {@code run_command}), so the model cannot execute a Class X operation against source files
     * (AC-1.4) &mdash; the tools are simply absent from the {@code toolConfig}, not merely denied at
     * the gate. The implementation phase, reached only after the design + task breakdown are
     * approved (AC-2.3), gets the full live toolset (the same ten tools {@link #parentRegistry()}
     * offers a brownfield run).
     *
     * <p><b>Why withholding, not gate-denial.</b> The permission gate (C8) denies a Class-X tool
     * the model <em>calls</em>; but a denial still surfaces the tool to the model, lets it attempt
     * the call, and depends on the gate's mode/grant state being correct. Withholding the tool
     * makes the safety AC a property of the toolset the model ever sees, independent of permission
     * mode &mdash; the same structural-withholding discipline the child sub-agent registry uses to
     * keep {@code spawn_subagent} out of a child (no unbounded recursion, ADR-0010). A unit test
     * pins, under the coverage gate, that the pre-approval registry contains no source-write tool.
     *
     * <p>The read-only explore tools ({@code read_file}/{@code grep}/{@code glob}/{@code list},
     * Class R) and {@code read_memory} (Class R) are available in every phase &mdash; reads are
     * always safe (RD-4) and let the agent explore and recall during the requirements/design
     * dialogue. The pre-approval registry deliberately also withholds {@code write_memory} and
     * {@code spawn_subagent} (both Class X; a child could itself write source), keeping the
     * pre-approval surface read-only.
     *
     * @param phase the greenfield phase whose tool registry is needed; must not be {@code null}.
     * @return the phase-scoped registry: read-only tools for a pre-approval phase, the full live
     *         toolset for the implementation phase; never {@code null}.
     * @throws NullPointerException if {@code phase} is {@code null}.
     */
    ToolRegistry greenfieldRegistry(GreenfieldPhase phase) {
        if (Objects.requireNonNull(phase, "phase").isPreApproval()) {
            return preApprovalRegistry();
        }
        return parentRegistry();
    }

    /**
     * The registry the greenfield pre-approval phases (requirements/design/tasks) offer the model:
     * the four explore tools plus {@code read_memory} (all Class R) <em>plus</em> the
     * {@code write_artifact} design-doc write path (T-3.2). No general source-write Class-X tool
     * ({@code write_file}/{@code edit_file}/{@code run_command}) is present, so a source write is
     * structurally impossible during the pre-approval dialogue (AC-1.4); but the agent <em>can</em>
     * persist the requirements/design/tasks markdown into the target repo (AC-1.2/AC-2.1) through
     * {@code write_artifact}, which the {@link GreenfieldArtifactStore} confines to the target repo's
     * design-doc directory. ADR-0012 makes that design-markdown write the one write allowed before
     * the breakdown is approved ("the agent writes only design markdown … until the breakdown is
     * approved"); confining it to {@code design/} keeps it distinct from the withheld source-write
     * path, so a design-markdown write succeeds while a source write does not. Mirrors the
     * structural-withholding shape of {@link #childToolRegistry()}.
     */
    private ToolRegistry preApprovalRegistry() {
        List<ToolHandler> handlers = new ArrayList<>();
        handlers.add(new ReadFileTool(workspaceRoot));
        handlers.add(new GrepTool(workspaceRoot));
        handlers.add(new GlobTool(workspaceRoot));
        handlers.add(new ListTool(workspaceRoot));
        handlers.add(new ReadMemoryTool(memoryStore, repoKey));
        handlers.add(new WriteArtifactTool(new GreenfieldArtifactStore(workspaceRoot)));
        return ToolRegistry.of(handlers);
    }

    /**
     * Builds the greenfield approval gate that records the approval timestamp into each phase's
     * artifact and enforces task-breakdown traceability (T-3.2; AC-1.5, AC-2.5) over the developer's
     * per-phase decision (component C3, ADR-0012 greenfield side). This is the gate-covered seam that
     * realizes the {@link GreenfieldDriver.ApprovalGate} extension point — the driver's seam Javadoc
     * names T-3.2 as layering approval-timestamp recording here.
     *
     * <p>It is assembled here (not in the JaCoCo-excluded {@link AgentLoopFactory}/{@link Main}) for
     * the same gate-covered-seam reason the phase-scoped registry and per-phase prompt are: the
     * collaborators the gate needs — the target-repo root (so the artifact store writes into the
     * target project's design-doc directory, AC-6.2), and the boundary clock (the source of the
     * approval timestamp, ADR-0005) — already live on this composer, so a unit test pins the
     * timestamp-recording + traceability-enforcing contract under the coverage gate. The factory only
     * threads this gate (and the phase-loop factory) into the {@link GreenfieldDriver}.
     *
     * @param decision the underlying per-phase developer yes/no the gate wraps (the interactive
     *                 approval prompt in production, scripted in tests); must not be {@code null}.
     * @return the timestamp-recording, traceability-enforcing approval gate; never {@code null}.
     * @throws NullPointerException if {@code decision} is {@code null}.
     */
    GreenfieldDriver.ApprovalGate greenfieldApprovalGate(
            ArtifactApprovalGate.ApprovalDecision decision) {
        Objects.requireNonNull(decision, "decision");
        return new ArtifactApprovalGate(
                decision, new GreenfieldArtifactStore(workspaceRoot), clock);
    }

    /**
     * Assembles the parent agent loop's system-prompt blocks: the brownfield playbook priming
     * (C3, ADR-0012 — explore-before-edit / verify-after-change) PLUS, when memory exists, an
     * always-loaded memory-index block (ADR-0007 "Index + selective load" — both tiers' indexes
     * load into the system prompt at session start so the model has cheap awareness of which
     * curated entries it can pull in full via {@code read_memory(slug)}).
     *
     * <p><b>Why this assembly lives here (the T-2.4 D5 gap).</b> The factory previously handed the
     * loop only {@link BrownfieldPlaybook#systemPrompt()}; the memory index — built and maintained
     * on disk by {@code write_memory} and read by {@link MemoryStore#loadIndexes} — was never
     * injected into the live system prompt. A real-Bedrock session therefore had to GUESS a slug to
     * call {@code read_memory} and could not recall stored learnings. Like the tool composition
     * (the T-2.7 gap), this assembly must live in a NOT-coverage-excluded seam so a unit test pins
     * the contract that index content reaches the prompt; the factory ({@link AgentLoopFactory}) is
     * JaCoCo-excluded, so the same defect would recur if the logic lived there.
     *
     * <p><b>Re-read-fresh, not cached (INV-14, AC-14.2).</b> The index is loaded by calling
     * {@link MemoryStore#loadIndexes} at the moment this method runs — i.e. when the loop is
     * assembled for the run (session start). {@code MemoryStore} holds no masking cache (every
     * {@code loadIndexes} reads from disk), so an entry written on one session appears on the NEXT
     * session's prompt build; no caching is introduced here.
     *
     * <p><b>Empty index → no memory section (AC-14.3).</b> When {@code loadIndexes} returns empty
     * (neither tier has an {@code INDEX.md} yet), the returned blocks are exactly the playbook
     * blocks — no fabricated memory heading. The index block is appended only when at least one
     * entry exists.
     *
     * <p>The block sits in the cacheable static prefix (ADR-0006), alongside the playbook blocks and
     * the tool definitions, and renders the GLOBAL-then-PROJECT ordering {@code loadIndexes} returns:
     * a short heading explaining the entries can be retrieved in full via {@code read_memory(slug)},
     * then one {@code - [slug] description} line per index entry so the slug AND its one-line
     * description are visible to the model.
     *
     * @return the system-prompt blocks for the parent loop's {@code system} argument: the playbook
     *         blocks, plus the memory-index block when the index is non-empty; never {@code null} or
     *         empty.
     */
    List<String> parentSystemPrompt() {
        List<String> blocks = new ArrayList<>(BrownfieldPlaybook.systemPrompt());
        List<MemoryIndexLine> index = memoryStore.loadIndexes(repoKey);
        if (!index.isEmpty()) {
            blocks.add(renderMemoryIndexBlock(index));
        }
        return List.copyOf(blocks);
    }

    /**
     * Assembles the greenfield agent loop's system-prompt blocks for a given {@link GreenfieldPhase}
     * (C3, ADR-0012 greenfield side): the per-phase {@link GreenfieldPlaybook} blocks (the common
     * greenfield contract &mdash; requirements-before-source AC-1.1, ask-don't-write-source-early
     * AC-1.3, the no-source-write pre-approval rule AC-1.4, approval-before-implementation AC-2.3 &mdash;
     * plus the phase-specific block) PLUS, when memory exists, the same always-loaded memory-index
     * block {@link #parentSystemPrompt()} appends (ADR-0007 "Index + selective load", INV-14).
     *
     * <p><b>Why this assembly lives here (the T-2.4 D5 lesson, applied to greenfield).</b> Like the
     * brownfield prompt assembly, the greenfield prompt &mdash; the lever that primes the model
     * through the phase state machine and reinforces the no-source-write rule &mdash; must be
     * assembled in this NOT-coverage-excluded seam so a unit test pins that the greenfield prompt
     * (and the memory index) reaches the model's Converse request; the factory
     * ({@link AgentLoopFactory}) is JaCoCo-excluded, so the same live-vs-unit gap would recur if the
     * logic lived there. The factory only carries the assembled blocks to the loop's {@code system}
     * argument.
     *
     * <p>Re-read-fresh, not cached (INV-14, AC-14.2) and empty-index &rarr; no memory section
     * (AC-14.3): identical to {@link #parentSystemPrompt()} &mdash; the index is loaded fresh via
     * {@link MemoryStore#loadIndexes} and the index block is appended only when at least one entry
     * exists.
     *
     * @param phase the greenfield phase whose system prompt is needed; must not be {@code null}.
     * @return the system-prompt blocks for the greenfield loop's {@code system} argument in
     *         {@code phase}: the per-phase playbook blocks, plus the memory-index block when the
     *         index is non-empty; never {@code null} or empty.
     * @throws NullPointerException if {@code phase} is {@code null}.
     */
    List<String> greenfieldSystemPrompt(GreenfieldPhase phase) {
        List<String> blocks = new ArrayList<>(GreenfieldPlaybook.systemPrompt(phase));
        List<MemoryIndexLine> index = memoryStore.loadIndexes(repoKey);
        if (!index.isEmpty()) {
            blocks.add(renderMemoryIndexBlock(index));
        }
        return List.copyOf(blocks);
    }

    /**
     * Renders the always-loaded memory-index block (ADR-0007): a short heading naming the curated
     * entries and the {@code read_memory(slug)} retrieval path, then one {@code - [slug]
     * description} line per index entry (in the GLOBAL-then-PROJECT order {@code loadIndexes}
     * returns) so the model can see each slug and its one-line description and pick the right slug.
     */
    private static String renderMemoryIndexBlock(List<MemoryIndexLine> index) {
        StringBuilder block = new StringBuilder(
                "Curated memory. You have a memory of curated learnings from past work in this "
                        + "repository and across projects. The entries below are always available; "
                        + "when one looks relevant, retrieve its full content with "
                        + "read_memory(slug) before acting on the request. The available entries "
                        + "(slug and one-line description) are:");
        for (MemoryIndexLine line : index) {
            block.append("\n- [").append(line.slug()).append("] ").append(line.description());
        }
        return block.toString();
    }

    /**
     * Builds the sub-agent orchestrator (C13, ADR-0010) over the production child-loop seam: each
     * child runs its own nested {@link AgentLoop} over the SAME {@link ModelClient}, its OWN
     * session log, and a fresh no-inherited-grants gate.
     */
    private SubAgentOrchestrator orchestrator() {
        return new SubAgentOrchestrator(
                sessionStore,
                log,
                childLoopFactory(),
                parentGrants,
                config.permissionMode(),
                approver,
                repoKey,
                config.modelId(),
                childSessionIds,
                clock,
                config.subAgentMax(),
                SubAgentOrchestrator.DEFAULT_WALL_CLOCK_CAP);
    }

    /**
     * The production {@link ChildAgentLoopFactory} (ADR-0010 / {@code ChildAgentLoopFactory}
     * Javadoc): {@code () -> childLoop.run(context.prompt())} over a REAL nested {@link AgentLoop}.
     * The child loop reuses the parent's {@link ModelClient} (so its toolResult mapping is the
     * D2-fixed wire path by construction), runs with the child's OWN log and fresh gate from the
     * {@code ChildLoopContext}, the child's resolved model id, and a child tool registry that does
     * NOT include {@code spawn_subagent} (no unbounded recursive spawning at v1 N=1, ADR-0010).
     */
    private ChildAgentLoopFactory childLoopFactory() {
        return childContext -> {
            AgentLoop childLoop = new AgentLoop(
                    modelClient,
                    childToolRegistry(),
                    childContext.childGate(),
                    childContext.childLog(),
                    clock,
                    BudgetGuard.NONE,
                    OutputDisposer.forConfig(config),
                    childContext.modelId(),
                    null);
            return () -> childLoop.run(childContext.prompt());
        };
    }

    /**
     * The child sub-agent's tool registry: the file/search/run tools plus {@code read_memory}, but
     * NOT {@code write_memory} or {@code spawn_subagent}. The child reads memory but does not itself
     * remember (curated writes stay the developer-facing root session's lane, ADR-0007) nor
     * recursively spawn (the v1 N=1 stance, ADR-0010 / OOS).
     */
    private ToolRegistry childToolRegistry() {
        List<ToolHandler> handlers = new ArrayList<>(fileSearchRunTools(workspaceRoot));
        handlers.add(new ReadMemoryTool(memoryStore, repoKey));
        return ToolRegistry.of(handlers);
    }

    private List<ToolHandler> fileSearchRunTools(Path root) {
        List<ToolHandler> handlers = new ArrayList<>();
        handlers.add(new ReadFileTool(root));
        handlers.add(new GrepTool(root));
        handlers.add(new GlobTool(root));
        handlers.add(new ListTool(root));
        handlers.add(new WriteFileTool(root));
        handlers.add(new EditFileTool(root));
        handlers.add(new RunCommandTool(new CommandExecutor(root), config));
        return handlers;
    }

    private static String requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }
}
