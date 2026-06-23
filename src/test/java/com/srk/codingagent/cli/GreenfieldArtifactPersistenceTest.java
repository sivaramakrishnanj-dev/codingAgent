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
 * The greenfield <b>artifact-content persistence</b> contract test (T-3.2-RD-D7, regression of
 * T-3.2): running a pre-approval greenfield phase turn must persist that phase's substantive
 * deliverable CONTENT to its design-doc artifact via the {@code write_artifact} tool — so the
 * requirements/design/tasks artifacts carry real content (not just an approval stamp) on the live
 * path (RD-7, AC-1.2 for requirements; AC-2.1 for design + tasks).
 *
 * <p><b>The D7 defect this would have caught.</b> A real-Bedrock greenfield run drove
 * requirements&rarr;design&rarr;tasks, approving each phase, yet {@code write_artifact} was invoked
 * <em>zero</em> times: the model discussed each deliverable in prose but never persisted it, so the
 * artifact files held only the approval gate's stamp. The cause was the per-phase greenfield system
 * prompt not directing the model to author each phase's deliverable to its artifact via
 * {@code write_artifact} before the phase turn completed. A test that only asserted "the phase
 * returns text" would not catch that — so this test drives a real phase-loop turn over a scripted
 * Bedrock double that emits a {@code write_artifact} tool-use and asserts the artifact on disk holds
 * the model-authored content (the real contract: deliverable content persisted, the D5/D7 lesson).
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
 *   <li><b>AC-1.2 / RD-7:</b> a requirements-phase turn that calls {@code write_artifact} persists
 *       the requirements content to {@code design/00-requirements.md}.</li>
 *   <li><b>AC-2.1:</b> the design and tasks phases each persist their deliverable to their
 *       artifact.</li>
 *   <li><b>The D7 contract (RD-7/AC-1.2):</b> the per-phase prompt the model receives directs it to
 *       write the deliverable to its artifact via {@code write_artifact} before completing the
 *       turn — asserted against the captured Converse request's system blocks.</li>
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

    // --- AC-1.2 : a requirements-phase turn persists the requirements content via write_artifact --

    @Test
    @DisplayName("AC-1.2/RD-7: a requirements-phase turn that calls write_artifact persists the requirements content to design/00-requirements.md")
    void requirementsPhasePersistsArtifactContent(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-1.2 — "the agent shall persist the agreed requirements as a markdown artifact in
        // the target project"; RD-7 — greenfield persists requirements/design/tasks as markdown. The
        // contract D7 surfaced: a phase turn must result in write_artifact being invoked with the
        // phase's substantive content, so the artifact holds real content (not just an approval
        // stamp). Driving a real requirements-phase loop over a scripted model that emits a
        // write_artifact tool-use, the requirements artifact on disk must contain the authored
        // content. The expected content traces to the scripted deliverable + AC-1.2, not to composer
        // code.
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
    @DisplayName("AC-2.1: the design and tasks phase turns each persist their deliverable content to their artifact")
    void designAndTasksPhasesPersistArtifactContent(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-2.1 — "the agent shall produce a design artifact and a task-breakdown artifact as
        // markdown in the target project". Each of those phase turns must persist its deliverable
        // content via write_artifact (the D7 contract, generalized to design + tasks). For each phase,
        // drive a real phase loop over a scripted write_artifact tool-use and assert the phase's
        // artifact holds the authored content. The body traces to AC-2.1 + the scripted deliverable.
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

    // --- the D7 prompt contract: the per-phase prompt directs writing the artifact -----------------

    @Test
    @DisplayName("RD-7/AC-1.2: each pre-approval phase prompt the model receives directs writing the deliverable to its artifact via write_artifact")
    void preApprovalPhasePromptDirectsArtifactWrite(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: RD-7/AC-1.2/AC-2.1 — the deliverable of each pre-approval phase is the persisted
        // artifact. The D7 cause was the per-phase prompt never directing the model to write it. The
        // prompt the model actually receives (the captured Converse system blocks, the live path) must
        // name the write_artifact tool AND the phase's artifact path, so the model is steered to
        // persist the deliverable — not merely discuss it. Assert against the captured request (what
        // the model receives), the same wiring discipline as GreenfieldWiringTest, for every
        // pre-approval phase.
        for (GreenfieldPhase phase : List.of(
                GreenfieldPhase.REQUIREMENTS, GreenfieldPhase.DESIGN, GreenfieldPhase.TASKS)) {
            String path = GreenfieldArtifact.forPhase(phase).orElseThrow().relativePath();
            ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                    .then(endTurn("discussed; nothing written"));
            ToolRegistryComposer composer = composer(bedrock, workspace, storeRoot);

            phaseLoop(composer, phase).run("proceed with the " + phase + " phase");

            String systemText = systemTextOf(bedrock.requests.get(0));
            assertTrue(systemText.contains(WriteArtifactTool.NAME),
                    "RD-7: the " + phase + " phase prompt names the " + WriteArtifactTool.NAME
                            + " tool so the deliverable is persisted; was: " + systemText);
            assertTrue(systemText.contains(path.toLowerCase(Locale.ROOT)),
                    "AC-1.2/AC-2.1: the " + phase + " phase prompt names its artifact path (" + path
                            + ") so the deliverable is written to the right file; was: " + systemText);
        }
    }
}
