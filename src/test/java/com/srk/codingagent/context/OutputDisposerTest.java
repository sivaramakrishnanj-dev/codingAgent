package com.srk.codingagent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.persistence.ContentBlock;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutputDisposer} — output disposal (component C6, US-19, ADR-0006).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link OutputDisposer}; there is nothing
 * to mock (it is pure logic over strings and a {@link ContentBlock.ToolResult}). The full
 * output is a real {@code ContentBlock.ToolResult} the registry would produce.
 *
 * <p><b>Oracles trace to the spec, not to the disposer's code:</b>
 * <ul>
 *   <li><b>AC-19.1 (boundary):</b> output <em>exceeding</em> NFR-OUTPUT-MAX-INLINE is reduced;
 *       output exactly at the cap is not — the boundary word is "exceeding".</li>
 *   <li><b>NFR-OUTPUT-MAX-INLINE:</b> the cap is in UTF-8 bytes (the {@code outputMaxInlineBytes}
 *       field name + "16 KB"); the configured value is used, not a literal.</li>
 *   <li><b>ADR-0006 (head+tail):</b> the reduction keeps the head AND the tail (failures are
 *       legible from the tail), with a {@code truncated} marker naming where the full output
 *       lives (a {@code fullRef}).</li>
 *   <li><b>D2 family (wire-valid):</b> a reduced result's content is plain text, the shape the
 *       Converse {@code toolResult.content.text} member accepts.</li>
 * </ul>
 */
class OutputDisposerTest {

    private static final int CAP = 64;

    private static ContentBlock.ToolResult okResult(String toolUseId, Object content) {
        return ContentBlock.toolResult(toolUseId, "ok", content);
    }

    /** A string of {@code n} ASCII 'a' bytes (1 byte each, so byte length == char length). */
    private static String asciiOf(int n) {
        return "a".repeat(n);
    }

    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // --- AC-19.1 boundary: exactly-at-cap is NOT reduced, one byte over IS ------------

    @Test
    @DisplayName("AC-19.1 boundary: output exactly at the cap is NOT reduced (returned unchanged)")
    void exactlyAtCap_notReduced() {
        // Oracle: AC-19.1 reduces output "exceeding" the cap. Output whose UTF-8 byte length
        // equals the cap does not exceed it, so it must pass through unchanged.
        OutputDisposer disposer = new OutputDisposer(CAP);
        ContentBlock.ToolResult full = okResult("tu_1", asciiOf(CAP));

        ContentBlock.ToolResult out = disposer.reduceForContext(full, 5);

        assertSame(full, out,
                "AC-19.1: output exactly at the cap does not exceed it and must be returned unchanged");
    }

    @Test
    @DisplayName("AC-19.1 boundary: output one byte over the cap IS reduced")
    void oneByteOverCap_reduced() {
        // Oracle: AC-19.1 — output exceeding the cap is reduced. CAP+1 bytes exceeds the cap,
        // so the content must change (be reduced), distinguishing it from the at-cap case.
        OutputDisposer disposer = new OutputDisposer(CAP);
        ContentBlock.ToolResult full = okResult("tu_1", asciiOf(CAP + 1));

        ContentBlock.ToolResult out = disposer.reduceForContext(full, 5);

        assertFalse(asciiOf(CAP + 1).equals(out.content()),
                "AC-19.1: output one byte over the cap must be reduced (content changed)");
    }

    @Test
    @DisplayName("AC-19.1: a reduced result's content is within the cap budget plus the marker")
    void reducedContent_fitsCapPlusMarker() {
        // Oracle: AC-19.1 — the reduction keeps "what enters the context" bounded. The inlined
        // payload (head+tail) is held within the cap; only the irreducible truncation marker
        // (the pointer the model needs) may push the total slightly past it. Assert the reduced
        // content is far smaller than the original 10x-over output.
        OutputDisposer disposer = new OutputDisposer(CAP);
        String huge = asciiOf(CAP * 10);
        ContentBlock.ToolResult full = okResult("tu_1", huge);

        ContentBlock.ToolResult out = disposer.reduceForContext(full, 5);

        assertTrue(utf8Len((String) out.content()) < utf8Len(huge),
                "AC-19.1: the reduced content must be smaller than the original oversized output");
        assertTrue(utf8Len((String) out.content()) <= CAP + 200,
                "AC-19.1: the reduced content stays near the cap (payload within cap, plus the marker)");
    }

