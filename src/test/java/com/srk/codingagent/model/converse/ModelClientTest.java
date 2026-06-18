package com.srk.codingagent.model.converse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.StopReason;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

/**
 * Unit tests for {@link ModelClient} — the Bedrock Converse adapter (component C4).
 *
 * <p>The SUT (a real {@link ModelClient} with the real {@link ConverseWireMapper}) is
 * never mocked. Only the external dependency — the {@link BedrockRuntimeClient} — is
 * stubbed by a hand-rolled in-test double that returns a canned {@link ConverseResponse}
 * or throws, so the request-build and response-parse mapping run for real without any
 * network or live Converse call (per the task's mocked-Bedrock directive; ADR-0001 — no
 * live calls in unit tests).
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>§ 6.A.1 / § 7</b> — the adapter builds the request from the domain transcript
 *       (modelId + messages), calls converse once, and parses the response into the
 *       domain Turn (stopReason, content, usage).</li>
 *   <li><b>ADR-0001 / ExitCode.MODEL_BACKEND</b> — an SDK failure on the Converse call is
 *       surfaced as a typed {@link ModelBackendException} (exit 4), chaining the cause.</li>
 *   <li><b>INV-6 / CT-INV-5</b> — an unpaired toolResult is rejected before any call.</li>
 * </ul>
 */
class ModelClientTest {

    private static final String MODEL_ID = "anthropic.claude-opus-4-8";

    /**
     * A hand-rolled {@link BedrockRuntimeClient} test double. It captures the request it
     * receives and returns a supplied response, or throws a supplied failure — never
     * touching the network. The SUT is {@link ModelClient}; this only stubs the external
     * Bedrock dependency.
     */
    private static final class StubBedrockClient implements BedrockRuntimeClient {
        private final ConverseResponse response;
        private final RuntimeException failure;
        private final AtomicReference<ConverseRequest> captured = new AtomicReference<>();
        private int converseCalls;

        private StubBedrockClient(ConverseResponse response, RuntimeException failure) {
            this.response = response;
            this.failure = failure;
        }

        static StubBedrockClient returning(ConverseResponse response) {
            return new StubBedrockClient(response, null);
        }

        static StubBedrockClient throwing(RuntimeException failure) {
            return new StubBedrockClient(null, failure);
        }

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            converseCalls++;
            captured.set(request);
            if (failure != null) {
                throw failure;
            }
            return response;
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

    private static ConverseResponse endTurnResponse(String text) {
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(c -> c.text(text))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason("end_turn")
                .usage(u -> u.inputTokens(11).outputTokens(22).totalTokens(33))
                .build();
    }

    @Test
    @DisplayName("§ 6.A.1: converse builds the request, calls Bedrock once, and parses the Turn")
    void convserse_roundTrips() {
        // Oracle: § 6.A.1 / § 7 — the adapter sends one Converse turn: it builds the
        // request from modelId + the transcript, calls converse, and parses the response
        // into a domain Turn (stopReason + content, usage). Verify the parsed values trace
        // to the canned response the stub returns (the SDK shape § 7 pins), not to impl
        // internals.
        StubBedrockClient stub = StubBedrockClient.returning(endTurnResponse("hi there"));
        ModelClient client = new ModelClient(stub);

        ModelClient.Turn turn = client.converse(
                MODEL_ID,
                List.of(ConverseMessage.user(List.of(ContentBlock.text("hello")))),
                null,
                null);

        assertEquals(1, stub.converseCalls, "§ 6.A.1: exactly one Converse call per turn");
        assertEquals(MODEL_ID, stub.captured.get().modelId(),
                "§ 7: the built request carries the model id");
        assertEquals(StopReason.END_TURN, turn.response().stopReason(),
                "§ 6.A.1: the parsed stop reason traces to the response stopReason 'end_turn'");
        assertEquals(1, turn.response().content().size());
        assertTrue(turn.response().content().get(0) instanceof ContentBlock.Text,
                "§ 7: the model text turn parses to a domain TextBlock");
        assertEquals(11, turn.usage().inputTokens(),
                "§ 7: the Turn usage traces to the response usage envelope");
        assertEquals(22, turn.usage().outputTokens());
    }

