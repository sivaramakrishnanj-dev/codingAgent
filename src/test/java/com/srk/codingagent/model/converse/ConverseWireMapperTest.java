package com.srk.codingagent.model.converse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.ModelResponsePayload;
import com.srk.codingagent.persistence.ModelUsagePayload;
import com.srk.codingagent.persistence.StopReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.Type;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

/**
 * Unit tests for {@link ConverseWireMapper} — the wire-format boundary between our
 * domain types and the Bedrock Converse types.
 *
 * <p>Oracles trace to the spec, not to the implementation:
 * <ul>
 *   <li><b>03-data-model.md § 7</b> — the translation table: TextBlock&harr;text,
 *       ToolUseBlock&harr;toolUse{toolUseId,name,input}, ToolResultBlock&harr;
 *       toolResult{toolUseId,content,status}, modelId&rarr;request modelId, system
 *       prompt&rarr;system[], stopReason&rarr;our StopReason, usage&rarr;
 *       ModelUsagePayload.</li>
 *   <li><b>§ 6.A.1</b> — request shape (modelId, messages[] with role+content[], system[]
 *       separate, toolConfig); toolResult appended as a USER-role message; stopReason
 *       wire form lowercase/underscore mapped to the UPPERCASE domain enum; usage carries
 *       input/output and optional cacheRead/cacheWrite tokens.</li>
 *   <li><b>INV-6 / CT-INV-5</b> — a toolResult whose toolUseId has no prior toolUse in the
 *       transcript is rejected.</li>
 * </ul>
 * The SUT (a real {@link ConverseWireMapper}) is never mocked; SDK request/response
 * objects are real builder-constructed values, so no network or live Converse call
 * occurs.
 */
class ConverseWireMapperTest {

    private static final String MODEL_ID = "anthropic.claude-opus-4-8";

    private final ConverseWireMapper mapper = new ConverseWireMapper();

    private static ConverseResponse responseWith(Message message, String stopReason) {
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason(stopReason)
                .usage(u -> u.inputTokens(10).outputTokens(20).totalTokens(30))
                .build();
    }

    @Nested
    @DisplayName("toRequest — us → request mapping (§ 7, § 6.A.1)")
    class ToRequest {

        @Test
        @DisplayName("§ 7: ResolvedConfig.modelId maps to the request modelId")
        void mapsModelId() {
            // Oracle: § 7 table — "ResolvedConfig.modelId | request modelId | us → request".
            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(ConverseMessage.user(List.of(ContentBlock.text("hello")))),
                    null,
                    null);

            assertEquals(MODEL_ID, request.modelId(),
                    "§ 7: the request modelId must come from ResolvedConfig.modelId");
        }

        @Test
        @DisplayName("§ 7: a TextBlock maps to a Converse text block on a user-role message")
        void mapsTextBlock() {
            // Oracle: § 7 table — "TextBlock | text | both"; § 6.A.1 — messages[] carry a
            // role and typed content[].
            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(ConverseMessage.user(List.of(ContentBlock.text("hello world")))),
                    null,
                    null);