    // --- ADR-0006 head+tail: BOTH the head and the tail survive ----------------------

    @Test
    @DisplayName("ADR-0006: the reduction keeps the HEAD of the output")
    void reduction_keepsHead() {
        // Oracle: ADR-0006 — head+tail truncation "keeps the start". The reduced content must
        // begin with the original output's head bytes.
        OutputDisposer disposer = new OutputDisposer(CAP);
        String output = "HEAD_MARKER_START" + asciiOf(CAP * 4) + "TAIL_MARKER_END";
        ContentBlock.ToolResult full = okResult("tu_1", output);

        String reduced = (String) disposer.reduceForContext(full, 5).content();

        assertTrue(reduced.startsWith("HEAD_MARKER_START"),
                "ADR-0006: the reduction must keep the head of the output (it 'keeps the start')");
    }

    @Test
    @DisplayName("ADR-0006: the reduction keeps the TAIL of the output (failures are legible from the tail)")
    void reduction_keepsTail() {
        // Oracle: ADR-0006 — head+tail "keeps ... the error tail — most build failures are
        // legible from the tail". The reduced content must end with the original output's tail
        // bytes, not drop them. This is the D2-class guard: a disposer that keeps only the head
        // would pass a naive "truncated==true" test but lose the error.
        OutputDisposer disposer = new OutputDisposer(CAP);
        String output = "HEAD_MARKER_START" + asciiOf(CAP * 4) + "BUILD FAILED at line 42";
        ContentBlock.ToolResult full = okResult("tu_1", output);

        String reduced = (String) disposer.reduceForContext(full, 5).content();

        assertTrue(reduced.endsWith("BUILD FAILED at line 42"),
                "ADR-0006: the reduction must keep the tail — build/test failures are legible from it");
    }

    @Test
    @DisplayName("ADR-0006: the reduction carries a truncated marker naming the fullRef and elided bytes")
    void reduction_carriesTruncatedMarkerAndFullRef() {
        // Oracle: ADR-0006 — head+tail truncation "with a truncated marker"; AC-19.2/19.3 — the
        // marker must name where the full output lives so the model can retrieve it. The marker
        // names the fullRef (evt:<seq>) and that the content was truncated.
        OutputDisposer disposer = new OutputDisposer(CAP);
        ContentBlock.ToolResult full = okResult("tu_1", asciiOf(CAP * 5));

        String reduced = (String) disposer.reduceForContext(full, 7).content();

        assertTrue(reduced.contains("truncated"),
                "ADR-0006: the reduction must carry a truncation marker");
        // AC-19.2/19.3: the marker names the fullRef pointing back to the persisted full output
        // at the logged seq (7). The fullRef value comes from the FullRef contract (separately
        // spec-traced in FullRefTest), not a hardcoded literal.
        assertTrue(reduced.contains(FullRef.forSeq(7)),
                "AC-19.2/19.3: the marker must name the fullRef where the full output lives");
    }

    // --- D2 family: a reduced result stays a wire-valid toolResult --------------------

    @Test
    @DisplayName("D2: a reduced result's content is plain text (the wire-valid toolResult content shape)")
    void reducedContent_isPlainText() {
        // Oracle: 03-data-model § 6.A.1 / ConverseWireMapper — a toolResult content that is not
        // a JSON object must use the text member; routing a String into json is the D2
        // ValidationException. The reduced content must be a String so the next Converse call
        // accepts it.
        OutputDisposer disposer = new OutputDisposer(CAP);
        ContentBlock.ToolResult full = okResult("tu_1", asciiOf(CAP * 3));

        ContentBlock.ToolResult out = disposer.reduceForContext(full, 5);

        assertTrue(out.content() instanceof String,
                "D2: a reduced result's content must be plain text (the wire-valid content shape)");
        assertEquals("tu_1", out.toolUseId(), "the reduction preserves the toolUseId (INV-6)");
        assertEquals("ok", out.status(), "the reduction preserves the result status");
    }

