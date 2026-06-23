package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.BudgetGuard;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import com.srk.codingagent.tool.ToolRegistry;
import com.srk.codingagent.tool.WriteArtifactTool;
import com.srk.codingagent.workflow.GreenfieldArtifact;
import com.srk.codingagent.workflow.GreenfieldPhase;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * The greenfield <b>optional {@code write_artifact} tool</b> contract test (originally T-3.2-RD-D7;
 * reframed under DCR-1 / ADR-0012 amended 2026-06-23). Since DCR-1, persistence of each pre-approval
 * phase deliverable is <em>driver-authored</em> — the {@link com.srk.codingagent.workflow.GreenfieldDriver}
 * writes each artifact in code from the phase's END_TURN prose (see
 * {@code GreenfieldArtifactAuthoringTest} and the production-driver CLI tests), NOT via a model tool
 * call. The {@code write_artifact} design-doc tool nonetheless <em>stays registered/available</em> in
 * the pre-approval registry (it is now optional, no longer the persistence mechanism; ADR-0012 amended,
 * C7 note). This test pins the still-true contracts around that optional tool: when the model
 * <em>does</em> emit a {@code write_artifact} tool-use, the dispatch&rarr;tool&rarr;store plumbing
 * still persists the content to the {@code design/}-confined artifact (RD-7, AC-1.2/AC-2.1; AC-1.4
 * design/-confinement); and the per-phase prompt the model receives names the artifact path its
 * deliverable is saved to and notes that {@code write_artifact} remains available.
 *
 * <p><b>SUT and collaborators.</b> The SUT is the real {@link ToolRegistryComposer} greenfield
 * registry + per-phase prompt assembly driving a real {@link AgentLoop} (real {@link ModelClient},
 * real {@link PermissionGate}, real {@link EventLog}, real {@link WriteArtifactTool} over a real
 * {@link GreenfieldArtifactStore} rooted at the target-repo {@link TempDir}). The only external
 * double is a hand-rolled {@link ScriptedBedrockClient} replaying scripted {@link ConverseResponse}s
 * — the same Bedrock-double pattern {@code AgentLoopTest} / {@code LiveToolRegistryCompositionTest}
 * use, never a mock of the SUT. The gate auto-approves so the Class-X {@code write_artifact} (which
 * the composition root offers in the pre-approval phases, path-confined to {@code design/})
 * dispatches.
 *
 * <p><b>Oracles trace to the spec, not to the composer's code:</b>
 * <ul>
 *   <li><b>AC-1.2 / RD-7 (tool stays registered):</b> when the model calls the optional
 *       {@code write_artifact} tool, it persists the requirements content to
 *       {@code design/00-requirements.md} (the plumbing still works — the DCR-1 probe contract).</li>
 *   <li><b>AC-2.1 (tool stays registered):</b> the optional tool likewise persists design + tasks
 *       content when called.</li>
 *   <li><b>RD-7/AC-1.2/AC-2.1:</b> the per-phase prompt names its artifact path (so the model knows
 *       what file its deliverable becomes) and the {@code write_artifact} tool remains named/available
 *       — asserted against the captured Converse request's system blocks.</li>
 * </ul>
 */
class GreenfieldArtifactPersistenceTest {

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

