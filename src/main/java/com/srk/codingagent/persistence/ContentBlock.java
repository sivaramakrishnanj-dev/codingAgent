package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;
import java.util.Objects;

/**
 * One block of conversation content carried by a {@link UserMessagePayload} or a
 * {@link ModelResponsePayload} (and, as a tool result, by a
 * {@link ToolResultPayload}'s embedded content). Mirrors the {@code ContentBlock}
 * schema ({@code 06-formal/content-block.schema.json}): a tagged union
 * discriminated by the {@code kind} field.
 *
 * <p><b>Scope (T-0.4).</b> Only the three block kinds present in the contract
 * fixture {@code session-tool-use-cycle.jsonl} are modelled here: {@link Text},
 * {@link ToolUse}, and {@link ToolResult}. The remaining schema variants
 * (reasoning, image, document, cachePoint) are deliberately not modelled — the full
 * Bedrock Converse round-trip mapping is T-0.5's lane. A later task extends this
 * union; the {@code kind} discriminator and sealed hierarchy make that extension
 * additive. See {@code stated_assumptions} in the task handoff.
 *
 * <p>The {@code kind} discriminator is a real field of each block so it both
 * serializes (matching the schema's {@code kind} const) and round-trips on read
 * without reflective getter inference. Each variant serializes to exactly the fields
 * its schema branch allows ({@code additionalProperties: false}); {@code null}
 * optional fields are omitted so the emitted JSON matches the schema's optional-field
 * semantics. Use the {@link #text}, {@link #toolUse}, {@link #toolResult} factories,
 * which set the correct discriminator.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContentBlock.Text.class, name = ContentBlock.KIND_TEXT),
    @JsonSubTypes.Type(value = ContentBlock.ToolUse.class, name = ContentBlock.KIND_TOOL_USE),
    @JsonSubTypes.Type(value = ContentBlock.ToolResult.class, name = ContentBlock.KIND_TOOL_RESULT)
})
public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.ToolUse, ContentBlock.ToolResult {

    /** The wire tag for a {@link Text} block. */
    String KIND_TEXT = "text";

    /** The wire tag for a {@link ToolUse} block. */
    String KIND_TOOL_USE = "toolUse";

    /** The wire tag for a {@link ToolResult} block. */
    String KIND_TOOL_RESULT = "toolResult";

    /**
     * The wire discriminator for this block ({@code text}, {@code toolUse}, or
     * {@code toolResult}). Matches the schema's {@code kind} const for the variant.
     *
     * @return the block kind tag; never {@code null}.
     */
    String kind();

    /**
     * Creates a text block.
     *
     * @param text the text content; must not be {@code null}.
     * @return a {@link Text} block.
     */
    static Text text(String text) {
        return new Text(KIND_TEXT, text);
    }

    /**
     * Creates a tool-use block.
     *
     * @param toolUseId the correlating tool-use id; non-blank.
     * @param name      the tool name; non-blank.
     * @param input     the tool input object; must not be {@code null}.
     * @return a {@link ToolUse} block.
     */
    static ToolUse toolUse(String toolUseId, String name, Map<String, Object> input) {
        return new ToolUse(KIND_TOOL_USE, toolUseId, name, input);
    }

    /**
     * Creates a tool-result block.
     *
     * @param toolUseId the correlating tool-use id; non-blank.
     * @param status    {@code ok} or {@code error}.
     * @param content   the result content, or {@code null}.
     * @return a {@link ToolResult} block.
     */
    static ToolResult toolResult(String toolUseId, String status, Object content) {
        return new ToolResult(KIND_TOOL_RESULT, toolUseId, status, content);
    }

    /**
     * A plain text block (the {@code TextBlock} schema variant).
     *
     * @param kind the discriminator; must equal {@link #KIND_TEXT}.
     * @param text the text content; must not be {@code null}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Text(String kind, String text) implements ContentBlock {

        /**
         * Validates the block and its discriminator.
         *
         * @throws NullPointerException     if {@code text} is {@code null}.
         * @throws IllegalArgumentException if {@code kind} is not {@link #KIND_TEXT}.
         */
        public Text {
            requireKind(kind, KIND_TEXT);
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A tool-invocation request block (the {@code ToolUseBlock} schema variant).
     *
     * @param kind      the discriminator; must equal {@link #KIND_TOOL_USE}.
     * @param toolUseId the correlating tool-use id; non-blank (schema
     *                  {@code minLength 1}).
     * @param name      the tool name; non-blank (schema {@code minLength 1}).
     * @param input     the tool input object; must not be {@code null} (schema
     *                  {@code type object}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolUse(String kind, String toolUseId, String name, Map<String, Object> input)
            implements ContentBlock {

        /**
         * Validates the block and defensively copies {@code input}.
         *
         * @throws NullPointerException     if a non-discriminator field is
         *                                  {@code null}.
         * @throws IllegalArgumentException if {@code kind} is not
         *                                  {@link #KIND_TOOL_USE}, or
         *                                  {@code toolUseId}/{@code name} is blank.
         */
        public ToolUse {
            requireKind(kind, KIND_TOOL_USE);
            Payloads.requireNonBlank(toolUseId, "toolUseId");
            Payloads.requireNonBlank(name, "name");
            input = Map.copyOf(Objects.requireNonNull(input, "input"));
        }
    }

    /**
     * A tool-result block (the {@code ToolResultBlock} schema variant), as it may
     * appear inside conversation content. Distinct from the top-level
     * {@link ToolResultPayload} carried by a {@code TOOL_RESULT} event.
     *
     * @param kind      the discriminator; must equal {@link #KIND_TOOL_RESULT}.
     * @param toolUseId the correlating tool-use id; non-blank.
     * @param status    {@code ok} or {@code error} (the schema's content-block
     *                  status enum, narrower than the event payload's enum).
     * @param content   the result content (arbitrary JSON), or {@code null}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolResult(String kind, String toolUseId, String status, Object content)
            implements ContentBlock {

        /**
         * Validates the block.
         *
         * @throws NullPointerException     if {@code toolUseId} or {@code status} is
         *                                  {@code null}.
         * @throws IllegalArgumentException if {@code kind} is not
         *                                  {@link #KIND_TOOL_RESULT}, or
         *                                  {@code toolUseId} is blank.
         */
        public ToolResult {
            requireKind(kind, KIND_TOOL_RESULT);
            Payloads.requireNonBlank(toolUseId, "toolUseId");
            Objects.requireNonNull(status, "status");
        }
    }

    private static void requireKind(String kind, String expected) {
        if (!expected.equals(kind)) {
            throw new IllegalArgumentException("kind must be '" + expected + "' but was '" + kind + "'");
        }
    }
}