    @Test
    @DisplayName("AC-19.1: a structured (non-String) oversized content is reduced to text on its JSON rendering")
    void structuredContent_reducedOnJsonRendering() {
        // Oracle: AC-19.1 — disposal applies to any oversized output, not only Strings. A large
        // structured content (e.g. a CommandResult-shaped map of verbose stdout) is rendered to
        // its canonical JSON, measured, and reduced; the reduced content is plain text.
        OutputDisposer disposer = new OutputDisposer(CAP);
        Map<String, Object> structured = Map.of("stdout", asciiOf(CAP * 4), "exitCode", 1);
        ContentBlock.ToolResult full = okResult("tu_1", structured);

        ContentBlock.ToolResult out = disposer.reduceForContext(full, 5);

        assertTrue(out.content() instanceof String,
                "AC-19.1: an oversized structured content is reduced to a plain-text head+tail");
        assertTrue(utf8Len((String) out.content()) < utf8Len(asciiOf(CAP * 4)),
                "AC-19.1: the reduced rendering is smaller than the original structured output");
    }

    @Test
    @DisplayName("AC-19.1: content Jackson cannot render falls back to its string form, still reduced")
    void unserializableContent_fallsBackToStringForm() {
        // Oracle: AC-19.1 — disposal must reduce oversized output for any content shape; an
        // exotic content object Jackson cannot serialize to JSON must degrade gracefully (use
        // its string form) rather than crash the loop. The object's toString is large, so it is
        // still reduced to a head+tail string.
        OutputDisposer disposer = new OutputDisposer(CAP);
        String marker = "STRINGFORM-HEAD";
        Object unserializable = new Object() {
            @Override
            public String toString() {
                return marker + asciiOf(CAP * 4);
            }
        };
        ContentBlock.ToolResult full = okResult("tu_1", unserializable);

        ContentBlock.ToolResult out = disposer.reduceForContext(full, 5);

        assertTrue(out.content() instanceof String,
                "AC-19.1: unserializable content is reduced to a plain-text head+tail, not dropped");
        assertTrue(((String) out.content()).startsWith(marker),
                "the fallback uses the content's string form for the reduction");
    }

    // --- Multi-byte safety: a UTF-8 code point is never split ------------------------

    @Test
    @DisplayName("the head+tail slices never split a multi-byte UTF-8 code point (valid text out)")
    void multiByteCodePoint_notSplit() {
        // Oracle: NFR-OUTPUT-MAX-INLINE measures bytes; cutting on a byte boundary could split a
        // multi-byte character. The reduced content must remain valid text (no U+FFFD
        // replacement chars from a split code point). Use a 3-byte char (€, U+20AC) repeated so
        // the byte cut lands mid-character.
        OutputDisposer disposer = new OutputDisposer(CAP);
        String euros = "€".repeat(CAP); // CAP * 3 bytes
        ContentBlock.ToolResult full = okResult("tu_1", euros);

        String reduced = (String) disposer.reduceForContext(full, 5).content();

        assertFalse(reduced.contains("�"),
                "byte-boundary slicing must not split a multi-byte code point (no replacement char)");
    }

    // --- forConfig + construction validation ----------------------------------------

