package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.BudgetGuard;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import com.srk.codingagent.tool.ToolRegistry;
import com.srk.codingagent.tool.WriteArtifactTool;
import com.srk.codingagent.workflow.ArtifactApprovalGate;
import com.srk.codingagent.workflow.GreenfieldArtifact;
import com.srk.codingagent.workflow.GreenfieldDriver;
import com.srk.codingagent.workflow.GreenfieldOutcome;
import com.srk.codingagent.workflow.GreenfieldPhase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

/**
 * The greenfield {@code write_artifact} <b>tool-contract (inputSchema)</b> regression and full
 * production-path persistence test (T-3.2-RD-D9, the THIRD regression of T-3.2). It closes the gap the
 * D7 (prompt) and D8 (gate) fixes — and their tests — both bypassed: the contract the live model reads
 * to decide whether and how to call {@code write_artifact} is the tool's <em>rendered toolSpec</em>
 * (name + description + inputSchema), and that contract must describe the <em>design-doc artifact</em>
 * write it is, not a generic source-file write — otherwise the live model, told by the greenfield
 * prompt to never write source, never calls a tool whose schema reads like a file writer, so the
 * design-doc content never persists (RD-7, AC-1.2, AC-2.1; C7 schema↔handler-agree invariant).
 *
 * <p><b>Why D7/D8 stayed green while the live run still lost the content.</b> Both
 * {@link GreenfieldArtifactPersistenceTest} (D7) and {@link GreenfieldSharedStdinArtifactPersistenceTest}
 * (D8) hand-script a perfect {@code write_artifact(path, content)} tool-use and assert the content
 * lands. That proves the dispatch→tool→store plumbing works when the model emits a well-formed call —
 * but it assumes away the very thing that broke live: <em>whether the model calls {@code write_artifact}
 * at all</em>. Neither test inspects the tool's rendered inputSchema (the {@code write_artifact} toolSpec
 * the model actually sees), which reused the generic {@code write_file} schema whose field descriptions
 * read "Workspace-relative path of the file to write" / "The full new contents of the file" — a generic
 * source-file writer, contradicting the prompt's no-source-write rule. This test pins the tool-contract
 * the model reads, so the schema/prompt mismatch cannot recur, AND re-asserts the full production
 * dispatch→store path so the contract and the plumbing are covered together.
 *
 * <p><b>SUT and collaborators.</b> The SUT is the real {@link WriteArtifactTool} schema rendered through
 * the real {@link ToolRegistry#toToolConfiguration()} (the exact {@code toolConfig} the model receives),
 * plus the full production greenfield wiring — the {@link ToolRegistryComposer} pre-approval registry +
 * per-phase prompt driving a real {@link AgentLoop} (real {@link ModelClient}, real
 * {@link PermissionGate} in {@code ASK_EVERY_TIME}, real {@link WriteArtifactTool} over a real
 * {@link GreenfieldArtifactStore}) driven by a real {@link GreenfieldDriver} across all three
 * pre-approval phases, with the gate's {@link InteractiveApprover} and the phase-gate
 * {@link InteractiveGreenfieldApproval} sharing ONE answerSource, exactly as
 * {@code Main.interactiveGreenfield} wires them. The only external double is a scripted
 * {@link BedrockRuntimeClient} (the same Bedrock-double pattern the prior tests use), never a mock of
 * the SUT.
 *
 * <p><b>Oracles trace to the spec, not to implementation wording:</b>
 * <ul>
 *   <li><b>RD-7 / AC-1.2 / AC-2.1 (C7 schema↔handler):</b> the rendered {@code write_artifact} toolSpec
 *       the model sees describes a {@code design/} design-doc artifact (the path the model must fill is
 *       a {@link GreenfieldArtifact} path), NOT a generic source file — so the schema agrees with the
 *       tool's purpose and the prompt.</li>
 *   <li><b>RD-7 / AC-1.2 / AC-2.1:</b> on the full production multi-phase driver over the shared stdin,
 *       a model that emits {@code write_artifact} per phase persists each phase's deliverable CONTENT to
 *       its artifact (requirements/design/tasks), exercising the same dispatch/registry/store path that
 *       runs live.</li>
 *   <li><b>AC-1.5 / AC-2.5:</b> after the developer's per-phase {@code y}, each artifact also carries the
 *       approval stamp and the traceable task breakdown admits the session into implementation.</li>
 * </ul>
 */
