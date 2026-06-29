package com.srk.codingagent.model.converse;

import com.srk.codingagent.model.PromptCacheCaps;
import com.srk.codingagent.persistence.ModelResponsePayload;
import com.srk.codingagent.persistence.ModelUsagePayload;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;

/**
 * The Model Client (component C4): one adapter over Bedrock Converse that builds a
 * request from our domain types, makes the (non-streaming) Converse call, and parses
 * the response back into our domain types (ADR-0001, 03-data-model.md § 7, § 6.A.1).
 *
 * <p>It owns no business logic and is provider-agnostic on its surface: callers pass a
 * model id, a transcript of {@link ConverseMessage}s, an optional system prompt, and an
 * optional {@link ToolConfiguration}, and receive a {@link Turn} of domain types. The
 * agent loop (T-0.8), the tool registry/{@code toolConfig} rendering (T-0.6), the
 * permission gate (T-0.7), and capability profiles (T-4.3) are deliberately out of
 * scope — this class is the wire seam those consume.
 *
 * <p>The {@link BedrockRuntimeClient} and {@link ConverseWireMapper} are injected so the
 * request-build and response-parse mapping are fully unit-testable against a stubbed
 * client (no network, no live Converse call), and the only un-unit-testable code is the
 * single {@code converse(...)} invocation in {@link #converse}. Construct the client via
 * {@link com.srk.codingagent.model.credentials.BedrockClientFactory}; this adapter does
 * not construct it (read/invoke only — no AWS write verbs, ADR-0011).
 */