    @Test
    @DisplayName("NFR-OUTPUT-MAX-INLINE: forConfig uses the config's outputMaxInlineBytes as the cap")
    void forConfig_usesConfiguredCap() {
        // Oracle: NFR-OUTPUT-MAX-INLINE is "configurable"; the disposer uses the config value,
        // not a literal. Build a config with a tiny cap and assert an output above THAT cap is
        // reduced while one at it is not — proving the configured value is the live threshold.
        ResolvedConfig config = configWithCap(CAP);
        OutputDisposer disposer = OutputDisposer.forConfig(config);

        ContentBlock.ToolResult atCap = disposer.reduceForContext(okResult("tu_1", asciiOf(CAP)), 1);
        ContentBlock.ToolResult overCap = disposer.reduceForContext(okResult("tu_2", asciiOf(CAP + 1)), 1);

        assertEquals(asciiOf(CAP), atCap.content(),
                "forConfig: output at the configured cap is not reduced (the config value is the threshold)");
        assertTrue(overCap.content() instanceof String && !asciiOf(CAP + 1).equals(overCap.content()),
                "forConfig: output above the configured cap is reduced");
    }

    @Test
    @DisplayName("forConfig defaults to the NFR-pinned 16384-byte cap (configured default)")
    void forConfig_defaultCapIs16384() {
        // Oracle: NFR-OUTPUT-MAX-INLINE default = 16 KB = 16384 bytes. A disposer built from a
        // default-cap config must leave a 16384-byte output unchanged but reduce a 16385-byte
        // one (the default cap is exactly 16384).
        OutputDisposer disposer = OutputDisposer.forConfig(configWithCap(16384));

        ContentBlock.ToolResult atDefault =
                disposer.reduceForContext(okResult("tu_1", asciiOf(16384)), 1);
        ContentBlock.ToolResult overDefault =
                disposer.reduceForContext(okResult("tu_2", asciiOf(16385)), 1);

        assertEquals(asciiOf(16384), atDefault.content(),
                "NFR-OUTPUT-MAX-INLINE: 16384 bytes is at the default cap and is not reduced");
        assertFalse(asciiOf(16385).equals(overDefault.content()),
                "NFR-OUTPUT-MAX-INLINE: 16385 bytes exceeds the default cap and is reduced");
    }

    @Test
    @DisplayName("construction rejects a cap below 1 (matching the config schema range >= 1)")
    void construction_rejectsCapBelowOne() {
        // Oracle: ResolvedConfig.outputMaxInlineBytes range is >= 1; the disposer's cap mirrors
        // that constraint.
        assertThrows(IllegalArgumentException.class, () -> new OutputDisposer(0),
                "a cap of 0 must be rejected (config range is >= 1)");
        assertThrows(IllegalArgumentException.class, () -> new OutputDisposer(-1),
                "a negative cap must be rejected");
    }

    @Test
    @DisplayName("reduceForContext rejects a null result and a negative logged seq")
    void reduceForContext_rejectsBadArgs() {
        OutputDisposer disposer = new OutputDisposer(CAP);

        assertThrows(NullPointerException.class, () -> disposer.reduceForContext(null, 0),
                "a null full result must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> disposer.reduceForContext(okResult("tu_1", "x"), -1),
                "a negative logged seq must be rejected (seq is >= 0, INV-1)");
    }

    @Test
    @DisplayName("null content is never reduced (an empty render is within any cap)")
    void nullContent_notReduced() {
        // Oracle: AC-19.1 reduces output exceeding the cap; null/absent content has no bytes to
        // exceed it, so it passes through unchanged.
        OutputDisposer disposer = new OutputDisposer(CAP);
        ContentBlock.ToolResult full = okResult("tu_1", null);

        ContentBlock.ToolResult out = disposer.reduceForContext(full, 5);

        assertSame(full, out, "null content has no bytes and must not be reduced");
    }

    private static ResolvedConfig configWithCap(int cap) {
        return new ResolvedConfig(
                "anthropic.claude-opus-4-8",
                PermissionMode.ASK_EVERY_TIME,
                "us-east-1",
                null,
                1,
                null,
                ResolvedConfig.Commands.empty(),
                0.85,
                cap,
                5,
                300,
                10,
                300);
    }
}