    /** A model turn that emits a {@code write_artifact} tool-use with the given path + content. */
    private static ConverseResponse writeArtifactTurn(String text, String toolUseId, String path,
            String content) {
        Document input = Document.mapBuilder()
                .putString("path", path)
                .putString("content", content)
                .build();
        ContentBlock textBlock = ContentBlock.fromText(text);
        ContentBlock toolUseBlock = ContentBlock.fromToolUse(
                b -> b.toolUseId(toolUseId).name(WriteArtifactTool.NAME).input(input));
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(List.of(textBlock, toolUseBlock))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason("tool_use")
                .usage(u -> u.inputTokens(100).outputTokens(20).totalTokens(120))
                .build();
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

    private static Approver alwaysApprove() {
        return req -> PermissionDecisionOutcome.APPROVE;
    }

    private static ResolvedConfig config() {
        return new ResolvedConfig(MODEL, PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                1, null, ResolvedConfig.Commands.empty(), 0.85, 16384, 5, 300);
    }

    /**
     * Builds the composer EXACTLY as {@link AgentLoopFactory} builds it — the production composition
     * path — but over a {@link ModelClient} backed by the scripted Bedrock and stores rooted at the
     * temp dir; {@code workspace} is the target repo the {@code write_artifact} store is confined to.
     */
    private static ToolRegistryComposer composer(BedrockRuntimeClient bedrock, Path workspace,
            Path storeRoot) {
        Supplier<String> childIds = () -> CHILD_ID;
        return new ToolRegistryComposer(
                new ModelClient(bedrock), config(), workspace,
                EventLog.over(new StringWriter(), "parent"), new MemoryStore(storeRoot),
                new SessionStore(storeRoot), GrantStore.forSession(LINEAGE), alwaysApprove(),
                LINEAGE, LINEAGE, () -> TS, childIds);
    }

    /**
     * Assembles a real greenfield phase {@link AgentLoop} the way {@link AgentLoopFactory} does for a
     * pre-approval phase: the composer's phase-scoped registry (which offers {@code write_artifact})
     * and per-phase greenfield prompt, over a real gate that auto-approves the Class-X
     * {@code write_artifact} so it dispatches.
     */
    private static AgentLoop phaseLoop(ToolRegistryComposer composer, GreenfieldPhase phase) {
        ToolRegistry tools = composer.greenfieldRegistry(phase);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession(LINEAGE), alwaysApprove());
        return new AgentLoop(composer.modelClient(), tools, gate,
                EventLog.over(new StringWriter(), "gf"), () -> TS, BudgetGuard.NONE,
                new OutputDisposer(16384), MODEL, composer.greenfieldSystemPrompt(phase));
    }