            assertEquals(1, request.messages().size());
            Message message = request.messages().get(0);
            assertEquals(ConversationRole.USER, message.role(),
                    "§ 6.A.1: a user turn carries the USER role");
            assertEquals(1, message.content().size());
            assertEquals(Type.TEXT, message.content().get(0).type(),
                    "§ 7: a TextBlock maps to a Converse text block");
            assertEquals("hello world", message.content().get(0).text());
        }

        @Test
        @DisplayName("§ 7: a ToolUseBlock maps to toolUse{toolUseId,name,input}")
        void mapsToolUseBlock() {
            // Oracle: § 7 table — "ToolUseBlock | toolUse {toolUseId,name,input}".
            ContentBlock toolUse = ContentBlock.toolUse(
                    "tu-1", "read_file", Map.of("path", "/etc/hosts"));

            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(ConverseMessage.assistant(List.of(toolUse))),
                    null,
                    null);

            var wireBlock = request.messages().get(0).content().get(0);
            assertEquals(Type.TOOL_USE, wireBlock.type(),
                    "§ 7: a ToolUseBlock maps to a Converse toolUse block");
            ToolUseBlock wireToolUse = wireBlock.toolUse();
            assertEquals("tu-1", wireToolUse.toolUseId(),
                    "§ 7: toolUseId is carried verbatim");
            assertEquals("read_file", wireToolUse.name(),
                    "§ 7: the tool name is carried verbatim");
            assertEquals("/etc/hosts", wireToolUse.input().asMap().get("path").asString(),
                    "§ 7: the tool input object is carried into the toolUse input document");
        }

        @Test
        @DisplayName("§ 7 / § 6.A.1: a ToolResultBlock maps to toolResult{toolUseId,content,status} on a USER message")
        void mapsToolResultBlock() {
            // Oracle: § 7 table — "ToolResultBlock | toolResult {toolUseId,content,status} |
            // us → request | appended as a user-role message"; § 6.A.1 — toolResult is
            // appended as a USER-role message. Paired with a prior toolUse to satisfy INV-6.
            ContentBlock priorToolUse = ContentBlock.toolUse("tu-7", "list_dir", Map.of());
            ContentBlock toolResult = ContentBlock.toolResult("tu-7", "ok", Map.of("entries", 3));

            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(
                            ConverseMessage.assistant(List.of(priorToolUse)),
                            ConverseMessage.user(List.of(toolResult))),
                    null,
                    null);

            Message resultMessage = request.messages().get(1);
            assertEquals(ConversationRole.USER, resultMessage.role(),
                    "§ 6.A.1: a toolResult is appended as a USER-role message");
            var wireBlock = resultMessage.content().get(0);
            assertEquals(Type.TOOL_RESULT, wireBlock.type(),
                    "§ 7: a ToolResultBlock maps to a Converse toolResult block");
            ToolResultBlock wireResult = wireBlock.toolResult();
            assertEquals("tu-7", wireResult.toolUseId(),
                    "§ 7: the toolResult toolUseId is carried verbatim");
            assertEquals(ToolResultStatus.SUCCESS, wireResult.status(),
                    "§ 7: domain status 'ok' maps to the Converse SUCCESS status");
        }

        @Test
        @DisplayName("§ 7: an error-status toolResult maps to the Converse ERROR status")
        void mapsErrorToolResultStatus() {
            // Oracle: § 7 — toolResult carries a status; the domain {ok,error} status maps
            // to the Converse {success,error} status. Here the error branch.
            ContentBlock priorToolUse = ContentBlock.toolUse("tu-9", "run", Map.of());
            ContentBlock errorResult = ContentBlock.toolResult("tu-9", "error", "boom");

            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(
                            ConverseMessage.assistant(List.of(priorToolUse)),
                            ConverseMessage.user(List.of(errorResult))),
                    null,
                    null);

            ToolResultBlock wireResult = request.messages().get(1).content().get(0).toolResult();
            assertEquals(ToolResultStatus.ERROR, wireResult.status(),
                    "§ 7: domain status 'error' maps to the Converse ERROR status");
        }

        @Test
        @DisplayName("§ 7 / schema / § 6.A.1 (D2 regression): a String toolResult content maps to the text member, not json")
        void mapsStringToolResultContentToTextMember() {
            // Oracle: content-block.schema.json ToolResultBlock — content is "text, OR a
            // structured object"; § 6.A.1 — a toolResult content block supports text and json
            // members, and the json member must be a JSON object. A plain-text (String) result
            // is the "text" shape, so it must land in the Converse text member.
            //
            // Regression for D2: the prior mapping always used the json member, so a String
            // content produced the real-Bedrock ValidationException "The format of the value at
            // messages.2.content.0.toolResult.content.0.json is invalid. Provide a json object
            // for the field". The structurally-blind existing tests asserted only status, never
            // the content member, which is why the bug shipped. Paired with a prior toolUse to
            // satisfy INV-6.
            ContentBlock priorToolUse = ContentBlock.toolUse("tu-1", "read_file", Map.of());
            ContentBlock toolResult = ContentBlock.toolResult("tu-1", "ok", "file contents here");

            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(
                            ConverseMessage.assistant(List.of(priorToolUse)),
                            ConverseMessage.user(List.of(toolResult))),
                    null,
                    null);

            ToolResultBlock wireResult = request.messages().get(1).content().get(0).toolResult();
            ToolResultContentBlock wireContent = wireResult.content().get(0);
            assertEquals("file contents here", wireContent.text(),
                    "§ 7 / schema: a String (text-shape) toolResult content carries in the text member");
            assertNull(wireContent.json(),
                    "§ 6.A.1: the json member must be absent for a text-shape result (a String is not a JSON object)");
        }

        @Test
        @DisplayName("§ 7 / schema / § 6.A.1: a structured-object toolResult content maps to the json member, not text")
        void mapsStructuredObjectToolResultContentToJsonMember() {
            // Oracle: content-block.schema.json ToolResultBlock — content is "text, OR a
            // structured object (e.g. CommandResult)"; § 6.A.1 — the json member carries a JSON
            // object. A structured-object (Map) result is the "structured object" shape, so it
            // must land in the Converse json member as an object, complementary to the String →
            // text case above (D2).
            Map<String, Object> commandResult = Map.of("exitCode", 0, "stdout", "ok");
            ContentBlock priorToolUse = ContentBlock.toolUse("tu-2", "run", Map.of());
            ContentBlock toolResult = ContentBlock.toolResult("tu-2", "ok", commandResult);

            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(
                            ConverseMessage.assistant(List.of(priorToolUse)),
                            ConverseMessage.user(List.of(toolResult))),
                    null,
                    null);

            ToolResultBlock wireResult = request.messages().get(1).content().get(0).toolResult();
            ToolResultContentBlock wireContent = wireResult.content().get(0);
            assertNull(wireContent.text(),
                    "§ 7 / schema: a structured-object result does not use the text member");
            assertNotNull(wireContent.json(),
                    "§ 6.A.1: a structured-object (Map) result carries in the json member");
            assertTrue(wireContent.json().isMap(),
                    "§ 6.A.1: the json member must be a JSON object (Document map), not a scalar");
            assertEquals(0, wireContent.json().asMap().get("exitCode").asNumber().intValue(),
                    "§ 7: the structured object's fields are carried into the json document");
        }

        @Test
        @DisplayName("§ 7: a toolResult status outside {ok,error} is rejected at the wire boundary")
        void rejectsUnsupportedToolResultStatus() {
            // Oracle: § 7 — the ToolResultBlock status maps to the Converse {success,error}
            // status. A domain status outside the {ok,error} vocabulary has no Converse
            // mapping and must be rejected rather than silently dropped.
            ContentBlock priorToolUse = ContentBlock.toolUse("tu-3", "run", Map.of());
            ContentBlock oddStatus = ContentBlock.toolResult("tu-3", "weird", "data");

            assertThrows(IllegalArgumentException.class,
                    () -> mapper.toRequest(
                            MODEL_ID,
                            List.of(
                                    ConverseMessage.assistant(List.of(priorToolUse)),
                                    ConverseMessage.user(List.of(oddStatus))),
                            null,
                            null),
                    "§ 7: a toolResult status outside {ok,error} must be rejected");
        }

        @Test
        @DisplayName("§ 7: the system prompt maps to the request system[] (separate from messages)")
        void mapsSystemPrompt() {
            // Oracle: § 7 table — "(system prompt + memory index) | system[] | us → request";
            // § 6.A.1 — system[] is separate from messages.
            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(ConverseMessage.user(List.of(ContentBlock.text("hi")))),
                    List.of("You are a coding agent."),
                    null);

            assertTrue(request.hasSystem(), "§ 7: the system prompt populates system[]");
            assertEquals(1, request.system().size());
            assertEquals("You are a coding agent.", request.system().get(0).text(),
                    "§ 7: the system prompt text is carried into a system text block");
        }

        @Test
        @DisplayName("§ 6.A.1: a provided toolConfig is carried onto the request")
        void carriesToolConfig() {
            // Oracle: § 6.A.1 — the request shape includes a toolConfig (tools[] +
            // toolChoice). The Model Client passes through the toolConfig the loop supplies
            // (its rendering is T-0.6); an explicitly provided config must reach the request.
            ToolConfiguration toolConfig = ToolConfiguration.builder()
                    .tools(Tool.fromToolSpec(ToolSpecification.builder().name("read_file").build()))
                    .build();

            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(ConverseMessage.user(List.of(ContentBlock.text("hi")))),
                    null,
                    toolConfig);

            assertSame(toolConfig, request.toolConfig(),
                    "§ 6.A.1: the supplied toolConfig must be carried onto the request");
        }

        @Test
        @DisplayName("an empty transcript is rejected (a request needs at least one message)")
        void rejectsEmptyTranscript() {
            // Oracle: § 6.A.1 — the request shape requires messages[]; an empty transcript
            // is not a valid Converse request.
            assertThrows(IllegalArgumentException.class,
                    () -> mapper.toRequest(MODEL_ID, List.of(), null, null));
        }

        @Test
        @DisplayName("a blank modelId is rejected (§ 7: request modelId is required)")
        void rejectsBlankModelId() {
            // Oracle: § 7 — the request modelId is required; a blank id is invalid.
            assertThrows(IllegalArgumentException.class,
                    () -> mapper.toRequest("  ",
                            List.of(ConverseMessage.user(List.of(ContentBlock.text("hi")))),
                            null, null));
        }
    }

    @Nested
    @DisplayName("INV-6 / CT-INV-5 — toolUse ↔ toolResult pairing")
    class ToolPairing {

        @Test
        @DisplayName("CT-INV-5: a toolResult with no prior toolUse is rejected")
        void ctInv5_unpairedToolResultRejected() {
            // Oracle: CT-INV-5 (negative) — "a TOOL_RESULT whose toolUseId has no prior
            // TOOL_USE is rejected". The first and only block is a toolResult; there is no
            // prior toolUse, so building the request must throw.
            ContentBlock orphanResult = ContentBlock.toolResult("tu-missing", "ok", "data");

            ToolProtocolException thrown = assertThrows(ToolProtocolException.class,
                    () -> mapper.toRequest(
                            MODEL_ID,
                            List.of(ConverseMessage.user(List.of(orphanResult))),
                            null,
                            null),
                    "CT-INV-5: an unpaired toolResult must be rejected");
            assertTrue(thrown.getMessage().contains("tu-missing"),
                    "INV-6: the rejection must name the offending toolUseId");
        }

        @Test
        @DisplayName("INV-6: a toolResult matching a prior toolUse in the same transcript is accepted")
        void inv6_pairedToolResultAccepted() {
            // Oracle: INV-6 — "every ToolResultBlock.toolUseId matches a prior
            // ToolUseBlock.toolUseId in the same session". A toolResult preceded by a
            // matching toolUse is valid, so the request builds without throwing.
            ContentBlock toolUse = ContentBlock.toolUse("tu-1", "read_file", Map.of());
            ContentBlock toolResult = ContentBlock.toolResult("tu-1", "ok", "contents");

            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(
                            ConverseMessage.assistant(List.of(toolUse)),
                            ConverseMessage.user(List.of(toolResult))),
                    null,
                    null);

            assertEquals(2, request.messages().size(),
                    "INV-6: a paired toolResult is accepted and the request is built");
        }

        @Test
        @DisplayName("CT-INV-5: a toolResult is rejected when its matching toolUse comes after it")
        void ctInv5_toolResultBeforeItsToolUseRejected() {
            // Oracle: CT-INV-5 — the match must be a *prior* toolUse. A toolResult whose
            // only matching toolUse appears later in the transcript still has no prior
            // toolUse at its position, so it is rejected.
            ContentBlock toolResult = ContentBlock.toolResult("tu-2", "ok", "data");
            ContentBlock laterToolUse = ContentBlock.toolUse("tu-2", "read_file", Map.of());

            assertThrows(ToolProtocolException.class,
                    () -> mapper.toRequest(
                            MODEL_ID,
                            List.of(
                                    ConverseMessage.user(List.of(toolResult)),
                                    ConverseMessage.assistant(List.of(laterToolUse))),
                            null,
                            null),
                    "CT-INV-5: a toolResult preceding its toolUse has no prior toolUse and is rejected");
        }
    }

    @Nested
    @DisplayName("response parsing — response → us mapping (§ 7, § 6.A.1)")
    class ResponseParsing {

        @Test
        @DisplayName("§ 7: a Converse text block parses to a domain TextBlock")
        void parsesTextBlock() {
            // Oracle: § 7 table — "TextBlock | text | both". A model text turn parses back
            // into a domain Text block.
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.text("the answer is 42"))
                    .build();

            List<ContentBlock> blocks = mapper.toContentBlocks(responseWith(message, "end_turn"));

            assertEquals(1, blocks.size());
            ContentBlock.Text text = assertInstanceOf(blocks.get(0));
            assertEquals("the answer is 42", text.text(),
                    "§ 7: a Converse text block parses to a domain TextBlock with the same text");
        }

        @Test
        @DisplayName("§ 7: a Converse toolUse block parses to a domain ToolUseBlock (response → us)")
        void parsesToolUseBlock() {
            // Oracle: § 7 table — "ToolUseBlock | toolUse {toolUseId,name,input} |
            // response → us | from MODEL_RESPONSE".
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.toolUse(t -> t
                            .toolUseId("tu-42")
                            .name("write_file")
                            .input(Document.mapBuilder()
                                    .putString("path", "/tmp/out.txt")
                                    .build())))
                    .build();

            List<ContentBlock> blocks = mapper.toContentBlocks(responseWith(message, "tool_use"));

            assertEquals(1, blocks.size());
            assertTrue(blocks.get(0) instanceof ContentBlock.ToolUse,
                    "§ 7: a Converse toolUse block parses to a domain ToolUseBlock");
            ContentBlock.ToolUse toolUse = (ContentBlock.ToolUse) blocks.get(0);
            assertEquals("tu-42", toolUse.toolUseId(), "§ 7: toolUseId carried verbatim");
            assertEquals("write_file", toolUse.name(), "§ 7: tool name carried verbatim");
            assertEquals("/tmp/out.txt", toolUse.input().get("path"),
                    "§ 7: the toolUse input document parses back to the domain input map");
        }

        @Test
        @DisplayName("§ 6.A.1: stopReason 'end_turn' maps to domain END_TURN")
        void mapsEndTurnStopReason() {
            // Oracle: § 6.A.1 — wire form is lowercase/underscore (end_turn); the domain
            // StopReason enum is UPPERCASE (END_TURN). § 7 — stopReason is the loop selector.
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.text("done"))
                    .build();

            assertEquals(StopReason.END_TURN, mapper.toStopReason(responseWith(message, "end_turn")),
                    "§ 6.A.1: wire 'end_turn' maps to domain END_TURN");
        }

        @Test
        @DisplayName("§ 6.A.1: stopReason 'tool_use' maps to domain TOOL_USE")
        void mapsToolUseStopReason() {
            // Oracle: § 6.A.1 — wire 'tool_use' is the stop reason that drives the loop's
            // tool branch; it maps to the domain TOOL_USE constant.
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.text("calling a tool"))
                    .build();

            assertEquals(StopReason.TOOL_USE, mapper.toStopReason(responseWith(message, "tool_use")),
                    "§ 6.A.1: wire 'tool_use' maps to domain TOOL_USE");
        }

        @Test
        @DisplayName("§ 6.A.1: every wire stopReason token maps to its UPPERCASE domain constant")
        void mapsEveryStopReasonToken() {
            // Oracle: § 6.A.1 — the full stopReason vocabulary (lowercase/underscore wire
            // form) maps to the UPPERCASE domain enum by case fold. Verify the whole set so
            // no token collapses to the wrong constant.
            Map<String, StopReason> expected = Map.of(
                    "end_turn", StopReason.END_TURN,
                    "tool_use", StopReason.TOOL_USE,
                    "max_tokens", StopReason.MAX_TOKENS,
                    "stop_sequence", StopReason.STOP_SEQUENCE,
                    "guardrail_intervened", StopReason.GUARDRAIL_INTERVENED,
                    "content_filtered", StopReason.CONTENT_FILTERED,
                    "malformed_tool_use", StopReason.MALFORMED_TOOL_USE,
                    "malformed_model_output", StopReason.MALFORMED_MODEL_OUTPUT,
                    "model_context_window_exceeded", StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED);
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.text("x"))
                    .build();

            expected.forEach((wire, domain) ->
                    assertEquals(domain, mapper.toStopReason(responseWith(message, wire)),
                            "§ 6.A.1: wire '" + wire + "' must map to " + domain));
        }

        @Test
        @DisplayName("an unrecognised stopReason token is rejected")
        void rejectsUnknownStopReason() {
            // Oracle: § 6.A.1 — the stopReason vocabulary is closed; a token outside it has
            // no domain mapping and must be rejected rather than silently dropped.
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.text("x"))
                    .build();

            assertThrows(IllegalArgumentException.class,
                    () -> mapper.toStopReason(responseWith(message, "definitely_not_a_reason")));
        }

        @Test
        @DisplayName("§ 7: the usage envelope maps to a ModelUsagePayload")
        void mapsUsage() {
            // Oracle: § 7 table — "Event(MODEL_USAGE) | response usage | response → us";
            // § 6.A.1 — usage carries inputTokens and outputTokens (every call).
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.text("x"))
                    .build();
            ConverseResponse response = ConverseResponse.builder()
                    .output(ConverseOutput.builder().message(message).build())
                    .stopReason("end_turn")
                    .usage(u -> u.inputTokens(123).outputTokens(456).totalTokens(579))
                    .build();

            ModelUsagePayload usage = mapper.toUsage(response);

            assertEquals(123, usage.inputTokens(), "§ 6.A.1: inputTokens maps from the usage envelope");
            assertEquals(456, usage.outputTokens(), "§ 6.A.1: outputTokens maps from the usage envelope");
        }

        @Test
        @DisplayName("§ 6.A.1: usage carries cacheRead/cacheWrite tokens when the response reports them")
        void mapsCacheTokens() {
            // Oracle: § 6.A.1 — usage returns cacheReadInputTokens and cacheWriteInputTokens;
            // these map to the optional ModelUsagePayload cache fields when present.
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.text("x"))
                    .build();
            ConverseResponse response = ConverseResponse.builder()
                    .output(ConverseOutput.builder().message(message).build())
                    .stopReason("end_turn")
                    .usage(u -> u.inputTokens(10).outputTokens(20).totalTokens(30)
                            .cacheReadInputTokens(7).cacheWriteInputTokens(3))
                    .build();

            ModelUsagePayload usage = mapper.toUsage(response);

            assertEquals(7, usage.cacheReadInputTokens(),
                    "§ 6.A.1: cacheReadInputTokens maps when reported");
            assertEquals(3, usage.cacheWriteInputTokens(),
                    "§ 6.A.1: cacheWriteInputTokens maps when reported");
        }

        @Test
        @DisplayName("§ 6.A.1: usage omits cache fields when the response does not report them")
        void omitsAbsentCacheTokens() {
            // Oracle: § 6.A.1 / ModelUsagePayload schema — cache fields are optional; when
            // the response reports none they are null in the payload (omitted from JSON).
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.text("x"))
                    .build();

            ModelUsagePayload usage = mapper.toUsage(responseWith(message, "end_turn"));

            assertNull(usage.cacheReadInputTokens(),
                    "§ 6.A.1: an unreported cacheReadInputTokens is null");
            assertNull(usage.cacheWriteInputTokens(),
                    "§ 6.A.1: an unreported cacheWriteInputTokens is null");
        }

        @Test
        @DisplayName("§ 7: toModelResponse assembles stop reason + content into a ModelResponsePayload")
        void buildsModelResponsePayload() {
            // Oracle: § 7 — a MODEL_RESPONSE is "why the turn stopped plus the model's
            // content blocks". toModelResponse must combine the mapped stopReason and the
            // parsed content into the domain payload the event log records.
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.text("final answer"))
                    .build();

            ModelResponsePayload payload = mapper.toModelResponse(responseWith(message, "end_turn"));

            assertEquals(StopReason.END_TURN, payload.stopReason(),
                    "§ 7: the payload carries the mapped stop reason");
            assertEquals(1, payload.content().size(),
                    "§ 7: the payload carries the parsed content blocks");
        }

        @Test
        @DisplayName("§ 6.A.1: a response with no usage envelope is rejected")
        void rejectsMissingUsage() {
            // Oracle: § 6.A.1 — "usage returned every call". A response missing the usage
            // envelope is malformed against the documented contract and must be rejected
            // rather than yielding a null/zero usage silently.
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(c -> c.text("x"))
                    .build();
            ConverseResponse response = ConverseResponse.builder()
                    .output(ConverseOutput.builder().message(message).build())
                    .stopReason("end_turn")
                    .build();

            assertThrows(IllegalArgumentException.class, () -> mapper.toUsage(response));
        }

        @Test
        @DisplayName("a response with no output message is rejected")
        void rejectsMissingOutputMessage() {
            // Oracle: § 6.A.1 — a Converse response carries output.message.content[]; a
            // response missing the output message cannot be parsed and must be rejected.
            ConverseResponse response = ConverseResponse.builder()
                    .stopReason("end_turn")
                    .usage(u -> u.inputTokens(1).outputTokens(1).totalTokens(2))
                    .build();

            assertThrows(IllegalArgumentException.class, () -> mapper.toContentBlocks(response));
        }

        private static ContentBlock.Text assertInstanceOf(ContentBlock block) {
            assertTrue(block instanceof ContentBlock.Text,
                    "expected a domain Text block but was " + block.getClass().getSimpleName());
            return (ContentBlock.Text) block;
        }
    }
}
