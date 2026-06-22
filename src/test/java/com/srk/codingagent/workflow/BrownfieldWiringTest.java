package com.srk.codingagent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.BudgetGuard;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.model.converse.ModelClient;
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
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * Wiring test for the brownfield run path: it confirms the {@link BrownfieldPlaybook} actually
 * reaches the model on the Converse call when a {@link BrownfieldDriver} drives a real
 * {@link AgentLoop} built with the playbook as its {@code system} prompt — the end-to-end path
 * {@code playbook -> AgentLoop.system -> Converse request} the production
 * {@link com.srk.codingagent.cli.AgentLoopFactory} wires.
 *
 * <p><b>Why this test exists (M0-lesson discipline).</b> The G1 explore&rarr;edit&rarr;verify gate
 * depends on the model being primed with the brownfield playbook. A test that only asserted "a
 * driver ran the loop" would not catch a regression where the playbook silently never reached the
 * model. This test drives a real {@link AgentLoop} (real {@link ModelClient}, real
 * {@link PermissionGate}, real {@link EventLog}) over a scripted Bedrock double &mdash; the only
 * external dependency, no live AWS &mdash; and asserts the captured Converse request's system
 * blocks carry the playbook's actual instruction keywords (explore, edit, verify), not merely that
 * a non-empty system prompt was set.
 *
 * <p><b>Oracle.</b> 02-architecture § 2 (the loop calls {@code Converse(messages, system, ...)}) +
 * AC-4.1/AC-5.3 (the playbook content) &mdash; the playbook's explore-before-edit and
 * verify-after-change instructions must reach the request the model client builds.
 */
class BrownfieldWiringTest {

    private static final String MODEL_ID = "anthropic.claude-opus-4-8";
    private static final String TS = "2026-06-22T10:00:00Z";

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

    @Test
    @DisplayName("the brownfield playbook reaches the model: explore/edit/verify instructions are in the Converse system blocks")
    void playbookReachesTheModelOnTheRunPath() {
        // Oracle: 02-architecture § 2 + AC-4.1/AC-5.3 — a brownfield session must prime the model
        // with the playbook. A real AgentLoop built with BrownfieldPlaybook.systemPrompt(), driven
        // through the BrownfieldDriver, must send those instructions on the Converse request. Assert
        // against the captured request's system blocks (what the model actually receives), not impl
        // state.
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient().then(endTurn("Made the change."));
        ModelClient modelClient = new ModelClient(bedrock);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession("root"),
                req -> PermissionDecisionOutcome.APPROVE);
        AgentLoop loop = new AgentLoop(modelClient, ToolRegistry.of(List.of()), gate,
                EventLog.over(new StringWriter(), "t"), () -> TS, BudgetGuard.NONE,
                new OutputDisposer(16384), MODEL_ID, BrownfieldPlaybook.systemPrompt());
        // No test command configured, so the driver does not shell out; the change-turn completes
        // and the run is NOT_VERIFIED. The point of THIS test is the system-prompt wiring, exercised
        // by running the real loop once.
        BrownfieldDriver driver = new BrownfieldDriver(
                loop::run, remedy -> () -> com.srk.codingagent.loop.VerifyOutcome.noTestCommand());

        driver.run("rename the field");

        ConverseRequest sent = bedrock.requests.get(0);
        assertTrue(sent.hasSystem(), "§ 2: the brownfield playbook reaches the Converse request");
        String systemText = sent.system().stream()
                .map(SystemContentBlock::text)
                .reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);
        assertTrue(systemText.contains("explore"),
                "AC-4.1: the explore-before-edit instruction reaches the model; was: " + systemText);
        assertTrue(systemText.contains("edit"),
                "AC-5.1: the edit instruction reaches the model");
        assertTrue(systemText.contains("verify"),
                "AC-5.3: the verify-after-change instruction reaches the model");
        assertEquals(BrownfieldPlaybook.systemPrompt().size(), sent.system().size(),
                "every playbook block reaches the model (no block dropped on the wire)");
    }
}