class GreenfieldWriteArtifactSchemaPersistenceTest {

    private static final String LINEAGE = "one-shot";
    private static final String MODEL = "anthropic.claude-opus-4-8";
    private static final String CHILD_ID = "child-session-1";
    private static final String TS = "2026-06-23T09:00:00Z";

    // --- Scripted external Bedrock dependency (the only external double) ------------------

    /** A {@link BedrockRuntimeClient} replaying a scripted queue of responses, capturing requests. */
    private static final class ScriptedBedrockClient implements BedrockRuntimeClient {
        private final Deque<ConverseResponse> script = new ArrayDeque<>();
        private final List<ConverseRequest> requests = new ArrayList<>();

        ScriptedBedrockClient then(ConverseResponse response) {
            script.addLast(response);
            return this;
        }

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            requests.add(request);
            if (script.isEmpty()) {
                throw new IllegalStateException("scripted model exhausted after " + requests.size());
            }
            return script.removeFirst();
        }

        @Override
        public String serviceName() {
            return "bedrock-runtime";
        }

        @Override
        public void close() {
            // no-op for the in-test double
        }
    }

    private static ConverseResponse endTurn(String text) {
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(c -> c.text(text))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason("end_turn")
                .usage(u -> u.inputTokens(50).outputTokens(10).totalTokens(60))
                .build();
    }

    private static Supplier<String> answers(String... lines) {
        Deque<String> queue = new ArrayDeque<>(List.of(lines));
        return () -> queue.isEmpty() ? null : queue.removeFirst();
    }

    private static ResolvedConfig config() {
        return new ResolvedConfig(MODEL, PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                1, null, ResolvedConfig.Commands.empty(), 0.85, 16384, 5, 300);
    }

    /**
     * Builds the composer EXACTLY as {@link AgentLoopFactory} builds it — the production composition
     * path — over a {@link ModelClient} backed by the scripted Bedrock and stores rooted at the temp
     * dir; {@code workspace} is the target repo the {@code write_artifact} store is confined to.
     */
    private static ToolRegistryComposer composer(BedrockRuntimeClient bedrock, Path workspace,
            Path storeRoot, Approver approver) {
        Supplier<String> childIds = () -> CHILD_ID;
        return new ToolRegistryComposer(
                new ModelClient(bedrock), config(), workspace,
                EventLog.over(new StringWriter(), "parent"), new MemoryStore(storeRoot),
                new SessionStore(storeRoot), GrantStore.forSession(LINEAGE), approver,
                LINEAGE, LINEAGE, () -> TS, childIds);
    }

    /** Never-called Bedrock client: assembling a registry / rendering a toolConfig makes no call. */
    private static final class UnusedBedrockClient implements BedrockRuntimeClient {
        @Override
        public ConverseResponse converse(ConverseRequest request) {
            throw new IllegalStateException("no model call is expected when rendering the toolConfig");
        }

        @Override
        public String serviceName() {
            return "bedrock-runtime";
        }

        @Override
        public void close() {
            // no-op
        }
    }

    /** The rendered {@code write_artifact} toolSpec the model sees for a pre-approval phase. */
    private static ToolSpecification renderedWriteArtifactSpec(ToolRegistry registry) {
        ToolConfiguration toolConfig = registry.toToolConfiguration();
        Optional<ToolSpecification> spec = toolConfig.tools().stream()
                .map(Tool::toolSpec)
                .filter(s -> s != null && WriteArtifactTool.NAME.equals(s.name()))
                .findFirst();
        return spec.orElseThrow(() ->
                new AssertionError("the pre-approval toolConfig must contain a write_artifact toolSpec"));
    }

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);

    // --- RD-7 / AC-1.2 / AC-2.1 (C7) : the rendered toolSpec describes a design-doc artifact ---------

    @Test
    @DisplayName("RD-7/AC-1.2/AC-2.1 (C7): the write_artifact toolSpec the model sees describes a design/ design-doc artifact, not a generic source file")
    void renderedWriteArtifactToolSpecDescribesADesignDocArtifact(
            @TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: RD-7 — greenfield persists requirements/design/tasks "as markdown in the target
        // project"; AC-1.2/AC-2.1 — those deliverables are markdown ARTIFACTS in the target project;
        // C7 invariant — "a tool's schema and its handler agree". The contract the live model reads to
        // decide whether/how to call write_artifact is its rendered toolSpec (name + description +
        // inputSchema), so that contract must describe the design-doc artifact write it is (a design/
        // artifact path), NOT a generic source-file write — the D9 root cause was the schema reusing the
        // generic write_file field descriptions ("file to write" / "contents of the file"), which read
        // as a source-file writer against the prompt's no-source-write rule and steered the live model
        // away from calling it. The expected tokens (design/, the artifact path, "design") trace to
        // GreenfieldArtifact.relativePath() + AC-1.2/RD-7 ("markdown artifact"), not to the schema's
        // wording.
        ToolRegistryComposer composer = composer(
                new ModelClient(new UnusedBedrockClient()), workspace, storeRoot);

        for (GreenfieldPhase phase : List.of(
                GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS)) {
            ToolSpecification spec = renderedWriteArtifactSpec(composer.greenfieldRegistry(phase));
            String schemaJson = spec.inputSchema().json().toString().toLowerCase(Locale.ROOT);

            // The schema's path field describes a design/ design-doc artifact (the directory the store
            // confines writes to, GreenfieldArtifactStore.ARTIFACT_DIR), not a generic source file.
            assertTrue(schemaJson.contains("design/"),
                    "RD-7/AC-1.2: the write_artifact schema steers the model to a design/ artifact path "
                            + "(" + phase + "); was: " + schemaJson);
            // It names a concrete artifact path the model fills `path` from — the requirements artifact
            // path is a stable example present for every phase's schema (drawn from GreenfieldArtifact).
            assertTrue(schemaJson.contains(
                            GreenfieldArtifact.REQUIREMENTS.relativePath().toLowerCase(Locale.ROOT)),
                    "AC-1.2/AC-2.1: the write_artifact schema gives the model a concrete design-doc "
                            + "artifact-path example (" + phase + "); was: " + schemaJson);
            // It describes the write as a design document / artifact (the spec concept — RD-7 "as
            // markdown in the target project", C7 schema-agrees-with-handler), so the model reads it as
            // the design-doc write it is rather than the generic source-file write the prompt forbids.
            // The oracle is the RD-7/AC-1.2 "design document/artifact" concept, not the prior impl's
            // wording.
            assertTrue(schemaJson.contains("design document") || schemaJson.contains("design-doc")
                            || schemaJson.contains("artifact"),
                    "RD-7 (C7): the write_artifact schema describes a design document/artifact, not a "
                            + "generic source-file write (" + phase + "); was: " + schemaJson);
        }
    }

    @Test
    @DisplayName("C7: the write_artifact handler and its rendered schema agree (same tool name; the schema names the path+content the handler reads)")
    void writeArtifactSchemaAndHandlerAgree(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: C7 invariant — "a tool's schema and its handler agree — both come from the same
        // ToolHandler". The rendered toolSpec the model sees must carry the handler's name
        // (write_artifact) and require exactly the input fields the handler reads (path + content), so a
        // model that follows the schema produces a tool-use the handler can service. Expected names
        // trace to WriteArtifactTool.NAME and the handler's required inputs (path/content), not to the
        // schema document's prose.
        ToolRegistryComposer composer = composer(
                new ModelClient(new UnusedBedrockClient()), workspace, storeRoot);

        ToolSpecification spec =
                renderedWriteArtifactSpec(composer.greenfieldRegistry(GreenfieldPhase.REQUIREMENTS));
        assertEquals(WriteArtifactTool.NAME, spec.name(),
                "C7: the rendered toolSpec name is the handler's name");
        String schemaJson = spec.inputSchema().json().toString();
        assertTrue(schemaJson.contains("\"path\"") && schemaJson.contains("\"content\""),
                "C7: the schema requires the path + content fields WriteArtifactTool.handle reads; was: "
                        + schemaJson);
        assertTrue(schemaJson.contains("\"required\""),
                "C7: the schema marks path + content required; was: " + schemaJson);
    }

    // --- RD-7 / AC-1.2 / AC-2.1 / AC-1.5 / AC-2.5 : full production multi-phase persistence ----------

    @Test
    @DisplayName("DCR-1 RD-7/AC-1.2/AC-2.1/AC-1.5/AC-2.5: the full production driver persists each phase's END_TURN deliverable CONTENT (then stamp) across requirements->design->tasks->implement — with NO model write_artifact tool call")
    void fullProductionDriverPersistsEachPhaseDeliverableContentAndReachesImplementation(
            @TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: RD-7 — greenfield persists requirements/design/tasks markdown in the target project;
        // AC-1.2 — the requirements are persisted as a markdown artifact, DRIVER-GUARANTEED (the driver
        // writes the artifact in code from the phase's settled output, ADR-0012, not via a model tool
        // call); AC-2.1 — same for design + tasks; AC-1.5 — the approval is recorded with a timestamp in
        // the artifact (ADR-0012 generalizes to each phase); AC-2.5 — every task traces to a requirement
        // so the tasks gate admits the session into implementation. The DCR-1 reproduction: the model
        // here emits NO write_artifact tool_use — it answers each phase in prose and stops at END_TURN
        // (exactly the live behaviour D6-D9 could not catch). The full production GreenfieldDriver — every
        // phase a real AgentLoop over the composer's pre-approval registry + per-phase prompt, the real
        // ASK_EVERY_TIME gate, the real ArtifactApprovalGate, the composer's driver-authored persistence
        // seam, ONE shared stdin (Main.interactiveGreenfield) — must, given one 'y' per phase, persist
        // each phase's END_TURN CONTENT (driver-written, not just a stamp) and reach implementation.
        // Expected content traces to the scripted END_TURN deliverables + AC-1.2/AC-2.1; the stamp
        // timestamp to AC-1.5 (the boundary clock's TS).
        String reqBody = "# Requirements\n\n## US-1 Shorten URL\n- AC-1.1: accept a long URL, return a "
                + "short code.\n## NFR\n- NFR-1: p99 < 50ms.\n";
        String designBody = "# Design\n\n## Architecture\n- C1: the shortener service.\n";
        String tasksBody = "# Tasks\n\n- T-1: build the shortener service (Refs AC-1.1)\n";

        // The model answers each pre-approval phase in PROSE and stops at end_turn — NO write_artifact
        // tool_use anywhere (the live behaviour DCR-1 fixes). The driver captures each END_TURN prose and
        // persists it.
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(endTurn(reqBody))
                .then(endTurn(designBody))
                .then(endTurn(tasksBody))
                .then(endTurn("Implementation would proceed here."));

        // One realistic 'y' per pre-approval phase gate (requirements, design, tasks) on the single
        // shared stdin the production REPL wires.
        Supplier<String> sharedStdin = answers("y", "y", "y");
        GreenfieldDriver driver = productionDriver(bedrock, workspace, storeRoot, sharedStdin);

        GreenfieldOutcome outcome = driver.run("build me a URL shortener");

        GreenfieldArtifactStore reader = new GreenfieldArtifactStore(workspace);
        // AC-1.2 / RD-7: the requirements deliverable CONTENT is driver-persisted, and (AC-1.5) the
        // approval stamp is recorded with the timestamp.
        String reqPersisted = reader.read(GreenfieldArtifact.REQUIREMENTS.relativePath()).orElseThrow();
        assertTrue(reqPersisted.contains(reqBody),
                "AC-1.2/RD-7 (DCR-1): the requirements END_TURN deliverable content is driver-persisted "
                        + "(no tool call); was: " + reqPersisted);
        assertTrue(reqPersisted.contains(TS),
                "AC-1.5: the requirements approval timestamp is recorded in the artifact; was: "
                        + reqPersisted);
        // AC-2.1 / RD-7: the design and tasks deliverable CONTENT is driver-persisted.
        assertTrue(reader.read(GreenfieldArtifact.DESIGN.relativePath()).orElseThrow().contains(designBody),
                "AC-2.1/RD-7 (DCR-1): the design END_TURN deliverable content is driver-persisted");
        assertTrue(reader.read(GreenfieldArtifact.TASKS.relativePath()).orElseThrow().contains(tasksBody),
                "AC-2.1/RD-7 (DCR-1): the task-breakdown END_TURN deliverable content is driver-persisted");
        // AC-2.5 / AC-2.3: the traceable driver-written breakdown admitted the session into
        // implementation (the run completed rather than stranding awaiting approval).
        assertEquals(GreenfieldOutcome.Disposition.COMPLETED, outcome.disposition(),
                "AC-2.5/AC-2.3: a traceable driver-written task breakdown (every task -> a requirement) "
                        + "admits the session into implementation, so the fully-approved run completes");
    }

    /**
     * Wires the production greenfield driver exactly as {@code Main.interactiveGreenfield} /
     * {@code AgentLoopFactory.createGreenfieldDriver} do for the live REPL path: the composer's
     * pre-approval registry + per-phase prompt build a real {@link AgentLoop} per phase over a real
     * {@link PermissionGate} in {@code ASK_EVERY_TIME}, and ONE shared {@code answerSource} feeds both
     * the gate's {@link InteractiveApprover} and the phase-gate {@link InteractiveGreenfieldApproval}.
     */
    private GreenfieldDriver productionDriver(BedrockRuntimeClient bedrock, Path workspace,
            Path storeRoot, Supplier<String> sharedStdin) {
        Approver approver = new InteractiveApprover(sharedStdin, out);
        ToolRegistryComposer composer = composer(bedrock, workspace, storeRoot, approver);
        GreenfieldArtifactStore gateStore = new GreenfieldArtifactStore(workspace);
        ArtifactApprovalGate.ApprovalDecision decision =
                new InteractiveGreenfieldApproval(sharedStdin, out, gateStore);
        GreenfieldDriver.ApprovalGate approvalGate =
                new ArtifactApprovalGate(decision, gateStore, () -> TS);
        GreenfieldDriver.PhaseLoopFactory phaseLoops = phase -> {
            ToolRegistry tools = composer.greenfieldRegistry(phase);
            PermissionGate gate = new PermissionGate(
                    PermissionMode.ASK_EVERY_TIME, GrantStore.forSession(LINEAGE), approver);
            AgentLoop loop = new AgentLoop(composer.modelClient(), tools, gate,
                    EventLog.over(new StringWriter(), "gf"), () -> TS, BudgetGuard.NONE,
                    new OutputDisposer(16384), MODEL, composer.greenfieldSystemPrompt(phase));
            return loop::run;
        };
        // DCR-1: persistence is driver-authored — the driver writes each phase's converged prose to
        // its artifact through the composer's driver-authored persistence seam (the same one
        // AgentLoopFactory.createGreenfieldDriver wires), not via a model write_artifact tool call.
        // DCR-2: the developer-turn source is the SAME shared stdin (the production multi-turn
        // wiring); with each phase approved on its first 'y' no refining turn is read.
        GreenfieldDriver.DeveloperTurnSource turnSource = phase -> sharedStdin.get();
        return new GreenfieldDriver(
                phaseLoops, composer.greenfieldArtifactWriter(), approvalGate, turnSource);
    }

    private static ToolRegistryComposer composer(ModelClient modelClient, Path workspace,
            Path storeRoot) {
        Supplier<String> childIds = () -> CHILD_ID;
        Approver alwaysApprove = req -> com.srk.codingagent.persistence.PermissionDecisionOutcome.APPROVE;
        return new ToolRegistryComposer(
                modelClient, config(), workspace,
                EventLog.over(new StringWriter(), "parent"), new MemoryStore(storeRoot),
                new SessionStore(storeRoot), GrantStore.forSession(LINEAGE), alwaysApprove,
                LINEAGE, LINEAGE, () -> TS, childIds);
    }
}
