package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.BudgetGuard;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.tool.ToolRegistry;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * Wiring test for the greenfield run path: it confirms the per-phase {@link GreenfieldPlaybook}
 * actually reaches the model on the Converse call when a {@link GreenfieldDriver} drives real
 * {@link AgentLoop}s built with the per-phase greenfield prompt as their {@code system} argument —
 * the end-to-end path {@code playbook(phase) -> AgentLoop.system -> Converse request} the production
 * {@link com.srk.codingagent.cli.AgentLoopFactory} wires for {@code --mode greenfield}.
 *
 * <p><b>Why this test exists (M0-lesson discipline; the same discipline as
 * {@link BrownfieldWiringTest}).</b> The G3 phase-gating gate depends on the model being primed with
 * the greenfield phase playbook. A test that only asserted "a driver ran the loop" would not catch a
 * regression where the greenfield prompt silently never reached the model — the exact class of
 * defect the M0/M2 real-Bedrock smoke tests caught for the memory index (D5). This test drives a
 * real {@link GreenfieldDriver} over real {@link AgentLoop}s (real {@link ModelClient}, real
 * {@link PermissionGate}, real {@link EventLog}) backed by a scripted Bedrock double &mdash; the
 * only external dependency, no live AWS &mdash; and asserts the captured Converse request's system
 * blocks carry the phase playbook's actual instruction keywords, not merely that a non-empty system
 * prompt was set.
 *
 * <p><b>Oracle.</b> 02-architecture &sect; 2 (the loop calls {@code Converse(messages, system, ...)})
 * + AC-1.1/AC-2.3 (the greenfield priming) &mdash; the per-phase playbook's requirements-before-
 * source and approval-before-implementation instructions must reach the request the model client
 * builds for the phase being run.
 */
class GreenfieldWiringTest {

    private static final String MODEL_ID = "anthropic.claude-opus-4-8";
    private static final String TS = "2026-06-23T10:00:00Z";