public final class ModelClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelClient.class);

    private final BedrockRuntimeClient bedrock;
    private final ConverseWireMapper mapper;

    /**
     * Creates a Model Client over the given Bedrock client, wired with the production
     * {@link ConverseWireMapper} and no prompt-cache breakpoint (prompt caching off). Kept for
     * callers that do not thread a capability profile; equivalent to
     * {@link #ModelClient(BedrockRuntimeClient, PromptCacheCaps)} with a {@code null}
     * {@code promptCacheCaps}.
     *
     * @param bedrock the configured Bedrock runtime client (from
     *                {@link com.srk.codingagent.model.credentials.BedrockClientFactory});
     *                must not be {@code null}.
     * @throws NullPointerException if {@code bedrock} is {@code null}.
     */
    public ModelClient(BedrockRuntimeClient bedrock) {
        this(bedrock, (PromptCacheCaps) null);
    }

    /**
     * Creates a Model Client over the given Bedrock client whose wire mapper places the single
     * stable-prefix {@code cachePoint} (T-4.4 — ADR-0006, OQ-I) when {@code promptCacheCaps} is
     * non-null (the resolved model reports prompt-cache support, ADR-0002 / C5). A {@code null}
     * {@code promptCacheCaps} (prompt caching unsupported, § 2.6 "null = unsupported") places no
     * breakpoint (graceful degradation). This is the brownfield/one-shot path (no output-token
     * cap; backend default applies).
     *
     * @param bedrock         the configured Bedrock runtime client; must not be {@code null}.
     * @param promptCacheCaps the resolved model's prompt-cache capabilities, or {@code null} when
     *                        prompt caching is unsupported.
     * @throws NullPointerException if {@code bedrock} is {@code null}.
     */
    public ModelClient(BedrockRuntimeClient bedrock, PromptCacheCaps promptCacheCaps) {
        this(bedrock, new ConverseWireMapper(null, promptCacheCaps));
    }

    /**
     * Creates a Model Client for the greenfield phase path with no prompt-cache breakpoint;
     * equivalent to {@link #forGreenfield(BedrockRuntimeClient, PromptCacheCaps)} with a
     * {@code null} {@code promptCacheCaps}. Kept for callers that thread only the output budget.
     *
     * @param bedrock the configured Bedrock runtime client; must not be {@code null}.
     * @return a Model Client whose requests carry the greenfield output-token budget; never
     *         {@code null}.
     * @throws NullPointerException if {@code bedrock} is {@code null}.
     */
    public static ModelClient forGreenfield(BedrockRuntimeClient bedrock) {
        return forGreenfield(bedrock, null);
    }

    /**
     * Creates a Model Client for the greenfield phase path (DCR-2 — D1, ADR-0012;
     * {@code 02-architecture.md} § 2.1): its wire mapper sets {@code inferenceConfig.maxTokens} to
     * {@link ConverseWireMapper#GREENFIELD_MAX_OUTPUT_TOKENS} (16K) on every request, so a full
     * requirements/design/tasks deliverable is not truncated at the backend's default 4096
     * output-token cap, AND places the single stable-prefix {@code cachePoint} (T-4.4 — ADR-0006)
     * when {@code promptCacheCaps} is non-null. Distinct from
     * {@link #ModelClient(BedrockRuntimeClient, PromptCacheCaps)} (no output cap — the
     * brownfield/one-shot path, backend default applies).
     *
     * @param bedrock         the configured Bedrock runtime client; must not be {@code null}.
     * @param promptCacheCaps the resolved model's prompt-cache capabilities, or {@code null} when
     *                        prompt caching is unsupported.
     * @return a Model Client whose requests carry the greenfield output-token budget (and the
     *         stable-prefix cachePoint when prompt caching is supported); never {@code null}.
     * @throws NullPointerException if {@code bedrock} is {@code null}.
     */
    public static ModelClient forGreenfield(BedrockRuntimeClient bedrock,
            PromptCacheCaps promptCacheCaps) {
        return new ModelClient(bedrock,
                new ConverseWireMapper(
                        ConverseWireMapper.GREENFIELD_MAX_OUTPUT_TOKENS, promptCacheCaps));
    }

    /**
     * Creates a Model Client with an injected mapper. Package-private: used by tests to
     * exercise the adapter against a stubbed client and the real mapper.
     *
     * @param bedrock the Bedrock runtime client; must not be {@code null}.
     * @param mapper  the wire-format mapper; must not be {@code null}.
     * @throws NullPointerException if either argument is {@code null}.
     */
    ModelClient(BedrockRuntimeClient bedrock, ConverseWireMapper mapper) {
        this.bedrock = Objects.requireNonNull(bedrock, "bedrock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Sends one Converse turn: builds the request from the domain transcript, calls
     * Bedrock, and parses the response into domain types.
     *
     * @param modelId    the Bedrock model id; must not be {@code null} or blank.
     * @param messages   the full conversation transcript (Converse is stateless —
     *                   resend all messages each call, § 6.A.1); must not be {@code null}
     *                   or empty.
     * @param system     the system prompt blocks, or {@code null}/empty for none.
     * @param toolConfig the tool configuration, or {@code null} when no tools are offered.
     * @return the model turn parsed into domain types (stop reason, content, usage).
     * @throws IllegalArgumentException if {@code modelId} is blank or {@code messages}
     *                                  is empty.
     * @throws ToolProtocolException    if the transcript violates the
     *                                  toolUse&harr;toolResult pairing invariant
     *                                  (INV-6, CT-INV-5).
     * @throws ModelBackendException    if the Converse call fails at the backend
     *                                  (mapped to exit {@code 4}).
     */
    public Turn converse(
            String modelId,
            List<ConverseMessage> messages,
            List<String> system,
            ToolConfiguration toolConfig) {
        ConverseRequest request = mapper.toRequest(modelId, messages, system, toolConfig);
        LOGGER.info("Calling Bedrock Converse: modelId={}, messages={}", modelId, messages.size());
        ConverseResponse response = invoke(request);
        ModelResponsePayload modelResponse = mapper.toModelResponse(response);
        ModelUsagePayload usage = mapper.toUsage(response);
        LOGGER.info("Converse returned stopReason={}, inputTokens={}, outputTokens={}",
                modelResponse.stopReason(), usage.inputTokens(), usage.outputTokens());
        return new Turn(modelResponse, usage);
    }

    private ConverseResponse invoke(ConverseRequest request) {
        try {
            return bedrock.converse(request);
        } catch (SdkException backendFailure) {
            // ADR-0001 / exit 4: an SDK service or client failure is an unrecoverable
            // model-backend problem. Wrap (chaining the cause so the request id and
            // service detail survive) and log once on the failure path.
            LOGGER.error("Bedrock Converse call failed for modelId={}", request.modelId(), backendFailure);
            throw new ModelBackendException("Bedrock Converse call failed", backendFailure);
        }
    }

    /**
     * One parsed model turn: the {@link ModelResponsePayload} (stop reason + content
     * blocks) and the {@link ModelUsagePayload} (token accounting) the event log
     * records as the {@code MODEL_RESPONSE} and {@code MODEL_USAGE} events (§ 7).
     *
     * @param response the model response (stop reason + content); never {@code null}.
     * @param usage    the token usage for the turn; never {@code null}.
     */
    public record Turn(ModelResponsePayload response, ModelUsagePayload usage) {

        /**
         * Validates the turn.
         *
         * @throws NullPointerException if {@code response} or {@code usage} is
         *                              {@code null}.
         */
        public Turn {
            Objects.requireNonNull(response, "response");
            Objects.requireNonNull(usage, "usage");
        }
    }
}
