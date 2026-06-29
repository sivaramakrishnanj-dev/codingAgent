package com.srk.codingagent.model.converse;

import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.ModelResponsePayload;
import com.srk.codingagent.persistence.ModelUsagePayload;
import com.srk.codingagent.persistence.StopReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.srk.codingagent.model.PromptCacheCaps;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointBlock;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointType;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.DocumentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.DocumentSource;
import software.amazon.awssdk.services.bedrockruntime.model.ImageBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ImageSource;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;
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
 *   <li>{@link ContentBlock.Reasoning} &rarr; {@code reasoningContent{reasoningText{text,signature}}}
 *       or {@code reasoningContent{redactedContent}}</li>
 *   <li>{@link ContentBlock.Image} &rarr; {@code image{format, source{bytes}}} (input only,
 *       § 2.3; the SDK base64-encodes the bytes the mapper reads from the block's
 *       {@code bytesRef})</li>
 *   <li>{@link ContentBlock.Document} &rarr; {@code document{name, format, source{bytes}}}
 *       (input only, § 2.3; neutral {@code name} per INV-18)</li>
 * </ul>
 *
 * <p><b>Multimodal attachments (T-4.2; § 2.3 multimodal input).</b> An {@link ContentBlock.Image}
 * / {@link ContentBlock.Document} carries its bytes <em>by reference</em> ({@code bytesRef}, a
 * path) rather than inline, so a persisted/replayed block does not bloat the JSONL log with
 * base64. On the request path the mapper reads the referenced file to raw bytes and wraps them in
 * the Converse {@code image.source.bytes} / {@code document.source.bytes} member; the SDK
 * base64-encodes at send (§ 2.3 "sourced as raw bytes (the SDK base64-encodes)"). These are
 * INPUT-ONLY (us &rarr; request) — a model turn never emits them, so there is no response-direction
 * mapping. A {@code bytesRef} that cannot be read is surfaced as an {@link IllegalArgumentException}
 * naming the reference rather than sending an empty/partial attachment. The capability gate
 * (INV-19) lives upstream in the attachment pipeline (C1) — by the time a block reaches this
 * boundary it has already been admitted; the mapper is the wire translation, not the gate.
 *
 * <p><b>INV-7 reasoning-signature replay.</b> A {@link ContentBlock.Reasoning} block
 * carries a {@code signature} (a tamper-check hash over the conversation). On the request
 * path the signature — and the reasoning text it accompanies — is carried into the wire
 * {@code reasoningContent.reasoningText} <em>verbatim</em>; on the response path it is read
 * back verbatim. This is load-bearing: a derived conversation that replays a prior
 * reasoning block with a mutated or dropped signature makes the first live Converse call
 * error (INV-7, § 6.A.1). The mapper never normalizes the signature.
 *
 * <p><b>toolResult content member (text vs json).</b> The {@code content} of a
 * {@link ContentBlock.ToolResult} is "text, or a structured object" per
 * {@code 06-formal/content-block.schema.json} (and § 6.A.1: a toolResult content block
 * supports {@code text} and {@code json} members). Converse requires the {@code json}
 * member to be a JSON <em>object</em>; a plain-text result must use the {@code text}
 * member instead. {@link #toWireToolResult} therefore branches on the runtime shape of
 * the content: a {@link java.util.Map} (a structured object, e.g. a CommandResult-shaped
 * map) maps to {@code json}; any other non-null content maps to {@code text}. Mapping a
 * String into {@code json} is what produced the real-Bedrock
 * {@code ValidationException: "The format of the value at ...toolResult.content.0.json is
 * invalid. Provide a json object for the field"} (D2) — the motivating evidence for this
 * branch.
 *
 * <p>Response direction (response &rarr; us): parse {@code output.message.content[]}
 * into domain {@link ContentBlock}s ({@code text}, {@code toolUse}, and
 * {@code reasoningContent} — the kinds a model turn emits), map the wire
 * {@code stopReason} to our {@link StopReason}, and map the {@code usage} envelope to a
 * {@link ModelUsagePayload}. {@link #toModelResponse} assembles the content + stop reason
 * into the {@link ModelResponsePayload} the event log records.
 *
 * <p><b>INV-6 / CT-INV-5 (toolUse&harr;toolResult pairing).</b> {@link #toRequest}
 * enforces the protocol-correctness property ADR-0001 says the agent owns: every
 * {@code toolResult} block in the transcript must carry a {@code toolUseId} produced by
 * an earlier {@code toolUse} block in the same transcript. A {@code toolResult} with no
 * prior {@code toolUse} is rejected with a {@link ToolProtocolException} before any
 * request leaves the boundary (CT-INV-5, the negative test). The text, toolUse, toolResult,
 * reasoning, image, and document kinds are mapped on the request path; image/document are
 * input-only (T-4.2 — a model turn never emits them, so they have no response-direction
 * mapping).
 *
 * <p><b>Prompt-cache placement (T-4.4 — ADR-0006, OQ-I; § 6.A.1).</b> The mapper optionally
 * places a single {@code cachePoint} breakpoint after the STABLE PREFIX — tools &rarr; system
 * &rarr; memory-index — which are static across a session (cache order is tools&rarr;system&rarr;
 * messages). Because the cached region is everything <em>before</em> the breakpoint, appending one
 * {@link CachePointBlock} (kind {@code default}) to the end of the {@code system} content array
 * marks tools + system (the memory-index forms the tail of the system content, § 7) as the cached
 * prefix, while the variable {@code messages} tail stays uncached — the largest-static-region
 * placement ADR-0006 specifies. ADR-0006's "simplified single-breakpoint" conservatism means ONE
 * breakpoint, never multiple checkpoints, and no explicit TTL micro-management (the model's
 * default window applies).
 *
 * <p><b>Capability-gated (ADR-0006).</b> A breakpoint is placed ONLY when the resolved model's
 * {@link PromptCacheCaps} (ADR-0002 / C5) reports prompt-cache support AND the stable-prefix
 * estimate meets the model's per-checkpoint token minimum
 * ({@link PromptCacheCaps#minTokensPerCheckpoint()}; Opus 4.5/4.6 &ge; 4096). The mapper carries
 * the caps by construction ({@link #ConverseWireMapper(Integer, PromptCacheCaps)}); a {@code null}
 * caps (prompt-cache unsupported, § 2.6 "null = unsupported") OR a sub-minimum prefix &rArr; NO
 * {@code cachePoint} — the request still builds and is valid, the loop is unaffected (graceful
 * degradation). Bedrock returns exact {@code usage} token counts only after a call, so at
 * request-build time the prefix size is a documented char-based estimate (&asymp; chars/4 across
 * the system blocks); see the T-4.4 handoff {@code stated_assumptions}.
 *
 * <p><b>Greenfield output-token budget (DCR-2 — D1 follow-on, ADR-0012; {@code 02-architecture.md}
 * § 2.1).</b> The mapper optionally carries an output-token cap. When constructed with one
 * ({@link #ConverseWireMapper(Integer)}), every {@link ConverseRequest} it builds sets
 * {@code inferenceConfig.maxTokens} to that value, bounding the model's output so a large
 * generation is not truncated at the Bedrock backend's default 4096 output-token cap (a live
 * greenfield phase produced a full design/tasks deliverable and stopped at {@code MAX_TOKENS}).
 * The greenfield phase path constructs the mapper with the configured budget
 * ({@code 16384} tokens, {@link #GREENFIELD_MAX_OUTPUT_TOKENS}); the default no-arg constructor
 * carries no cap (the backend default applies, the prior behaviour, unchanged for the
 * brownfield/one-shot path). This is the <em>model's output cap</em>, distinct from
 * {@code NFR-OUTPUT-MAX-INLINE} (tool-output disposal, a different axis).
 */
public final class ConverseWireMapper {

    /**
     * The greenfield phase Converse request's output-token budget (DCR-2, ADR-0012
     * "Greenfield-phase output-token budget"; {@code 02-architecture.md} § 2.1): {@code 16384}
     * tokens (16K). Ample headroom for a full requirements/design/tasks markdown deliverable while
     * staying well within the Claude Opus 4.x output ceiling, and small enough not to invite
     * runaway generation. Set as {@code inferenceConfig.maxTokens} on the greenfield path so a
     * large deliverable is not truncated at the backend's default 4096 cap.
     */
    public static final int GREENFIELD_MAX_OUTPUT_TOKENS = 16384;

    /**
     * The divisor of the char-based stable-prefix token estimate (T-4.4 — ADR-0006 gate): roughly
     * one token per four characters of prefix text. Bedrock returns exact {@code usage} token
     * counts only <em>after</em> a call (§ 6.A.1), so at request-build time the prefix size cannot
     * be measured exactly; this conservative heuristic (the common ~4-chars-per-token rule of
     * thumb) lets the capability gate honour ADR-0006's "the prefix meets the model's token
     * minimum" condition without an embedded tokenizer (out of scope for v1; see the T-4.4 handoff
     * {@code stated_assumptions}). It under-estimates rather than over-estimates token count, so it
     * errs toward NOT placing a cachePoint on a borderline prefix — the conservative direction.
     */
    private static final int ESTIMATED_CHARS_PER_TOKEN = 4;

    private final Integer maxOutputTokens;
    private final PromptCacheCaps promptCacheCaps;

    /**
     * Creates a mapper that sets no {@code inferenceConfig.maxTokens} on the requests it builds (so
     * the Bedrock backend applies its default output-token cap — the brownfield/one-shot path) and
     * places no prompt-cache breakpoint (prompt caching off — the prior behaviour).
     */
    public ConverseWireMapper() {
        this(null, null);
    }

    /**
     * Creates a mapper with an output-token cap but no prompt-cache breakpoint (DCR-2 — D1; the
     * greenfield path uses {@link #GREENFIELD_MAX_OUTPUT_TOKENS}). Kept for callers that thread only
     * the output budget; equivalent to {@link #ConverseWireMapper(Integer, PromptCacheCaps)} with a
     * {@code null} {@code promptCacheCaps}.
     *
     * @param maxOutputTokens the output-token cap to set on the request's {@code inferenceConfig},
     *                        or {@code null} to set none (backend default applies). When non-null it
     *                        must be positive.
     * @throws IllegalArgumentException if {@code maxOutputTokens} is non-null and not positive.
     */
    public ConverseWireMapper(Integer maxOutputTokens) {
        this(maxOutputTokens, null);
    }

    /**
     * Creates a mapper carrying both the optional output-token cap (DCR-2 — D1) and the optional
     * prompt-cache capabilities (T-4.4 — ADR-0006). When {@code promptCacheCaps} is non-null
     * (the resolved model reports prompt-cache support, ADR-0002 / C5) the mapper places a single
     * {@code cachePoint} after the stable prefix (tools &rarr; system &rarr; memory-index) on every
     * request whose stable-prefix estimate meets {@code promptCacheCaps.minTokensPerCheckpoint()};
     * a {@code null} {@code promptCacheCaps} (prompt caching unsupported) places no breakpoint
     * (graceful degradation — ADR-0006).
     *
     * @param maxOutputTokens  the output-token cap to set on the request's {@code inferenceConfig},
     *                         or {@code null} to set none (backend default applies). When non-null
     *                         it must be positive.
     * @param promptCacheCaps  the resolved model's prompt-cache capabilities (ADR-0002 / C5), or
     *                         {@code null} when prompt caching is unsupported (§ 2.6
     *                         "null = unsupported"). When non-null it gates and sizes the single
     *                         stable-prefix {@code cachePoint} (ADR-0006).
     * @throws IllegalArgumentException if {@code maxOutputTokens} is non-null and not positive.
     */
    public ConverseWireMapper(Integer maxOutputTokens, PromptCacheCaps promptCacheCaps) {
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxOutputTokens must be positive when set, was: " + maxOutputTokens);
        }
        this.maxOutputTokens = maxOutputTokens;
        this.promptCacheCaps = promptCacheCaps;
    }

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
     * @return the assembled {@link ConverseRequest}, carrying
     *         {@code inferenceConfig.maxTokens} when this mapper was constructed with an
     *         output-token cap (DCR-2 — the greenfield path), otherwise no {@code inferenceConfig}
     *         (the backend default output cap applies).
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
        if (maxOutputTokens != null) {
            // DCR-2 (D1; 04-apis § 3 documents inferenceConfig as part of the request shape):
            // bound the model's output so a large greenfield deliverable is not truncated at the
            // backend's default 4096 cap. Unset (null) on the brownfield/one-shot path, so the
            // backend default applies there as before.
            builder.inferenceConfig(InferenceConfiguration.builder()
                    .maxTokens(maxOutputTokens)
                    .build());
        }
        return builder.build();
    }

    /**
     * Parses a {@link ConverseResponse} into the domain content blocks of the model
     * turn: {@code output.message.content[]} mapped to {@link ContentBlock.Text},
     * {@link ContentBlock.ToolUse}, and {@link ContentBlock.Reasoning} (the kinds a model
     * turn emits, § 6.A.1).
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
            case ContentBlock.Reasoning reasoning ->
                    software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
                            .fromReasoningContent(toWireReasoning(reasoning));
            case ContentBlock.Image image ->
                    software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
                            .fromImage(toWireImage(image));
            case ContentBlock.Document document ->
                    software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
                            .fromDocument(toWireDocument(document));
        };
    }

    /**
     * Maps a domain {@link ContentBlock.Image} to a wire {@link ImageBlock} (§ 2.3 /
     * § 7 — {@code image{format, source{bytes}}}). The {@code format} is carried verbatim (the
     * domain block already constrained it to the Converse {@code png|jpeg|gif|webp} set), and the
     * raw bytes read from the block's {@code bytesRef} are wrapped in {@code source.bytes}; the
     * SDK base64-encodes them at send.
     */
    private ImageBlock toWireImage(ContentBlock.Image image) {
        return ImageBlock.builder()
                .format(image.format())
                .source(ImageSource.fromBytes(readBytes(image.bytesRef())))
                .build();
    }

    /**
     * Maps a domain {@link ContentBlock.Document} to a wire {@link DocumentBlock} (§ 2.3 /
     * § 7 — {@code document{name, format, source{bytes}}}). The neutral {@code name} (INV-18,
     * already sanitized by the domain block) and the {@code format} are carried verbatim, and the
     * raw bytes read from {@code bytesRef} are wrapped in {@code source.bytes} for the SDK to
     * base64-encode at send.
     */
    private DocumentBlock toWireDocument(ContentBlock.Document document) {
        return DocumentBlock.builder()
                .name(document.name())
                .format(document.format())
                .source(DocumentSource.fromBytes(readBytes(document.bytesRef())))
                .build();
    }

    /**
     * Reads the raw bytes a multimodal attachment references at send time (§ 2.3 — "sourced as
     * raw bytes (the SDK base64-encodes)"). Surfaces an unreadable reference as an
     * {@link IllegalArgumentException} naming it, so a missing/unreadable attachment fails the
     * request build rather than sending an empty or partial block.
     */
    private static SdkBytes readBytes(String bytesRef) {
        try {
            return SdkBytes.fromByteArray(Files.readAllBytes(Path.of(bytesRef)));
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "could not read attachment bytes from bytesRef '" + bytesRef + "'", e);
        }
    }

    /**
     * Maps a domain {@link ContentBlock.Reasoning} to a wire {@link ReasoningContentBlock},
     * replaying the {@code signature} verbatim (INV-7). When the block carries reasoning text
     * it maps to {@code reasoningText{text,signature}} (the signature is carried unchanged so a
     * live Converse call sees the exact tamper-check hash the model issued); when it carries
     * only base64 {@code redactedContent} it maps to the {@code redactedContent} member. A
     * block with neither maps to an empty {@code reasoningText} so the structure survives.
     */
    private ReasoningContentBlock toWireReasoning(ContentBlock.Reasoning reasoning) {
        if (reasoning.text() != null || reasoning.signature() != null) {
            return ReasoningContentBlock.fromReasoningText(ReasoningTextBlock.builder()
                    .text(reasoning.text())
                    .signature(reasoning.signature())
                    .build());
        }
        if (reasoning.redactedContent() != null) {
            return ReasoningContentBlock.fromRedactedContent(
                    SdkBytes.fromUtf8String(reasoning.redactedContent()));
        }
        return ReasoningContentBlock.fromReasoningText(ReasoningTextBlock.builder().build());
    }

    private ToolResultBlock toWireToolResult(ContentBlock.ToolResult toolResult) {
        ToolResultBlock.Builder builder = ToolResultBlock.builder()
                .toolUseId(toolResult.toolUseId())
                .status(toWireToolResultStatus(toolResult.status()));
        Object content = toolResult.content();
        if (content != null) {
            // Converse's toolResult json member must be a JSON object; only a structured
            // object (a Map) may use it. A plain-text result (e.g. the String contents of a
            // file from read_file) must use the text member instead — routing a String into
            // json produced the real-Bedrock ValidationException: "The format of the value at
            // ...toolResult.content.0.json is invalid. Provide a json object for the field"
            // (D2). Any other non-null, non-object content (number/boolean/list) is likewise
            // not a JSON object, so it is rendered to the text member rather than json.
            ToolResultContentBlock contentBlock = content instanceof java.util.Map<?, ?>
                    ? ToolResultContentBlock.fromJson(DocumentConverter.toDocument(content))
                    : ToolResultContentBlock.fromText(String.valueOf(content));
            builder.content(contentBlock);
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
        List<SystemContentBlock> blocks = new ArrayList<>(system.size() + 1);
        for (String text : system) {
            blocks.add(SystemContentBlock.fromText(text));
        }
        if (shouldPlaceCachePoint(system)) {
            // T-4.4 (ADR-0006, OQ-I): append the single cachePoint AFTER the stable prefix.
            // Cache order is tools->system->messages (§ 6.A.1), so a breakpoint at the END of the
            // system content array marks tools + system (the memory-index is the tail of the system
            // content, § 7) as the cached prefix, leaving the variable messages tail uncached. The
            // model's "simplified single-breakpoint" management means exactly ONE cachePoint
            // (default kind, no explicit TTL).
            blocks.add(SystemContentBlock.fromCachePoint(
                    CachePointBlock.builder().type(CachePointType.DEFAULT).build()));
        }
        return blocks;
    }

    /**
     * The ADR-0006 prompt-cache gate: place a {@code cachePoint} after the stable prefix ONLY when
     * the resolved model reports prompt-cache support ({@code promptCacheCaps != null}, ADR-0002 /
     * § 2.6 "null = unsupported") AND the stable-prefix estimate meets the model's per-checkpoint
     * token minimum ({@link PromptCacheCaps#minTokensPerCheckpoint()}). Absent support OR a
     * sub-minimum prefix &rArr; no breakpoint (graceful degradation; the loop is unaffected).
     */
    private boolean shouldPlaceCachePoint(List<String> system) {
        if (promptCacheCaps == null) {
            return false;
        }
        return estimatePrefixTokens(system) >= promptCacheCaps.minTokensPerCheckpoint();
    }

    /**
     * A documented char-based estimate of the stable-prefix token count (T-4.4 — ADR-0006 gate):
     * the total character length of the system blocks divided by {@link #ESTIMATED_CHARS_PER_TOKEN}.
     * Bedrock returns exact {@code usage} only after a call (§ 6.A.1), so the build-time gate uses
     * this conservative heuristic rather than an embedded tokenizer (out of scope for v1).
     */
    private static int estimatePrefixTokens(List<String> system) {
        long chars = 0;
        for (String block : system) {
            chars += block.length();
        }
        return (int) (chars / ESTIMATED_CHARS_PER_TOKEN);
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
        if (wireBlock.reasoningContent() != null) {
            return toDomainReasoning(wireBlock.reasoningContent());
        }
        throw new IllegalArgumentException(
                "unsupported response content block type: " + wireBlock.type()
                        + " (only text, toolUse and reasoningContent are mapped from a model turn)");
    }

    /**
     * Maps a wire {@link ReasoningContentBlock} back into a domain
     * {@link ContentBlock.Reasoning}, reading the {@code signature} (and reasoning text)
     * verbatim so it can later be replayed unchanged (INV-7). A redacted-only block carries
     * its base64 {@code redactedContent} through unchanged.
     */
    private ContentBlock toDomainReasoning(ReasoningContentBlock reasoningContent) {
        ReasoningTextBlock reasoningText = reasoningContent.reasoningText();
        String text = reasoningText == null ? null : reasoningText.text();
        String signature = reasoningText == null ? null : reasoningText.signature();
        SdkBytes redacted = reasoningContent.redactedContent();
        String redactedContent = redacted == null ? null : redacted.asUtf8String();
        return ContentBlock.reasoning(text, signature, redactedContent);
    }
}
