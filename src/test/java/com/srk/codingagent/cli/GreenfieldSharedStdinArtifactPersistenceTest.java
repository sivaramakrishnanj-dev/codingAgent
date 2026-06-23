package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * The greenfield artifact-content persistence regression under the <b>production approval wiring</b>
 * (T-3.2-RD-D8, regression of T-3.2): a greenfield pre-approval phase whose model authors its
 * deliverable via {@code write_artifact} must persist that CONTENT to the phase artifact on a normal
 * live run — where the permission gate's approver and the phase-approval gate read the SAME stdin in
 * the production default mode {@code ASK_EVERY_TIME} (RD-3 / NFR-PERMISSION-DEFAULT), exactly as
 * {@link Main#runInteractive} wires them (RD-7, AC-1.2, AC-2.1).
 *
 * <p><b>The D8 defect this catches — and why the existing D7 test missed it.</b> The existing
 * {@link GreenfieldArtifactPersistenceTest} builds the permission gate with an <em>always-approve</em>
 * {@link Approver}, so its {@code write_artifact} never consulted the real stdin-backed
 * {@link InteractiveApprover}. On the live REPL path, {@code Main.runInteractive} shares ONE
 * {@code answerSource} (over the single stdin {@link java.io.BufferedReader}) between the
 * {@link InteractiveApprover} (the permission gate's y/N) and the {@link InteractiveGreenfieldApproval}
 * (the phase y/N). {@code write_artifact} is Class X
 * ({@link com.srk.codingagent.persistence.OperationClass#SIDE_EFFECTING}); in {@code ASK_EVERY_TIME} a
 * Class-X tool routed through the gate would consult the approver, consuming a stdin line. With only
 * the one realistic developer {@code 'y'} fed (intended for the phase gate), the {@code write_artifact}
 * gate prompt and the phase gate contend for that single line — so the deliverable content write was
 * denied (a non-affirmative/EOF answer) and only the approval stamp landed. The fix (this regression
 * pins it) makes the {@code design/}-confined {@code write_artifact} a sanctioned pre-approval write
 * the gate auto-approves WITHOUT a separate per-op prompt (ADR-0012), so the developer's single
 * {@code 'y'} reaches the phase gate and the deliverable content reaches the artifact.
 *
 * <p><b>SUT and collaborators — the production approval wiring.</b> The SUT is a real greenfield
 * {@link AgentLoop} (real {@link ModelClient}, real {@link ToolRegistry} from the composer's
 * pre-approval registry, real {@link WriteArtifactTool} over a real {@link GreenfieldArtifactStore})
 * driven by a real {@link GreenfieldDriver}, with BOTH the real {@link PermissionGate} in
 * {@code ASK_EVERY_TIME} over a real {@link InteractiveApprover} AND the real
 * {@link ArtifactApprovalGate} over a real {@link InteractiveGreenfieldApproval} reading the SAME
 * {@code answerSource} — the precise wiring {@code Main.runInteractive}/{@code interactiveGreenfield}
 * builds. The only external double is the scripted {@link ScriptedBedrockClient} (the same Bedrock-
 * double pattern {@link GreenfieldArtifactPersistenceTest} / {@code AgentLoopTest} use), never a mock
 * of the SUT.
 *
 * <p><b>Oracles trace to the spec, not to implementation behaviour:</b>
 * <ul>
 *   <li><b>AC-1.2 / RD-7:</b> a requirements-phase turn whose model wrote via {@code write_artifact}
 *       persists the requirements CONTENT to {@code design/00-requirements.md}, on the shared-stdin
 *       production wiring with one developer {@code 'y'}.</li>
 *   <li><b>AC-1.2 / AC-1.5:</b> after the developer's {@code 'y'} approves the phase, the artifact
 *       carries BOTH the deliverable content AND the approval stamp — the real on-disk contract.</li>
 *   <li><b>AC-2.1 / RD-7:</b> the design and tasks phases each persist their deliverable content on
 *       the same shared-stdin wiring.</li>
 * </ul>
 */
class GreenfieldSharedStdinArtifactPersistenceTest {

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

    /** An answer source that replays a fixed sequence of typed lines, then end-of-input (null). */
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
     * path — but over a {@link ModelClient} backed by the scripted Bedrock and stores rooted at the
     * temp dir; {@code workspace} is the target repo the {@code write_artifact} store is confined to.
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

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);

    /**
     * Wires a single greenfield driver exactly as {@code Main.interactiveGreenfield} does for the live
     * REPL path: ONE {@code answerSource} shared between the permission gate's {@link InteractiveApprover}
     * and the phase-approval {@link InteractiveGreenfieldApproval}; the phase loop is the composer's
     * pre-approval registry + per-phase prompt over a real {@link PermissionGate} in
     * {@code ASK_EVERY_TIME}; the approval gate is the real timestamp-recording {@link ArtifactApprovalGate}.
     * The driver runs the single given pre-approval {@code phase} (the loop completes the phase, then the
     * approval gate is consulted to advance into the next phase).
     */
    private GreenfieldDriver liveSharedStdinDriver(BedrockRuntimeClient bedrock, Path workspace,
            Path storeRoot, Supplier<String> sharedAnswerSource, GreenfieldPhase phase) {
        // The production shared answer source: the gate's approver and the phase gate read the SAME
        // supplier, the single stdin the live REPL wires (Main.runInteractive).
        Approver approver = new InteractiveApprover(sharedAnswerSource, out);
        ToolRegistryComposer composer = composer(bedrock, workspace, storeRoot, approver);
        ToolRegistry tools = composer.greenfieldRegistry(phase);
        // The production gate: ASK_EVERY_TIME (the RD-3 default) over the real interactive approver.
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession(LINEAGE), approver);
        AgentLoop loop = new AgentLoop(composer.modelClient(), tools, gate,
                EventLog.over(new StringWriter(), "gf"), () -> TS, BudgetGuard.NONE,
                new OutputDisposer(16384), MODEL, composer.greenfieldSystemPrompt(phase));
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(workspace);
        // The production phase-approval decision: stdin-backed, reading the SAME shared answer source.
        ArtifactApprovalGate.ApprovalDecision decision =
                new InteractiveGreenfieldApproval(sharedAnswerSource, out, store);
        GreenfieldDriver.ApprovalGate approvalGate =
                new ArtifactApprovalGate(decision, store, () -> TS);
        // The phase-loop factory yields the phase-scoped loop only for the phase under test; any other
        // phase (the advance target) is given a no-write completing turn so the driver terminates
        // deterministically without a further model script.
        GreenfieldDriver.PhaseLoopFactory phaseLoops = requested ->
                requested == phase ? loop::run : prompt -> com.srk.codingagent.loop.LoopOutcome.completed("done");
        return new GreenfieldDriver(phaseLoops, approvalGate);
    }

    // --- AC-1.2 / RD-7 : the requirements deliverable content is persisted on the shared-stdin wiring

    @Test
    @DisplayName("AC-1.2/RD-7: on the production shared-stdin ASK_EVERY_TIME wiring, a requirements-phase write_artifact persists the deliverable content with only one developer 'y'")
    void requirementsContentPersistedUnderProductionSharedStdinWiring(
            @TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-1.2 — "the agent shall persist the agreed requirements as a markdown artifact in
        // the target project"; RD-7 — greenfield persists requirements as markdown. On the LIVE wiring
        // (the gate's InteractiveApprover and the phase gate share one stdin, ASK_EVERY_TIME), a
        // requirements turn whose model calls write_artifact must persist the requirements CONTENT,
        // given the one realistic developer input: the idea (the loop's prompt) + one 'y'. This is the
        // D8 reproduction: before the fix the single 'y' was consumed by the write_artifact gate prompt
        // (or the phase gate got EOF), so the content was denied and only the stamp landed. The expected
        // content traces to the scripted deliverable + AC-1.2, not to gate code.
        String requirements = "# Requirements\n\n## US-1 Shorten URL\n- AC-1.1: the service shall "
                + "accept a long URL and return a short code.\n## NFR\n- NFR-1: p99 < 50ms.\n";
        String artifactPath = GreenfieldArtifact.REQUIREMENTS.relativePath();
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(writeArtifactTurn("Writing the requirements.", "tu_req", artifactPath, requirements))
                .then(endTurn("I have written the requirements; please approve them."));
        // Only the realistic developer input on stdin: the single 'y' meant for the phase gate.
        Supplier<String> sharedStdin = answers("y");
        GreenfieldDriver driver = liveSharedStdinDriver(
                bedrock, workspace, storeRoot, sharedStdin, GreenfieldPhase.REQUIREMENTS);

        driver.run("build me a URL shortener");

        // The artifact on disk (read through the SAME target-repo store the tool writes through) holds
        // the model-authored requirements content — write_artifact actually persisted it on the live
        // shared-stdin wiring (AC-1.2/RD-7), not merely an approval stamp.
        String persisted = new GreenfieldArtifactStore(workspace).read(artifactPath).orElseThrow();
        assertTrue(persisted.contains(requirements),
                "AC-1.2/RD-7: the requirements deliverable content is persisted via write_artifact on "
                        + "the production shared-stdin ASK_EVERY_TIME wiring; was: " + persisted);
    }

    @Test
    @DisplayName("AC-1.2/AC-1.5: after the developer's one 'y' the requirements artifact carries BOTH the deliverable content AND the approval stamp (the real on-disk contract)")
    void requirementsArtifactCarriesContentThenApprovalStamp(
            @TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-1.2 (content persisted) + AC-1.5 (approval recorded with a timestamp in the
        // requirements artifact). The real on-disk contract D8 names: after a normal run (write_artifact
        // + the developer's single 'y'), the artifact holds the deliverable content AND, after approval,
        // the approval timestamp stamp. With one 'y' on the shared stdin, the content write is
        // auto-approved (sanctioned) and the 'y' reaches the phase gate, which stamps the approval.
        String requirements = "# Requirements\n\n- AC-1.1: accept a long URL and return a short code.\n";
        String artifactPath = GreenfieldArtifact.REQUIREMENTS.relativePath();
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(writeArtifactTurn("Writing the requirements.", "tu_req", artifactPath, requirements))
                .then(endTurn("Wrote the requirements; please approve."));
        Supplier<String> sharedStdin = answers("y");
        GreenfieldDriver driver = liveSharedStdinDriver(
                bedrock, workspace, storeRoot, sharedStdin, GreenfieldPhase.REQUIREMENTS);

        GreenfieldOutcome outcome = driver.run("build me a URL shortener");

        String persisted = new GreenfieldArtifactStore(workspace).read(artifactPath).orElseThrow();
        assertTrue(persisted.contains(requirements),
                "AC-1.2: the deliverable content is present in the artifact");
        assertTrue(persisted.contains(TS),
                "AC-1.5: the developer's 'y' reached the phase gate, which stamped the approval timestamp "
                        + "(" + TS + ") into the artifact; was: " + persisted);
        assertFalse(outcome.disposition() == GreenfieldOutcome.Disposition.AWAITING_APPROVAL
                && !persisted.contains(requirements),
                "AC-1.2/AC-1.5: the run did not strand the artifact at a stamp-only state");
    }

    // --- AC-2.1 / RD-7 : the design and tasks deliverable content is persisted on the shared wiring --

    @Test
    @DisplayName("AC-2.1/RD-7: the design and tasks phase turns each persist their deliverable content through the real ASK_EVERY_TIME gate over the stdin-backed InteractiveApprover")
    void designAndTasksContentPersistedThroughProductionGate(
            @TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-2.1 — "the agent shall produce a design artifact and a task-breakdown artifact as
        // markdown in the target project"; RD-7. Each of those phase turns must persist its deliverable
        // CONTENT via write_artifact when the write is routed through the REAL production permission gate
        // (ASK_EVERY_TIME over the stdin-backed InteractiveApprover) — the exact path that broke at D8,
        // where a Class-X write_artifact would have consumed a stdin line. The phase loop is driven
        // directly (the GreenfieldDriver always begins at the initial REQUIREMENTS phase, so the design
        // and tasks turns are exercised at the loop seam) with the production gate and a stdin that
        // holds no extra input — proving the deliverable content reaches the artifact without the gate
        // consuming a developer line for the sanctioned design-markdown write. Body traces to AC-2.1 +
        // the scripted deliverable, not to gate code.
        for (GreenfieldArtifact artifact : List.of(GreenfieldArtifact.DESIGN, GreenfieldArtifact.TASKS)) {
            GreenfieldPhase phase = artifact.phase();
            String body = "# " + artifact.heading() + "\n\n- item-1 (AC-1.2): " + artifact.heading()
                    + " deliverable content authored during the phase turn.\n";
            String path = artifact.relativePath();
            ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                    .then(writeArtifactTurn("Writing the " + artifact.heading() + ".", "tu_" + phase,
                            path, body))
                    .then(endTurn("Wrote the " + artifact.heading() + "; please approve."));
            // The production wiring: ASK_EVERY_TIME over the stdin-backed InteractiveApprover. Stdin
            // holds no input: if the sanctioned write_artifact were (wrongly) prompted, the approver
            // would read EOF and DENY, and the content would not persist — the D8 failure mode.
            Supplier<String> sharedStdin = answers();
            AgentLoop loop = productionGatePhaseLoop(bedrock, workspace, storeRoot, sharedStdin, phase);

            loop.run("proceed with the " + phase + " phase");

            String persisted = new GreenfieldArtifactStore(workspace).read(path).orElseThrow();
            assertTrue(persisted.contains(body),
                    "AC-2.1/RD-7: the " + artifact.heading() + " deliverable content is persisted via "
                            + "write_artifact through the real ASK_EVERY_TIME gate; was: " + persisted);
        }
    }

    /**
     * Assembles a real greenfield phase {@link AgentLoop} the way {@link AgentLoopFactory} does — the
     * composer's pre-approval registry + per-phase prompt over a real {@link PermissionGate} in the
     * production-default {@code ASK_EVERY_TIME} mode whose approver is the stdin-backed
     * {@link InteractiveApprover} (NOT an always-approve stub). This is the production gate path the D7
     * always-approve test bypassed.
     */
    private AgentLoop productionGatePhaseLoop(BedrockRuntimeClient bedrock, Path workspace,
            Path storeRoot, Supplier<String> sharedAnswerSource, GreenfieldPhase phase) {
        Approver approver = new InteractiveApprover(sharedAnswerSource, out);
        ToolRegistryComposer composer = composer(bedrock, workspace, storeRoot, approver);
        ToolRegistry tools = composer.greenfieldRegistry(phase);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession(LINEAGE), approver);
        return new AgentLoop(composer.modelClient(), tools, gate,
                EventLog.over(new StringWriter(), "gf"), () -> TS, BudgetGuard.NONE,
                new OutputDisposer(16384), MODEL, composer.greenfieldSystemPrompt(phase));
    }
}
