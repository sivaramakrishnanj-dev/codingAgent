package com.srk.codingagent.model.converse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.model.PromptCacheCaps;
import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.ModelResponsePayload;
import com.srk.codingagent.persistence.ModelUsagePayload;
import com.srk.codingagent.persistence.StopReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointType;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.Type;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.DocumentFormat;
import software.amazon.awssdk.services.bedrockruntime.model.ImageFormat;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;
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
    @DisplayName("DCR-2 — inferenceConfig.maxTokens output budget (ADR-0012 § 2.1, D1)")
    class OutputBudget {

        private static final List<ConverseMessage> ONE_MESSAGE =
                List.of(ConverseMessage.user(List.of(ContentBlock.text("draft the requirements"))));

        @Test
        @DisplayName("DCR-2/D1: a mapper built with the greenfield budget carries inferenceConfig.maxTokens = 16384 on the request")
        void greenfieldBudgetMapperSetsMaxTokens() {
            // Oracle: ADR-0012 "Greenfield-phase output-token budget" + 02-architecture.md § 2.1 —
            // "The greenfield phases (C3) set an explicit budget of 16384 tokens (16K)" via
            // inferenceConfig.maxTokens on the Converse request. The expected value (16384) traces
            // to the ADR's chosen value, not to impl. Build a request with the greenfield-budget
            // mapper and assert the request carries that maxTokens.
            ConverseWireMapper greenfield =
                    new ConverseWireMapper(ConverseWireMapper.GREENFIELD_MAX_OUTPUT_TOKENS);

            ConverseRequest request = greenfield.toRequest(MODEL_ID, ONE_MESSAGE, null, null);

            InferenceConfiguration inference = request.inferenceConfig();
            assertNotNull(inference,
                    "DCR-2: the greenfield request must carry an inferenceConfig bounding the output");
            assertEquals(16384, inference.maxTokens(),
                    "ADR-0012 § 2.1: the greenfield output budget is 16384 tokens (16K), set as "
                            + "inferenceConfig.maxTokens so a large deliverable is not truncated at the "
                            + "backend default 4096 cap");
        }

        @Test
        @DisplayName("DCR-2/D1: the published greenfield budget constant is 16384 (16K), the ADR-0012 § 2.1 chosen value")
        void greenfieldBudgetConstantIsSixteenK() {
            // Oracle: ADR-0012 "Chosen value: 16384 tokens (16K)" / 02-architecture.md § 2.1. The
            // public constant the greenfield path threads must be exactly the ADR's chosen value.
            assertEquals(16384, ConverseWireMapper.GREENFIELD_MAX_OUTPUT_TOKENS,
                    "ADR-0012 § 2.1: the greenfield output-token budget is 16384 (16K)");
        }

        @Test
        @DisplayName("the default (no-arg) mapper sets no inferenceConfig (backend default cap applies — the brownfield/one-shot path)")
        void defaultMapperSetsNoInferenceConfig() {
            // Oracle: 02-architecture.md § 2.1 — only "the greenfield phases (C3) set an explicit
            // budget"; the brownfield/one-shot path is unchanged (no inferenceConfig, so the backend
            // default output cap applies as before). The no-arg mapper must therefore set none.
            ConverseRequest request = mapper.toRequest(MODEL_ID, ONE_MESSAGE, null, null);

            assertNull(request.inferenceConfig(),
                    "§ 2.1: the non-greenfield path sets no inferenceConfig (the backend default cap "
                            + "applies, the prior behaviour)");
        }

        @Test
        @DisplayName("the greenfield budget is carried alongside system + toolConfig (the full greenfield request shape)")
        void budgetCarriedAlongsideSystemAndToolConfig() {
            // Oracle: 04-apis § 3 / § 6.A.1 — the request shape carries modelId, messages, system,
            // toolConfig, AND inferenceConfig. Setting the output budget must not drop the other
            // request members; assert all coexist on the greenfield request.
            ConverseWireMapper greenfield =
                    new ConverseWireMapper(ConverseWireMapper.GREENFIELD_MAX_OUTPUT_TOKENS);
            ToolConfiguration toolConfig = ToolConfiguration.builder()
                    .tools(Tool.fromToolSpec(ToolSpecification.builder()
                            .name("read_file")
                            .inputSchema(s -> s.json(Document.mapBuilder().build()))
                            .build()))
                    .build();

            ConverseRequest request = greenfield.toRequest(
                    MODEL_ID, ONE_MESSAGE, List.of("greenfield requirements playbook"), toolConfig);

            assertEquals(16384, request.inferenceConfig().maxTokens(),
                    "the greenfield output budget is set");
            assertTrue(request.hasSystem() && !request.system().isEmpty(),
                    "the system blocks are still carried");
            assertSame(toolConfig, request.toolConfig(),
                    "the toolConfig is still carried");
        }

        @Test
        @DisplayName("a non-positive output-token cap is rejected at construction")
        void rejectsNonPositiveCap() {
            // A budget must be a positive token count to be meaningful; a zero/negative cap is a
            // construction error (defensive — the production value is the positive 16K constant).
            assertThrows(IllegalArgumentException.class, () -> new ConverseWireMapper(0));
            assertThrows(IllegalArgumentException.class, () -> new ConverseWireMapper(-1));
        }
    }

    @Nested
    @DisplayName("T-4.4 — prompt-cache placement (cachePoint after tools→system→memory-index, capability-gated; ADR-0006, OQ-I)")
    class PromptCachePlacement {

        // ADR-0006: "Opus 4.5/4.6: >= 4096" tokens per checkpoint (§ 6.A.1). The PromptCacheCaps
        // value object's own javadoc names this figure too. Used as the model's per-checkpoint
        // minimum the gate compares the stable-prefix estimate against.
        private static final int OPUS_MIN_TOKENS_PER_CHECKPOINT = 4096;

        private static PromptCacheCaps opusCaps() {
            // A prompt-cache-SUPPORTING profile's caps: the Opus per-checkpoint minimum, the
            // §6.A.1 max-4-checkpoints, and the 1h TTL Opus 4.5 supports. Only the
            // minTokensPerCheckpoint figure is load-bearing for the gate; the others are valid
            // shape so the value object constructs.
            return new PromptCacheCaps(OPUS_MIN_TOKENS_PER_CHECKPOINT, 4,
                    List.of(PromptCacheCaps.TimeToLive.ONE_HOUR));
        }

        /** A system prompt whose total char length comfortably clears the 4096-token minimum. */
        private static List<String> largeStablePrefix() {
            // ADR-0006 gates on the prefix meeting the model's token minimum. The build-time gate
            // uses a documented ~chars/4 estimate (no exact tokenizer pre-call, § 6.A.1), so a
            // prefix well above 4096 * 4 = 16384 chars must clear the gate by any reasonable
            // estimate. Use 40000 chars (~10000 estimated tokens) — unambiguously above-minimum.
            return List.of("S".repeat(40_000));
        }

        private static List<ConverseMessage> oneUserTurn() {
            return List.of(ConverseMessage.user(List.of(ContentBlock.text("do the task"))));
        }

        private static long cachePointBlockCount(List<SystemContentBlock> system) {
            return system.stream().filter(b -> b.cachePoint() != null).count();
        }

        @Test
        @DisplayName("ADR-0006: a cachePoint is placed when the profile reports prompt-cache support and the prefix meets the minimum")
        void placesCachePointWhenSupportedAndPrefixMeetsMinimum() {
            // Oracle: ADR-0006 — "Place a cachePoint after the STABLE PREFIX ... Capability-gated.
            // ONLY when the profile reports prompt-cache support AND the prefix meets the model's
            // token minimum". A supporting profile (non-null caps) with an above-minimum stable
            // prefix must yield exactly one cachePoint on the built request.
            ConverseWireMapper cachingMapper = new ConverseWireMapper(null, opusCaps());

            ConverseRequest request = cachingMapper.toRequest(
                    MODEL_ID, oneUserTurn(), largeStablePrefix(), null);

            assertEquals(1, cachePointBlockCount(request.system()),
                    "ADR-0006: prompt-cache supported + above-minimum prefix => exactly one cachePoint");
        }

        @Test
        @DisplayName("ADR-0006 (graceful degradation): NO cachePoint when the profile's promptCache is null (unsupported)")
        void placesNoCachePointWhenPromptCacheUnsupported() {
            // Oracle: ADR-0006 — "Absent support => NO cachePoint, loop unaffected (graceful
            // degradation)"; § 2.6 — "null = unsupported". A mapper carrying null caps (the
            // capability-absent case) must place no cachePoint even with an above-minimum prefix.
            ConverseWireMapper nonCachingMapper = new ConverseWireMapper(null, null);

            ConverseRequest request = nonCachingMapper.toRequest(
                    MODEL_ID, oneUserTurn(), largeStablePrefix(), null);

            assertEquals(0, cachePointBlockCount(request.system()),
                    "ADR-0006: prompt-cache unsupported (null caps) => no cachePoint (graceful degradation)");
        }

        @Test
        @DisplayName("ADR-0006: the request still builds and is valid when prompt-cache is unsupported (loop unaffected)")
        void requestStillValidWhenPromptCacheUnsupported() {
            // Oracle: ADR-0006 — "Absent support => NO cachePoint, loop unaffected". The
            // unsupported path must still produce a well-formed request: the modelId, the
            // messages, and the system text blocks all carried, just without a breakpoint.
            ConverseWireMapper nonCachingMapper = new ConverseWireMapper(null, null);

            ConverseRequest request = nonCachingMapper.toRequest(
                    MODEL_ID, oneUserTurn(), List.of("You are a coding agent."), null);

            assertEquals(MODEL_ID, request.modelId(), "the request still carries the model id");
            assertEquals(1, request.messages().size(), "the messages tail is still carried");
            assertTrue(request.hasSystem(), "the system blocks are still carried");
            assertEquals(1, request.system().size(),
                    "ADR-0006: only the system text block, no appended cachePoint (unsupported)");
            assertEquals(SystemContentBlock.Type.TEXT, request.system().get(0).type(),
                    "the lone system block is the text block, not a cachePoint");
        }

        @Test
        @DisplayName("ADR-0006: NO cachePoint when the stable prefix is below the model's token minimum")
        void placesNoCachePointWhenPrefixBelowMinimum() {
            // Oracle: ADR-0006 — the gate requires the prefix to "meet the model's token minimum
            // (Opus 4.5/4.6: >= 4096)". A tiny stable prefix (a handful of chars, far below the
            // 4096-token minimum by any reasonable estimate) must NOT get a cachePoint even though
            // prompt-cache IS supported — the second gate condition fails.
            ConverseWireMapper cachingMapper = new ConverseWireMapper(null, opusCaps());

            ConverseRequest request = cachingMapper.toRequest(
                    MODEL_ID, oneUserTurn(), List.of("You are a coding agent."), null);

            assertEquals(0, cachePointBlockCount(request.system()),
                    "ADR-0006: a below-minimum stable prefix gets no cachePoint, even when supported");
        }

        @Test
        @DisplayName("ADR-0006 (simplified single-breakpoint): exactly ONE cachePoint is placed, never multiple")
        void placesExactlyOneBreakpointNeverMultiple() {
            // Oracle: ADR-0006 — "Use the model's SIMPLIFIED SINGLE-BREAKPOINT cache management ...
            // don't micro-manage multiple checkpoints in v1". Even with several system blocks
            // (tools/system/memory-index regions), the mapper places exactly one breakpoint, not
            // one per block.
            ConverseWireMapper cachingMapper = new ConverseWireMapper(null, opusCaps());
            List<String> multiBlockPrefix = List.of(
                    "S".repeat(20_000), "M".repeat(20_000), "I".repeat(20_000));

            ConverseRequest request = cachingMapper.toRequest(
                    MODEL_ID, oneUserTurn(), multiBlockPrefix, null);

            assertEquals(1, cachePointBlockCount(request.system()),
                    "ADR-0006: the simplified single-breakpoint strategy places exactly one cachePoint");
        }

        @Test
        @DisplayName("ADR-0006: the cachePoint is placed AFTER the stable prefix (last in system[]) so tools+system+memory-index are the cached region")
        void cachePointIsLastInSystemAfterTheStablePrefix() {
            // Oracle: ADR-0006 — "Place a cachePoint AFTER the STABLE PREFIX — tools → system →
            // memory-index"; § 6.A.1 — "cache order is tools→system→messages". The cached region is
            // everything BEFORE the breakpoint, so to cache tools+system+memory-index the cachePoint
            // must be the LAST system block (the text blocks — which include the memory-index tail —
            // come first, the breakpoint last), leaving the variable messages tail uncached.
            ConverseWireMapper cachingMapper = new ConverseWireMapper(null, opusCaps());
            List<String> prefix = largeStablePrefix();

            ConverseRequest request = cachingMapper.toRequest(MODEL_ID, oneUserTurn(), prefix, null);

            List<SystemContentBlock> system = request.system();
            assertEquals(prefix.size() + 1, system.size(),
                    "ADR-0006: the cachePoint is appended after the system text blocks (one extra block)");
            for (int i = 0; i < prefix.size(); i++) {
                assertEquals(SystemContentBlock.Type.TEXT, system.get(i).type(),
                        "ADR-0006: the stable-prefix text blocks come first (index " + i + ")");
            }
            SystemContentBlock last = system.get(system.size() - 1);
            assertEquals(SystemContentBlock.Type.CACHE_POINT, last.type(),
                    "ADR-0006: the cachePoint is the LAST system block (after the stable prefix), so "
                            + "tools+system+memory-index are the cached region and messages stay uncached");
        }

        @Test
        @DisplayName("ADR-0006 (conservatism): the single cachePoint uses the default kind, no multi-checkpoint TTL micro-management")
        void cachePointUsesDefaultKind() {
            // Oracle: ADR-0006 — "Conservatism. Use the model's SIMPLIFIED SINGLE-BREAKPOINT cache
            // management where available; don't micro-manage multiple checkpoints in v1". The one
            // breakpoint is the default cachePoint kind (no bespoke TTL juggling), the conservative
            // v1 shape.
            ConverseWireMapper cachingMapper = new ConverseWireMapper(null, opusCaps());

            ConverseRequest request = cachingMapper.toRequest(
                    MODEL_ID, oneUserTurn(), largeStablePrefix(), null);

            SystemContentBlock cachePointBlock = request.system().stream()
                    .filter(b -> b.cachePoint() != null)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a cachePoint block"));
            assertEquals(CachePointType.DEFAULT, cachePointBlock.cachePoint().type(),
                    "ADR-0006: the single breakpoint is the default cachePoint kind (conservative v1)");
        }

        @Test
        @DisplayName("ADR-0006: NO cachePoint when there is no stable prefix (empty system), even when prompt-cache is supported")
        void placesNoCachePointWhenNoStablePrefix() {
            // Oracle: ADR-0006 — the cached region is the stable prefix (tools→system→memory-index).
            // With no system blocks there is no stable region to cache (the estimate is 0 tokens,
            // below any minimum), so no cachePoint is placed and the request still builds.
            ConverseWireMapper cachingMapper = new ConverseWireMapper(null, opusCaps());

            ConverseRequest request = cachingMapper.toRequest(MODEL_ID, oneUserTurn(), null, null);

            assertTrue(request.system() == null || request.system().isEmpty(),
                    "ADR-0006: no system prefix => no system[] cachePoint to place");
        }

        @Test
        @DisplayName("the placement is orthogonal to the output budget: a greenfield-budget mapper with caps still carries BOTH")
        void placementCoexistsWithOutputBudget() {
            // Oracle: ADR-0006 (cachePoint placement) + ADR-0012/DCR-2 (output budget) are
            // independent request members; a mapper carrying both must set inferenceConfig.maxTokens
            // AND place the cachePoint, dropping neither (the greenfield path threads both).
            ConverseWireMapper greenfieldCaching = new ConverseWireMapper(
                    ConverseWireMapper.GREENFIELD_MAX_OUTPUT_TOKENS, opusCaps());

            ConverseRequest request = greenfieldCaching.toRequest(
                    MODEL_ID, oneUserTurn(), largeStablePrefix(), null);

            assertEquals(16384, request.inferenceConfig().maxTokens(),
                    "ADR-0012: the output budget is still set");
            assertEquals(1, cachePointBlockCount(request.system()),
                    "ADR-0006: the cachePoint is still placed alongside the output budget");
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
    @DisplayName("multimodal attachments — Image/Document → image/document (T-4.2, § 2.3 / § 7)")
    class Multimodal {

        private static final byte[] IMAGE_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A};
        private static final byte[] DOC_BYTES = "PDF-1.7 body".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);

        @Test
        @DisplayName("§ 7 / § 2.3: an Image block maps to a Converse image{format, source{bytes}}")
        void mapsImageBlock(@TempDir Path dir) throws IOException {
            // Oracle: § 2.3 / § 7 — "ImageBlock → image {format, source{bytes}}" (us → request,
            // input only). The block's format is carried into the wire image format, and the raw
            // bytes the bytesRef points to are wrapped in source.bytes (the SDK base64-encodes).
            Path png = dir.resolve("diagram.png");
            Files.write(png, IMAGE_BYTES);
            ContentBlock image = ContentBlock.image("png", png.toString());

            ConverseRequest request = mapper.toRequest(
                    MODEL_ID, List.of(ConverseMessage.user(List.of(image))), null, null);

            var wireBlock = request.messages().get(0).content().get(0);
            assertEquals(Type.IMAGE, wireBlock.type(),
                    "§ 7: an Image block maps to a Converse image block");
            assertEquals(ImageFormat.PNG, wireBlock.image().format(),
                    "§ 2.3: the image format is carried to the wire image format");
            assertArrayEquals(IMAGE_BYTES, wireBlock.image().source().bytes().asByteArray(),
                    "§ 2.3: the raw bytes from bytesRef are carried into source.bytes");
        }

        @Test
        @DisplayName("§ 7 / § 2.3: a Document block maps to a Converse document{name, format, source{bytes}}")
        void mapsDocumentBlock(@TempDir Path dir) throws IOException {
            // Oracle: § 2.3 / § 7 — "DocumentBlock → document {name, format, source{bytes}}" (us →
            // request, input only). The neutral name (INV-18), the format, and the raw bytes are all
            // carried to the wire document block.
            Path pdf = dir.resolve("spec.pdf");
            Files.write(pdf, DOC_BYTES);
            ContentBlock document = ContentBlock.document("use case spec", "pdf", pdf.toString());

            ConverseRequest request = mapper.toRequest(
                    MODEL_ID, List.of(ConverseMessage.user(List.of(document))), null, null);

            var wireBlock = request.messages().get(0).content().get(0);
            assertEquals(Type.DOCUMENT, wireBlock.type(),
                    "§ 7: a Document block maps to a Converse document block");
            assertEquals("use case spec", wireBlock.document().name(),
                    "§ 2.3 / INV-18: the neutral name is carried verbatim to the wire document name");
            assertEquals(DocumentFormat.PDF, wireBlock.document().format(),
                    "§ 2.3: the document format is carried to the wire document format");
            assertArrayEquals(DOC_BYTES, wireBlock.document().source().bytes().asByteArray(),
                    "§ 2.3: the raw bytes from bytesRef are carried into source.bytes");
        }

        @Test
        @DisplayName("§ 2.3: an attachment whose bytesRef cannot be read is rejected naming the reference")
        void unreadableBytesRefRejected() {
            // Oracle: § 2.3 — the attachment is "sourced as raw bytes"; if the referenced bytes
            // cannot be read the request cannot carry a valid attachment, so it is rejected (rather
            // than sending an empty/partial block). The rejection names the offending reference.
            ContentBlock image = ContentBlock.image("png", "/no/such/file/diagram.png");

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> mapper.toRequest(
                            MODEL_ID, List.of(ConverseMessage.user(List.of(image))), null, null),
                    "§ 2.3: an unreadable attachment bytesRef must be rejected");
            assertTrue(thrown.getMessage().contains("/no/such/file/diagram.png"),
                    "the rejection names the unreadable reference; was: " + thrown.getMessage());
        }

        @Test
        @DisplayName("§ 2.3: a prompt text block and an image attachment coexist on one user turn")
        void promptAndImageCoexistOnTurn(@TempDir Path dir) throws IOException {
            // Oracle: § 2.3 — a multimodal turn carries the prompt text plus the attachment; the
            // mapper renders both blocks of the one user turn (the C1→C4 path's shape).
            Path png = dir.resolve("x.png");
            Files.write(png, IMAGE_BYTES);
            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(ConverseMessage.user(List.of(
                            ContentBlock.text("review this diagram"),
                            ContentBlock.image("png", png.toString())))),
                    null, null);

            var content = request.messages().get(0).content();
            assertEquals(2, content.size(), "§ 2.3: the prompt text and the image are both rendered");
            assertEquals(Type.TEXT, content.get(0).type(), "the prompt text leads the turn");
            assertEquals(Type.IMAGE, content.get(1).type(), "the image attachment follows it");
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

    @Nested
    @DisplayName("INV-7 / CT-INV-6 — reasoning signature round-trip (response → us → request)")
    class ReasoningSignatureRoundTrip {

        private static final String SIGNATURE = "EqQBCkYIAR...verbatim-tamper-check-hash==";

        private static ConverseResponse reasoningResponse(String text, String signature) {
            software.amazon.awssdk.services.bedrockruntime.model.ContentBlock reasoningBlock =
                    software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromReasoningContent(
                            ReasoningContentBlock.fromReasoningText(
                                    ReasoningTextBlock.builder().text(text).signature(signature).build()));
            software.amazon.awssdk.services.bedrockruntime.model.ContentBlock textBlock =
                    software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromText("the answer");
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(List.of(reasoningBlock, textBlock))
                    .build();
            return responseWith(message, "end_turn");
        }

        @Test
        @DisplayName("§ 6.A.1: a wire reasoningContent block parses to a domain Reasoning with its signature verbatim")
        void parsesReasoningBlockKeepingSignature() {
            // Oracle: § 6.A.1 / content-block.schema.json ReasoningBlock — the signature is a
            // tamper-check hash the response carries; parsing must read it back verbatim (INV-7).
            List<ContentBlock> blocks =
                    mapper.toContentBlocks(reasoningResponse("deep thought", SIGNATURE));

            ContentBlock.Reasoning reasoning = (ContentBlock.Reasoning) blocks.get(0);
            assertEquals("deep thought", reasoning.text(),
                    "§ 6.A.1: the reasoning text parses back verbatim");
            assertEquals(SIGNATURE, reasoning.signature(),
                    "INV-7: the reasoning signature parses back byte-identical");
        }

        @Test
        @DisplayName("INV-7: a domain Reasoning block maps to a wire reasoningContent carrying the signature verbatim")
        void mapsReasoningBlockKeepingSignature() {
            // Oracle: INV-7 — a ReasoningBlock MUST be replayed verbatim with its signature, or
            // the live Converse call errors. Mapping our Reasoning block to the wire must put the
            // signature into reasoningContent.reasoningText.signature unchanged.
            ContentBlock reasoning = ContentBlock.reasoning("thinking", SIGNATURE, null);

            ConverseRequest request = mapper.toRequest(
                    MODEL_ID,
                    List.of(ConverseMessage.assistant(List.of(reasoning))),
                    null,
                    null);

            var wireBlock = request.messages().get(0).content().get(0);
            ReasoningTextBlock wireReasoning = wireBlock.reasoningContent().reasoningText();
            assertEquals(SIGNATURE, wireReasoning.signature(),
                    "INV-7: the signature is carried into the wire reasoningContent verbatim");
            assertEquals("thinking", wireReasoning.text(),
                    "§ 6.A.1: the reasoning text is carried verbatim");
        }

        @Test
        @DisplayName("CT-INV-6: a reasoning signature survives response → ContentBlock → request byte-identical")
        void ctInv6_signatureSurvivesFullRoundTrip() {
            // Oracle: CT-INV-6 (positive) — "a replayed ReasoningBlock keeps its signature". The
            // full path a derived session exercises: a model response carries a reasoning block;
            // we parse it (response → us), then re-send it (us → request). The signature on the
            // re-sent request must be byte-identical to the one the response carried — not merely
            // "a signature is present". A mutated/dropped signature would error the live call (INV-7).
            List<ContentBlock> parsed = mapper.toContentBlocks(reasoningResponse("reasoned", SIGNATURE));

            // Re-send the parsed assistant turn (the verbatim carryover a derived seed performs).
            ConverseRequest reSent = mapper.toRequest(
                    MODEL_ID,
                    List.of(ConverseMessage.assistant(parsed)),
                    null,
                    null);

            String reSentSignature = reSent.messages().get(0).content().stream()
                    .filter(b -> b.reasoningContent() != null)
                    .map(b -> b.reasoningContent().reasoningText().signature())
                    .findFirst()
                    .orElse(null);
            assertEquals(SIGNATURE, reSentSignature,
                    "CT-INV-6/INV-7: the reasoning signature is byte-identical after response→us→request");
        }

        @Test
        @DisplayName("INV-7: a redacted-only reasoning block round-trips through response → us → request")
        void redactedReasoningRoundTrips() {
            // Oracle: content-block.schema.json ReasoningBlock — redactedContent is the base64
            // redacted reasoning variant; it must survive the round-trip (the provider may return
            // redacted reasoning, which still must be replayed).
            software.amazon.awssdk.services.bedrockruntime.model.ContentBlock redacted =
                    software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromReasoningContent(
                            ReasoningContentBlock.fromRedactedContent(
                                    software.amazon.awssdk.core.SdkBytes.fromUtf8String("REDACTED")));
            Message message = Message.builder()
                    .role(ConversationRole.ASSISTANT)
                    .content(List.of(redacted))
                    .build();

            List<ContentBlock> parsed = mapper.toContentBlocks(responseWith(message, "end_turn"));
            ContentBlock.Reasoning reasoning = (ContentBlock.Reasoning) parsed.get(0);
            assertEquals("REDACTED", reasoning.redactedContent(),
                    "the redacted content parses back verbatim");

            ConverseRequest reSent = mapper.toRequest(
                    MODEL_ID, List.of(ConverseMessage.assistant(parsed)), null, null);
            var wire = reSent.messages().get(0).content().get(0).reasoningContent();
            assertEquals("REDACTED", wire.redactedContent().asUtf8String(),
                    "INV-7: redacted reasoning content survives the round-trip verbatim");
        }
    }
}
