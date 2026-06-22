package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContentBlock} (content-block.schema.json). Oracles: the
 * schema's per-variant {@code kind} const, {@code minLength 1} on ids/names, and the
 * required fields of each block variant.
 */
class ContentBlockTest {

    @Test
    @DisplayName("the text factory sets kind=text (schema TextBlock const)")
    void text_setsKind() {
        // Oracle: content-block.schema.json TextBlock — kind const is "text".
        ContentBlock.Text block = ContentBlock.text("hello");

        assertEquals(ContentBlock.KIND_TEXT, block.kind(), "a text block's kind must be 'text'");
        assertEquals("hello", block.text());
    }

    @Test
    @DisplayName("the toolUse factory sets kind=toolUse and carries id/name/input (schema ToolUseBlock)")
    void toolUse_setsFields() {
        // Oracle: content-block.schema.json ToolUseBlock — kind const "toolUse";
        // required toolUseId/name/input.
        ContentBlock.ToolUse block = ContentBlock.toolUse("tu_01", "run", Map.of("k", "v"));

        assertEquals(ContentBlock.KIND_TOOL_USE, block.kind());
        assertEquals("tu_01", block.toolUseId());
        assertEquals("run", block.name());
        assertEquals(Map.of("k", "v"), block.input());
    }

    @Test
    @DisplayName("the toolResult factory sets kind=toolResult (schema ToolResultBlock)")
    void toolResult_setsKind() {
        // Oracle: content-block.schema.json ToolResultBlock — kind const "toolResult".
        ContentBlock.ToolResult block = ContentBlock.toolResult("tu_01", "ok", "done");

        assertEquals(ContentBlock.KIND_TOOL_RESULT, block.kind());
        assertEquals("tu_01", block.toolUseId());
        assertEquals("ok", block.status());
    }

    @Test
    @DisplayName("a blank toolUseId is rejected (schema minLength 1)")
    void toolUse_blankId_rejected() {
        // Oracle: content-block.schema.json ToolUseBlock — toolUseId minLength 1.
        assertThrows(IllegalArgumentException.class,
                () -> ContentBlock.toolUse("", "run", Map.of()),
                "a blank toolUseId must be rejected (minLength 1)");
    }

    @Test
    @DisplayName("a blank tool name is rejected (schema minLength 1)")
    void toolUse_blankName_rejected() {
        // Oracle: content-block.schema.json ToolUseBlock — name minLength 1.
        assertThrows(IllegalArgumentException.class,
                () -> ContentBlock.toolUse("tu_01", " ", Map.of()),
                "a blank tool name must be rejected (minLength 1)");
    }

    @Test
    @DisplayName("null text is rejected (TextBlock requires text)")
    void text_null_rejected() {
        // Oracle: content-block.schema.json TextBlock — text is required.
        assertThrows(NullPointerException.class, () -> ContentBlock.text(null),
                "null text must be rejected");
    }

    @Test
    @DisplayName("the reasoning factory sets kind=reasoning and carries text/signature verbatim (schema ReasoningBlock)")
    void reasoning_setsFieldsVerbatim() {
        // Oracle: content-block.schema.json ReasoningBlock — kind const "reasoning"; optional
        // text/signature/redactedContent. The signature is the tamper-check hash MUST be
        // replayed verbatim (INV-7), so the factory must carry it through unchanged.
        ContentBlock.Reasoning block =
                ContentBlock.reasoning("thinking...", "sig-abc123==", null);

        assertEquals(ContentBlock.KIND_REASONING, block.kind(),
                "a reasoning block's kind must be 'reasoning'");
        assertEquals("thinking...", block.text());
        assertEquals("sig-abc123==", block.signature(),
                "INV-7: the signature is carried verbatim");
        assertEquals(null, block.redactedContent());
    }

    @Test
    @DisplayName("a reasoning block may carry only kind + redactedContent (schema: only kind required)")
    void reasoning_redactedOnly() {
        // Oracle: content-block.schema.json ReasoningBlock — only kind is required; a redacted
        // block carries redactedContent with no text/signature.
        ContentBlock.Reasoning block = ContentBlock.reasoning(null, null, "base64redacted");

        assertEquals(ContentBlock.KIND_REASONING, block.kind());
        assertEquals("base64redacted", block.redactedContent());
        assertEquals(null, block.text(), "a redacted-only reasoning block has no text");
        assertEquals(null, block.signature(), "a redacted-only reasoning block has no signature");
    }

    @Test
    @DisplayName("a reasoning block tagged with a wrong kind discriminator is rejected (schema const)")
    void reasoning_mismatchedKind_rejected() {
        // Oracle: content-block.schema.json ReasoningBlock — kind const is fixed "reasoning"; a
        // block tagged with the wrong discriminator is invalid.
        assertThrows(IllegalArgumentException.class,
                () -> new ContentBlock.Reasoning("text", "t", "s", null),
                "a Reasoning block must reject a kind other than 'reasoning'");
    }

    @Test
    @DisplayName("constructing a variant with a mismatched kind discriminator is rejected")
    void mismatchedKind_rejected() {
        // Oracle: content-block.schema.json — each variant's kind const is fixed; a
        // Text block tagged with the wrong discriminator is invalid. The canonical
        // constructor enforces the const so a hand-built (e.g. deserialized) block with
        // a wrong kind cannot exist.
        assertThrows(IllegalArgumentException.class, () -> new ContentBlock.Text("toolUse", "x"),
                "a Text block must reject a kind other than 'text'");
    }
}