    @Test
    @DisplayName("ADR-0001 / exit 4: an SDK Converse failure is surfaced as ModelBackendException")
    void converse_wrapsBackendFailure() {
        // Oracle: ADR-0001 / ExitCode.MODEL_BACKEND(4) — an unrecoverable backend failure
        // on the Converse call must surface as the typed model-backend exception, chaining
        // the underlying SDK exception so the failure is debuggable.
        ValidationException sdkFailure = (ValidationException) ValidationException.builder()
                .message("bad request")
                .build();
        ModelClient client = new ModelClient(StubBedrockClient.throwing(sdkFailure));

        ModelBackendException thrown = assertThrows(ModelBackendException.class,
                () -> client.converse(
                        MODEL_ID,
                        List.of(ConverseMessage.user(List.of(ContentBlock.text("hello")))),
                        null,
                        null),
                "ADR-0001: a Converse backend failure must surface as ModelBackendException");
        assertSame(sdkFailure, thrown.getCause(),
                "the underlying SDK exception must be chained as the cause (debuggability)");
    }

    @Test
    @DisplayName("CT-INV-5: an unpaired toolResult is rejected before any Converse call")
    void converse_rejectsUnpairedToolResult() {
        // Oracle: CT-INV-5 — a toolResult with no prior toolUse is rejected. The adapter
        // must reject during request build, before reaching the Bedrock call (no call made).
        StubBedrockClient stub = StubBedrockClient.returning(endTurnResponse("unused"));
        ModelClient client = new ModelClient(stub);

        assertThrows(ToolProtocolException.class,
                () -> client.converse(
                        MODEL_ID,
                        List.of(ConverseMessage.user(
                                List.of(ContentBlock.toolResult("tu-x", "ok", "data")))),
                        null,
                        null),
                "CT-INV-5: an unpaired toolResult must be rejected");
        assertEquals(0, stub.converseCalls,
                "CT-INV-5: rejection happens before the Converse call (no backend hit)");
    }

    @Test
    @DisplayName("a toolUse turn round-trips into a domain ToolUseBlock Turn (response → us)")
    void converse_parsesToolUseTurn() {
        // Oracle: § 7 — "ToolUseBlock | toolUse | response → us | from MODEL_RESPONSE";
        // § 6.A.1 — stopReason 'tool_use' drives the loop's tool branch. The adapter must
        // parse a model toolUse turn into a domain ToolUse block with TOOL_USE stop reason.
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(c -> c.toolUse(t -> t.toolUseId("tu-1").name("read_file")
                        .input(software.amazon.awssdk.core.document.Document.mapBuilder()
                                .putString("path", "/x").build())))
                .build();
        ConverseResponse toolUseResponse = ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason("tool_use")
                .usage(u -> u.inputTokens(5).outputTokens(6).totalTokens(11))
                .build();
        ModelClient client = new ModelClient(StubBedrockClient.returning(toolUseResponse));

        ModelClient.Turn turn = client.converse(
                MODEL_ID,
                List.of(ConverseMessage.user(List.of(ContentBlock.text("read it")))),
                null,
                null);

        assertEquals(StopReason.TOOL_USE, turn.response().stopReason(),
                "§ 6.A.1: a toolUse turn carries the TOOL_USE stop reason");
        ContentBlock block = turn.response().content().get(0);
        assertTrue(block instanceof ContentBlock.ToolUse,
                "§ 7: a model toolUse parses to a domain ToolUseBlock");
        assertEquals("tu-1", ((ContentBlock.ToolUse) block).toolUseId());
    }

    @Test
    @DisplayName("null Bedrock client is rejected (the client seam is required)")
    void nullBedrockClient_rejected() {
        // Oracle: component C4 — the adapter is a seam over a constructed Bedrock client;
        // it cannot operate without one.
        assertThrows(NullPointerException.class, () -> new ModelClient(null));
    }

    @Test
    @DisplayName("a Turn cannot be constructed without a response and usage")
    void turn_requiresResponseAndUsage() {
        // Oracle: § 7 — a model turn is response (stopReason + content) plus usage; a Turn
        // that omits either is not a valid record of the turn.
        assertThrows(NullPointerException.class, () -> new ModelClient.Turn(null, null));
    }

    @Test
    @DisplayName("§ 6.A.1: the system prompt and an empty toolConfig path build a valid request")
    void converse_withSystemPrompt() {
        // Oracle: § 7 — the system prompt maps to system[]; § 6.A.1 — toolConfig is optional
        // (null when no tools). Exercise the system-prompt path of the adapter end to end.
        StubBedrockClient stub = StubBedrockClient.returning(endTurnResponse("ok"));
        ModelClient client = new ModelClient(stub);

        ModelClient.Turn turn = client.converse(
                MODEL_ID,
                List.of(ConverseMessage.user(List.of(ContentBlock.text("hi")))),
                List.of("You are a coding agent."),
                null);

        assertNotNull(turn);
        assertTrue(stub.captured.get().hasSystem(),
                "§ 7: the system prompt reaches the request system[]");
        assertEquals("You are a coding agent.", stub.captured.get().system().get(0).text());
    }
}
