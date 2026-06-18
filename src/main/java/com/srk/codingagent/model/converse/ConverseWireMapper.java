package com.srk.codingagent.model.converse;

import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.ModelResponsePayload;
import com.srk.codingagent.persistence.ModelUsagePayload;
import com.srk.codingagent.persistence.StopReason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

/**
 * The wire-format boundary between our domain types and the Bedrock Converse types
 * (03-data-model.md § 7, § 6.A.1). This is where — and the only place where — our
 * {@link ContentBlock}s, {@link StopReason}, and token usage cross to and from the SDK
 * shapes; the rest of the system stays provider-agnostic (component C4).
 *
 * <p>Request direction (us &rarr; request): build a {@link ConverseRequest} from a
 * model id, a transcript of {@link ConverseMessage}s, an optional system prompt, and an
 * optional {@link ToolConfiguration}. Each domain {@link ContentBlock} maps to its
 * Converse counterpart per the § 7 translation table:
 * <ul>
 *   <li>{@link ContentBlock.Text} &rarr; {@code text}</li>
 *   <li>{@link ContentBlock.ToolUse} &rarr; {@code toolUse{toolUseId,name,input}}</li>
 *   <li>{@link ContentBlock.ToolResult} &rarr; {@code toolResult{toolUseId,content,status}}</li>
 * </ul>
 *
 * <p>Response direction (response &rarr; us): parse {@code output.message.content[]}
 * into domain {@link ContentBlock}s ({@code text} and {@code toolUse} — the kinds a
 * model turn emits), map the wire {@code stopReason} to our {@link StopReason}, and map
 * the {@code usage} envelope to a {@link ModelUsagePayload}. {@link #toModelResponse}
 * assembles the content + stop reason into the {@link ModelResponsePayload} the event
 * log records.
 *
 * <p><b>INV-6 / CT-INV-5 (toolUse&harr;toolResult pairing).</b> {@link #toRequest}
 * enforces the protocol-correctness property ADR-0001 says the agent owns: every
 * {@code toolResult} block in the transcript must carry a {@code toolUseId} produced by
 * an earlier {@code toolUse} block in the same transcript. A {@code toolResult} with no
 * prior {@code toolUse} is rejected with a {@link ToolProtocolException} before any
 * request leaves the boundary (CT-INV-5, the negative test). Reasoning, image,
 * document, and cachePoint blocks are out of scope for this task (deferred to the tasks
 * that need them — see the handoff's {@code stated_assumptions}); only the text,
 * toolUse, and toolResult kinds the title names are mapped.
 */
public final class ConverseWireMapper {