    /** A {@link BedrockRuntimeClient} replaying a scripted queue of responses and capturing requests. */
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
                .usage(u -> u.inputTokens(20).outputTokens(5).totalTokens(25))
                .build();
    }

    /**
     * Builds a real {@link AgentLoop} for a phase, primed with that phase's greenfield playbook as
     * its {@code system} argument and backed by the supplied scripted Bedrock — the same prompt ->
     * loop -> Converse wiring {@link com.srk.codingagent.cli.AgentLoopFactory} does, only Bedrock
     * substituted.
     */
    private static AgentLoop loopFor(GreenfieldPhase phase, ScriptedBedrockClient bedrock) {
        return loopFor(phase, bedrock, new ModelClient(bedrock));
    }

    /**
     * Builds a real {@link AgentLoop} for a phase over an explicit {@link ModelClient}, so a test
     * can wire the greenfield-budget client ({@link ModelClient#forGreenfield}) the production
     * factory uses for the greenfield phase loops (DCR-2 — D1) and assert the budget reaches the
     * wire.
     */
    private static AgentLoop loopFor(
            GreenfieldPhase phase, ScriptedBedrockClient bedrock, ModelClient modelClient) {
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession("greenfield"),
                req -> PermissionDecisionOutcome.APPROVE);
        return new AgentLoop(modelClient, ToolRegistry.of(List.of()), gate,
                EventLog.over(new StringWriter(), "gf"), () -> TS, BudgetGuard.NONE,
                new OutputDisposer(16384), MODEL_ID, GreenfieldPlaybook.systemPrompt(phase));
    }

    private static String systemTextOf(ConverseRequest request) {
        return request.system().stream()
                .map(SystemContentBlock::text)
                .reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * A no-op driver-authored persistence seam (DCR-1): this test pins the prompt-on-the-wire contract,
     * not artifact persistence, so the driver's per-phase write/read need no real store here.
     */
    private static GreenfieldDriver.PhaseArtifactWriter noopWriter() {
        return new GreenfieldDriver.PhaseArtifactWriter() {
            @Override
            public void write(GreenfieldArtifact artifact, String content) {
                // no-op: persistence is exercised in GreenfieldArtifactAuthoringTest
            }

            @Override
            public String read(GreenfieldArtifact artifact) {
                return "";
            }
        };
    }

    /** No further developer turns (each phase runs a single round; DCR-2 multi-turn dialogue seam). */
    private static GreenfieldDriver.DeveloperTurnSource noFurtherTurns() {
        return phase -> null;
    }

    @Test
    @DisplayName("the greenfield requirements-phase playbook reaches the model: requirements-before-source instructions are in the Converse system blocks")
    void requirementsPhasePlaybookReachesTheModel() {
        // Oracle: 02-architecture § 2 + AC-1.1 — a greenfield session in the requirements phase must
        // prime the model to gather requirements before any source is written. A real AgentLoop built
        // with GreenfieldPlaybook.systemPrompt(REQUIREMENTS), driven through the GreenfieldDriver,
        // must send those instructions on the Converse request. Assert against the captured request's
        // system blocks (what the model actually receives), not impl state. The gate declines the
        // first advance, so the session runs exactly the requirements phase (the AC-1.1 dialogue).
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(endTurn("Here are the requirements I gathered."));
        GreenfieldDriver.PhaseLoopFactory loops = phase -> loopFor(phase, bedrock)::run;
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, noopWriter(), completedPhase -> false, noFurtherTurns());

        driver.run("build me a URL shortener");

        ConverseRequest sent = bedrock.requests.get(0);
        assertTrue(sent.hasSystem(), "§ 2: the greenfield playbook reaches the Converse request");
        String systemText = systemTextOf(sent);
        assertTrue(systemText.contains("requirements"),
                "AC-1.1: the requirements-gathering instruction reaches the model; was: " + systemText);
        assertTrue(systemText.contains("source") && systemText.contains("before"),
                "AC-1.1: the before-any-source-write framing reaches the model");
        assertEquals(GreenfieldPlaybook.systemPrompt(GreenfieldPhase.REQUIREMENTS).size(),
                sent.system().size(),
                "every requirements-phase playbook block reaches the model (no block dropped on the wire)");
    }

    @Test
    @DisplayName("AC-2.3: the implementation-phase playbook (reached only after approval) reaches the model with the source-change tools")
    void implementPhasePlaybookReachesTheModelAfterApproval() {
        // Oracle: AC-2.3 — implementation begins only after the design + task breakdown are approved;
        // the implementation phase is where the source-change tools are introduced. Approving every
        // gate, the driver runs all four phases over their real loops; the FOURTH Converse request
        // (the implementation phase's) must carry the implementation playbook — naming the
        // source-change tools the pre-approval phases withheld. Assert against the captured request's
        // system blocks.
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(endTurn("requirements done"))
                .then(endTurn("design done"))
                .then(endTurn("tasks done"))
                .then(endTurn("implemented the first task"));
        GreenfieldDriver.PhaseLoopFactory loops = phase -> loopFor(phase, bedrock)::run;
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, noopWriter(), completedPhase -> true, noFurtherTurns());

        driver.run("build me a URL shortener");

        // Requests #0..#3 = requirements, design, tasks, implement (one phase turn each).
        assertEquals(4, bedrock.requests.size(),
                "AC-2.3: all four phases ran their loop turns once every gate was approved");
        String implementSystem = systemTextOf(bedrock.requests.get(3));
        assertTrue(implementSystem.contains("implement"),
                "AC-2.3: the implementation phase prompt reaches the model on the post-approval turn");
        assertTrue(implementSystem.contains("write_file") && implementSystem.contains("edit_file")
                        && implementSystem.contains("run_command"),
                "AC-2.3: the implementation phase prompt introduces the source-change tools (the only "
                        + "phase that does); was: " + implementSystem);
    }

    @Test
    @DisplayName("AC-1.4: a pre-approval phase turn's Converse system blocks do NOT name the source-change tools")
    void preApprovalPhaseTurnDoesNotNameSourceChangeTools() {
        // Oracle: AC-1.4 — while in the pre-approval dialogue the agent does not write source. The
        // prompt that reaches the model in a pre-approval phase must not name the source-change tools
        // (steering only toward tools actually offered, matching the driver's structural withholding).
        // Drive the requirements phase (declined gate, so only that phase runs) and assert the
        // captured system blocks name no source-change tool.
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient().then(endTurn("requirements done"));
        GreenfieldDriver.PhaseLoopFactory loops = phase -> loopFor(phase, bedrock)::run;
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, noopWriter(), completedPhase -> false, noFurtherTurns());

        driver.run("build me a URL shortener");

        String systemText = systemTextOf(bedrock.requests.get(0));
        assertTrue(!systemText.contains("write_file") && !systemText.contains("edit_file")
                        && !systemText.contains("run_command"),
                "AC-1.4: the requirements-phase prompt the model receives names no source-change tool; was: "
                        + systemText);
    }

    @Test
    @DisplayName("DCR-2/D1: the greenfield phase Converse request (built over ModelClient.forGreenfield) carries inferenceConfig.maxTokens = 16384")
    void greenfieldPhaseRequestCarriesOutputBudgetOnTheWire() {
        // Oracle: ADR-0012 "Greenfield-phase output-token budget" + 02-architecture.md § 2.1 — the
        // greenfield phases set an explicit inferenceConfig.maxTokens (16384) on the Converse request
        // so a full deliverable is not truncated at the backend default 4096 cap. This pins the
        // end-to-end greenfield path: a phase loop built over the greenfield-budget ModelClient (the
        // one AgentLoopFactory wires for the greenfield phase loops, ModelClient.forGreenfield) must
        // send maxTokens on the actual captured Converse request. The expected value (16384) traces
        // to the ADR's chosen value, not to impl. Drive the requirements phase (declined gate, one
        // phase runs) and assert the captured request's inferenceConfig.maxTokens.
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient().then(endTurn("requirements done"));
        ModelClient greenfieldClient = ModelClient.forGreenfield(bedrock);
        GreenfieldDriver.PhaseLoopFactory loops =
                phase -> loopFor(phase, bedrock, greenfieldClient)::run;
        GreenfieldDriver driver = new GreenfieldDriver(
                loops, noopWriter(), completedPhase -> false, noFurtherTurns());

        driver.run("build me a URL shortener");

        ConverseRequest sent = bedrock.requests.get(0);
        InferenceConfiguration inference = sent.inferenceConfig();
        assertNotNull(inference,
                "DCR-2/§ 2.1: the greenfield phase Converse request must carry an inferenceConfig "
                        + "bounding the model's output");
        assertEquals(16384, inference.maxTokens(),
                "ADR-0012 § 2.1: the greenfield phase request sets inferenceConfig.maxTokens = 16384 "
                        + "(16K) so a large deliverable is not truncated at the default 4096 cap");
    }
}