    private static String systemTextOf(ConverseRequest request) {
        return request.system().stream()
                .map(SystemContentBlock::text)
                .reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);
    }

    // --- AC-1.2 (tool stays registered) : the optional write_artifact tool still persists content ---

    @Test
    @DisplayName("DCR-1 AC-1.2/RD-7 (tool stays registered): when the model calls the optional write_artifact tool, it still persists the requirements content to design/00-requirements.md")
    void requirementsPhasePersistsArtifactContent(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-1.2 — "the agent shall persist the agreed requirements as a markdown artifact in
        // the target project"; RD-7 — greenfield persists requirements/design/tasks as markdown. Under
        // DCR-1 persistence is driver-authored (covered by GreenfieldArtifactAuthoringTest), but the
        // write_artifact tool STAYS registered/available (ADR-0012 amended, C7 note) — so the still-true
        // contract here is that when the model DOES emit a write_artifact tool-use, the
        // dispatch->tool->store plumbing persists the content to the design/-confined artifact. Driving
        // a real requirements-phase loop over a scripted model that emits a write_artifact tool-use, the
        // requirements artifact on disk must contain the authored content. The expected content traces
        // to the scripted deliverable + AC-1.2, not to composer code.
        String requirements = "# Requirements\n\n## US-1 Shorten URL\n- AC-1.1: the service shall "
                + "accept a long URL and return a short code.\n## NFR\n- NFR-1: p99 < 50ms.\n";
        String artifactPath = GreenfieldArtifact.REQUIREMENTS.relativePath();
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(writeArtifactTurn("Writing the requirements.", "tu_req", artifactPath, requirements))
                .then(endTurn("I have written the requirements; please approve them."));
        ToolRegistryComposer composer = composer(bedrock, workspace, storeRoot);

        LoopOutcome outcome = phaseLoop(composer, GreenfieldPhase.REQUIREMENTS).run(
                "build me a URL shortener");

        assertTrue(outcome.completed(), "the requirements phase turn completes after writing the artifact");
        // The artifact on disk (read through the SAME target-repo store the tool writes through) holds
        // the model-authored requirements content — write_artifact actually persisted it (AC-1.2).
        String persisted = new GreenfieldArtifactStore(workspace).read(artifactPath).orElseThrow();
        assertEquals(requirements, persisted,
                "AC-1.2/RD-7: the requirements deliverable content is persisted to the artifact via "
                        + "write_artifact, not merely discussed");
    }

    @Test
    @DisplayName("DCR-1 AC-2.1 (tool stays registered): when the model calls the optional write_artifact tool, the design and tasks phases each still persist their deliverable content")
    void designAndTasksPhasesPersistArtifactContent(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-2.1 — "the agent shall produce a design artifact and a task-breakdown artifact as
        // markdown in the target project". Under DCR-1 persistence is driver-authored; the write_artifact
        // tool stays registered/available (ADR-0012 amended), so the still-true contract is that when
        // the model DOES call it, the plumbing persists the content. For each phase, drive a real phase
        // loop over a scripted write_artifact tool-use and assert the phase's artifact holds the authored
        // content. The body traces to AC-2.1 + the scripted deliverable.
        for (GreenfieldArtifact artifact : List.of(GreenfieldArtifact.DESIGN, GreenfieldArtifact.TASKS)) {
            GreenfieldPhase phase = artifact.phase();
            String body = "# " + artifact.heading() + "\n\n" + artifact.heading()
                    + " deliverable content authored during the phase turn.\n";
            String path = artifact.relativePath();
            ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                    .then(writeArtifactTurn("Writing the " + artifact.heading() + ".", "tu_" + phase,
                            path, body))
                    .then(endTurn("Wrote the " + artifact.heading() + "; please approve."));
            ToolRegistryComposer composer = composer(bedrock, workspace, storeRoot);

            phaseLoop(composer, phase).run("proceed with the " + phase + " phase");

            String persisted = new GreenfieldArtifactStore(workspace).read(path).orElseThrow();
            assertEquals(body, persisted,
                    "AC-2.1: the " + artifact.heading() + " phase persists its deliverable to "
                            + path + " via write_artifact");
        }
    }

    // --- the prompt contract: the per-phase prompt names the artifact path + the optional tool ------

    @Test
    @DisplayName("DCR-1 RD-7/AC-1.2/AC-2.1: each pre-approval phase prompt names its artifact path (the file its deliverable becomes) and notes the optional write_artifact tool remains available")
    void preApprovalPhasePromptDirectsArtifactWrite(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: RD-7/AC-1.2/AC-2.1 — the deliverable of each pre-approval phase is persisted as its
        // design-doc artifact. Under DCR-1 the driver persists the phase's END_TURN prose to that
        // artifact in code, so the prompt no longer mandates a write_artifact tool call; but it must
        // still name the artifact path (so the model knows what file its deliverable becomes) and the
        // write_artifact tool stays registered/available (ADR-0012 amended, C7 note) so the prompt still
        // names it as optional. Assert against the captured request (what the model receives), the same
        // wiring discipline as GreenfieldWiringTest, for every pre-approval phase.
        for (GreenfieldPhase phase : List.of(
                GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS)) {
            String path = GreenfieldArtifact.forPhase(phase).orElseThrow().relativePath();
            ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                    .then(endTurn("the full deliverable content as my final answer"));
            ToolRegistryComposer composer = composer(bedrock, workspace, storeRoot);

            phaseLoop(composer, phase).run("proceed with the " + phase + " phase");

            String systemText = systemTextOf(bedrock.requests.get(0));
            assertTrue(systemText.contains(path.toLowerCase(Locale.ROOT)),
                    "AC-1.2/AC-2.1: the " + phase + " phase prompt names its artifact path (" + path
                            + ") so the deliverable is saved to the right file; was: " + systemText);
            assertTrue(systemText.contains(WriteArtifactTool.NAME),
                    "ADR-0012 amended (C7 note): the " + phase + " phase prompt still names the optional "
                            + WriteArtifactTool.NAME + " tool (it stays registered/available); was: "
                            + systemText);
        }
    }
}