    /**
     * Builds a {@link ConverseRequest} from our domain transcript, enforcing the
     * toolUse&harr;toolResult pairing invariant (INV-6) before returning.
     *
     * @param modelId   the Bedrock model id (from
     *                  {@link com.srk.codingagent.config.ResolvedConfig#modelId()});
     *                  must not be {@code null} or blank.
     * @param messages  the full conversation transcript (Converse is stateless — every
     *                  message is resent each call, § 6.A.1); must not be {@code null}.
     *                  An empty transcript is rejected (a request needs at least one
     *                  message).
     * @param system    the system prompt blocks (system prompt + memory index, § 7), or
     *                  {@code null}/empty for none.
     * @param toolConfig the tool configuration (rendered by the tool registry, T-0.6),
     *                   or {@code null} when no tools are offered.
     * @return the assembled {@link ConverseRequest}.
     * @throws NullPointerException     if {@code modelId} or {@code messages} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if {@code modelId} is blank or {@code messages}
     *                                  is empty.
     * @throws ToolProtocolException    if a {@code toolResult} block references a
     *                                  {@code toolUseId} with no prior {@code toolUse}
     *                                  in the transcript (INV-6, CT-INV-5).
     */
    public ConverseRequest toRequest(
            String modelId,
            List<ConverseMessage> messages,
            List<String> system,
            ToolConfiguration toolConfig) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must be non-blank");
        }
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("a Converse request needs at least one message");
        }
        requireToolResultsPaired(messages);

        List<Message> wireMessages = new ArrayList<>(messages.size());
        for (ConverseMessage message : messages) {
            wireMessages.add(toWireMessage(message));
        }

        ConverseRequest.Builder builder = ConverseRequest.builder()
                .modelId(modelId)
                .messages(wireMessages);
        if (system != null && !system.isEmpty()) {
            builder.system(toSystemBlocks(system));
        }
        if (toolConfig != null) {
            builder.toolConfig(toolConfig);
        }
        return builder.build();
    }

    /**
     * Parses a {@link ConverseResponse} into the domain content blocks of the model
     * turn: {@code output.message.content[]} mapped to {@link ContentBlock.Text} and
     * {@link ContentBlock.ToolUse} (the kinds a model turn emits, § 6.A.1).
     *
     * @param response the Converse response; must not be {@code null} and must carry an
     *                 {@code output.message}.
     * @return the model turn's content blocks, in order; never {@code null}.
     * @throws NullPointerException     if {@code response} is {@code null}.
     * @throws IllegalArgumentException if the response carries no {@code output.message}.
     */
    public List<ContentBlock> toContentBlocks(ConverseResponse response) {
        Objects.requireNonNull(response, "response");
        if (response.output() == null || response.output().message() == null) {
            throw new IllegalArgumentException("Converse response carries no output.message");
        }
        List<software.amazon.awssdk.services.bedrockruntime.model.ContentBlock> wireBlocks =
                response.output().message().content();
        List<ContentBlock> blocks = new ArrayList<>(wireBlocks.size());
        for (software.amazon.awssdk.services.bedrockruntime.model.ContentBlock wireBlock : wireBlocks) {
            blocks.add(toDomainResponseBlock(wireBlock));
        }
        return blocks;
    }

    /**
     * Maps the wire {@code stopReason} of a response to the domain {@link StopReason}
     * (§ 6.A.1; § 7 — the loop's state selector).
     *
     * @param response the Converse response; must not be {@code null} and must carry a
     *                 stop reason.
     * @return the domain stop reason.
     * @throws NullPointerException     if {@code response} is {@code null}.
     * @throws IllegalArgumentException if the response carries no recognised stop reason.
     */
    public StopReason toStopReason(ConverseResponse response) {
        Objects.requireNonNull(response, "response");
        return StopReasonMapper.fromWire(response.stopReasonAsString());
    }

    /**
     * Maps the {@code usage} envelope of a response to the domain
     * {@link ModelUsagePayload} that drives a {@code MODEL_USAGE} event (§ 7 —
     * {@code Event(MODEL_USAGE): response usage}).
     *
     * @param response the Converse response; must not be {@code null} and must carry a
     *                 usage envelope (Converse returns usage on every call, § 6.A.1).
     * @return the domain usage payload, including cache-read/write tokens when the
     *         response reports them.
     * @throws NullPointerException     if {@code response} is {@code null}.
     * @throws IllegalArgumentException if the response carries no usage envelope.
     */
    public ModelUsagePayload toUsage(ConverseResponse response) {
        Objects.requireNonNull(response, "response");
        var usage = response.usage();
        if (usage == null) {
            throw new IllegalArgumentException("Converse response carries no usage envelope");
        }
        return new ModelUsagePayload(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadInputTokens(),
                usage.cacheWriteInputTokens());
    }

    /**
     * Assembles the parsed content and stop reason of a response into the
     * {@link ModelResponsePayload} the event log records for a {@code MODEL_RESPONSE}
     * event.
     *
     * @param response the Converse response; must not be {@code null}.
     * @return the model-response payload (stop reason + content blocks).
     * @throws NullPointerException     if {@code response} is {@code null}.
     * @throws IllegalArgumentException if the response lacks an output message or a
     *                                  recognised stop reason.
     */
    public ModelResponsePayload toModelResponse(ConverseResponse response) {
        Objects.requireNonNull(response, "response");
        return new ModelResponsePayload(toStopReason(response), toContentBlocks(response));
    }

    private void requireToolResultsPaired(List<ConverseMessage> messages) {
        // INV-6: a toolResult's toolUseId must match a toolUse produced earlier in the
        // transcript. Scan in order, collecting toolUseIds as toolUse blocks appear, so a
        // toolResult is validated against only the toolUse blocks that precede it
        // (CT-INV-5: a toolResult with no prior toolUse is rejected).
        Set<String> seenToolUseIds = new HashSet<>();
        for (ConverseMessage message : messages) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ContentBlock.ToolUse toolUse) {
                    seenToolUseIds.add(toolUse.toolUseId());
                } else if (block instanceof ContentBlock.ToolResult toolResult
                        && !seenToolUseIds.contains(toolResult.toolUseId())) {
                    throw new ToolProtocolException(
                            "toolResult references toolUseId '" + toolResult.toolUseId()
                                    + "' with no prior toolUse in the transcript (INV-6)");
                }
            }
        }
    }

    private Message toWireMessage(ConverseMessage message) {
        List<software.amazon.awssdk.services.bedrockruntime.model.ContentBlock> wireBlocks =
                new ArrayList<>(message.content().size());
        for (ContentBlock block : message.content()) {
            wireBlocks.add(toWireBlock(block));
        }
        return Message.builder()
                .role(toWireRole(message.role()))
                .content(wireBlocks)
                .build();
    }

    private static ConversationRole toWireRole(Role role) {
        return switch (role) {
            case USER -> ConversationRole.USER;
            case ASSISTANT -> ConversationRole.ASSISTANT;
        };
    }

    private software.amazon.awssdk.services.bedrockruntime.model.ContentBlock toWireBlock(
            ContentBlock block) {
        return switch (block) {
            case ContentBlock.Text text ->
                    software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
                            .fromText(text.text());
            case ContentBlock.ToolUse toolUse ->
                    software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolUse(
                            ToolUseBlock.builder()
                                    .toolUseId(toolUse.toolUseId())
                                    .name(toolUse.name())
                                    .input(DocumentConverter.toDocument(toolUse.input()))
                                    .build());
            case ContentBlock.ToolResult toolResult ->
                    software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolResult(
                            toWireToolResult(toolResult));
        };
    }

    private ToolResultBlock toWireToolResult(ContentBlock.ToolResult toolResult) {
        ToolResultBlock.Builder builder = ToolResultBlock.builder()
                .toolUseId(toolResult.toolUseId())
                .status(toWireToolResultStatus(toolResult.status()));
        if (toolResult.content() != null) {
            builder.content(ToolResultContentBlock.fromJson(
                    DocumentConverter.toDocument(toolResult.content())));
        }
        return builder.build();
    }

    private static ToolResultStatus toWireToolResultStatus(String status) {
        // The domain status enum is {ok, error} (ContentBlock.ToolResult javadoc); the
        // Converse wire enum is {success, error}. Map ok -> success, error -> error.
        return switch (status) {
            case "ok" -> ToolResultStatus.SUCCESS;
            case "error" -> ToolResultStatus.ERROR;
            default -> throw new IllegalArgumentException(
                    "unsupported toolResult status: '" + status + "' (expected 'ok' or 'error')");
        };
    }

    private List<SystemContentBlock> toSystemBlocks(List<String> system) {
        List<SystemContentBlock> blocks = new ArrayList<>(system.size());
        for (String text : system) {
            blocks.add(SystemContentBlock.fromText(text));
        }
        return blocks;
    }

    private ContentBlock toDomainResponseBlock(
            software.amazon.awssdk.services.bedrockruntime.model.ContentBlock wireBlock) {
        if (wireBlock.text() != null) {
            return ContentBlock.text(wireBlock.text());
        }
        if (wireBlock.toolUse() != null) {
            ToolUseBlock toolUse = wireBlock.toolUse();
            Object input = DocumentConverter.toValue(toolUse.input());
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> inputMap = input instanceof java.util.Map<?, ?>
                    ? (java.util.Map<String, Object>) input
                    : java.util.Map.of();
            return ContentBlock.toolUse(toolUse.toolUseId(), toolUse.name(), inputMap);
        }
        throw new IllegalArgumentException(
                "unsupported response content block type: " + wireBlock.type()
                        + " (only text and toolUse are mapped from a model turn)");
    }
}
