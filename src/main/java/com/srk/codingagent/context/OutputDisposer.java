package com.srk.codingagent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.persistence.ContentBlock;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Output disposal (component C6, US-19, ADR-0006 &sect; Output disposal): the half of the
 * Context Manager that keeps an oversized tool/command output from overwhelming the model's
 * context while never losing the full output.
 *
 * <p><b>The split (AC-19.1/19.2).</b> A tool/command result flows to two places: the
 * session event log (the durable record) and the model's context (the transcript the next
 * Converse call resends). The agent loop persists the <em>full</em> result to the log first
 * &mdash; that log is the full store (AC-19.2), keyed by the appended event's monotonic
 * {@code seq} &mdash; then calls {@link #reduceForContext(ContentBlock.ToolResult, int)} to
 * decide what enters the context. When the rendered output exceeds the configured cap
 * (NFR-OUTPUT-MAX-INLINE, {@link ResolvedConfig#outputMaxInlineBytes()}), this disposer
 * replaces the content with a head+tail reduction carrying a {@code truncated} marker and a
 * {@code fullRef} pointer to the persisted full output (AC-19.1); otherwise the result is
 * returned unchanged.
 *
 * <p><b>Head+tail, not summarize (ADR-0006).</b> The default strategy keeps a head slice and
 * a tail slice of the output with a marker between them naming how many bytes were elided and
 * where the full output lives. The tail is kept deliberately: build and test failures are
 * usually legible from the tail (the error and stack trace land last), so dropping the tail
 * would defeat the purpose. The optional model-call summarizer is the escalation, not the
 * default, and is out of scope for this task (ADR-0006: "Summarization is the escalation, not
 * the default").
 *
 * <p><b>Byte length, UTF-8 (NFR-OUTPUT-MAX-INLINE).</b> The cap is measured in UTF-8 bytes,
 * matching the {@code outputMaxInlineBytes} config field name and the NFR's "16 KB" wire
 * reality. An output whose rendered UTF-8 byte length is {@code <=} the cap is left untouched;
 * one byte over is reduced. The head/tail slices are cut on UTF-8 byte boundaries (never
 * splitting a multi-byte code point), so the reduced content is always valid text.
 *
 * <p><b>Wire-valid reduction (D2 family).</b> The reduced content is always a plain
 * {@link String}, which the wire mapper sends as a Converse {@code toolResult.content.text}
 * member &mdash; a content shape the next Converse call accepts. The disposer never produces a
 * structured object the {@code json} member would reject.
 *
 * <p><b>Rendering.</b> A {@code String} content renders to itself; any other content (e.g. a
 * {@code CommandResult}) renders to its canonical JSON, the stable text representation the cap
 * is measured against and the reduction is cut from. The full, un-rendered content always
 * stays in the log untouched, so retrieval (AC-19.3, {@link OutputRetrieval}) returns the
 * original object.
 *
 * <p>Stateless and immutable: holds only the configured cap. One instance is safely shared.
 */
public final class OutputDisposer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutputDisposer.class);

    /**
     * The fraction of the cap budget kept as the head slice; the remainder (minus the marker)
     * is the tail. A larger tail than head reflects ADR-0006's rationale that failures are
     * legible from the tail, while still keeping enough head for the command/context preamble.
     */
    private static final double HEAD_FRACTION = 0.4;

    private final int maxInlineBytes;
    private final ObjectMapper mapper;

    /**
     * Creates a disposer that reduces output rendered to more than {@code maxInlineBytes}
     * UTF-8 bytes.
     *
     * @param maxInlineBytes the inline cap in UTF-8 bytes (NFR-OUTPUT-MAX-INLINE); must be
     *                       {@code >= 1} (matching {@link ResolvedConfig#outputMaxInlineBytes()}'s
     *                       schema range).
     * @throws IllegalArgumentException if {@code maxInlineBytes < 1}.
     */
    public OutputDisposer(int maxInlineBytes) {
        if (maxInlineBytes < 1) {
            throw new IllegalArgumentException(
                    "maxInlineBytes must be >= 1 (was " + maxInlineBytes + ")");
        }
        this.maxInlineBytes = maxInlineBytes;
        this.mapper = JsonMapper.builder().build();
    }

    /**
     * Builds a disposer wired to the resolved configuration's inline cap
     * (NFR-OUTPUT-MAX-INLINE, default 16384). Mirrors the {@code forConfig} idiom used by the
     * other config-wired control units (e.g. {@code VerifyLoop.forConfig}).
     *
     * @param config the resolved configuration; must not be {@code null}.
     * @return a disposer using {@link ResolvedConfig#outputMaxInlineBytes()} as the cap.
     * @throws NullPointerException if {@code config} is {@code null}.
     */
    public static OutputDisposer forConfig(ResolvedConfig config) {
        Objects.requireNonNull(config, "config");
        return new OutputDisposer(config.outputMaxInlineBytes());
    }

    /**
     * Reduces a tool result for the model's context if its rendered output exceeds the cap,
     * pointing the reduction at the full output already persisted at the given log sequence.
     *
     * <p>The caller (the agent loop) must have already appended the <em>full</em> result to
     * the event log and pass that appended event's {@code seq} here, so the {@code fullRef}
     * the reduction carries resolves to the persisted full output (AC-19.2/19.3).
     *
     * @param full      the full tool-result block produced by the registry (its content is
     *                  the un-truncated tool/command output); must not be {@code null}.
     * @param loggedSeq the {@code seq} of the TOOL_RESULT event that persisted the full output
     *                  (from {@link com.srk.codingagent.persistence.EventLog#append}); must be
     *                  {@code >= 0}.
     * @return the {@code full} block unchanged when its rendered output is within the cap, or a
     *         new block whose content is the head+tail reduction with a truncation marker and a
     *         {@code fullRef} pointer when it exceeds the cap (AC-19.1). Never {@code null}.
     * @throws NullPointerException     if {@code full} is {@code null}.
     * @throws IllegalArgumentException if {@code loggedSeq < 0}.
     */
    public ContentBlock.ToolResult reduceForContext(ContentBlock.ToolResult full, int loggedSeq) {
        Objects.requireNonNull(full, "full");
        if (loggedSeq < 0) {
            throw new IllegalArgumentException("loggedSeq must be >= 0 (was " + loggedSeq + ")");
        }

        String rendered = render(full.content());
        byte[] bytes = rendered.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxInlineBytes) {
            // AC-19.1 trigger is "exceeding" the cap; an output exactly at the cap is not
            // reduced. Nothing enters the context that the cap forbids, so the full result
            // flows through untouched.
            return full;
        }

        String fullRef = FullRef.forSeq(loggedSeq);
        String reduced = headTail(bytes, fullRef);
        LOGGER.info(
                "Reduced tool result {} for context: {} bytes -> {} bytes (head+tail), full at {} (AC-19.1)",
                full.toolUseId(), bytes.length,
                reduced.getBytes(StandardCharsets.UTF_8).length, fullRef);
        return ContentBlock.toolResult(full.toolUseId(), full.status(), reduced);
    }

    /**
     * Renders content to the stable text representation the cap is measured against: a
     * {@code String} renders to itself; anything else renders to canonical JSON. {@code null}
     * content renders to the empty string (never truncated).
     */
    private String render(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            // A content object that cannot be rendered to JSON cannot be measured or reduced
            // faithfully; fall back to its string form so disposal still degrades gracefully
            // rather than crashing the loop on an exotic content shape.
            LOGGER.warn("Could not render tool-result content to JSON for sizing; "
                    + "using its string form (class {})", content.getClass().getName(), e);
            return String.valueOf(content);
        }
    }

    /**
     * Builds the head+tail reduction: a head slice, a marker line naming the elided byte count
     * and the {@code fullRef}, then a tail slice. The cap governs the retained <em>payload</em>
     * (head + tail) bytes; the truncation marker is additive overhead &mdash; it is the
     * irreducible pointer the model needs to retrieve the full output, so it is never traded
     * against the payload (which would, at a small cap, drop the head or tail the spec requires
     * to survive). Slices are cut on UTF-8 byte boundaries so a multi-byte code point is never
     * split.
     */
    private String headTail(byte[] bytes, String fullRef) {
        int headBudget = Math.max(1, (int) Math.round(maxInlineBytes * HEAD_FRACTION));
        int tailBudget = Math.max(1, maxInlineBytes - headBudget);
        int elided = Math.max(0, bytes.length - headBudget - tailBudget);

        String head = sliceFromStart(bytes, headBudget);
        String tail = sliceFromEnd(bytes, tailBudget);
        return head + marker(elided, bytes.length, fullRef) + tail;
    }

    private static String marker(int elidedBytes, int totalBytes, String fullRef) {
        return "\n... [truncated " + elidedBytes + " of " + totalBytes
                + " bytes; full output at " + fullRef + "] ...\n";
    }

    /** Decodes the first {@code budget} bytes (rounded down to a code-point boundary) as UTF-8. */
    private static String sliceFromStart(byte[] bytes, int budget) {
        int end = Math.min(budget, bytes.length);
        end = retreatToBoundary(bytes, end);
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** Decodes the last {@code budget} bytes (advanced up to a code-point boundary) as UTF-8. */
    private static String sliceFromEnd(byte[] bytes, int budget) {
        int start = Math.max(0, bytes.length - budget);
        start = advanceToBoundary(bytes, start);
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    /**
     * Moves {@code index} back to the start of the UTF-8 code point it lands in (or on), so a
     * head slice ending at {@code index} does not split a multi-byte character. A
     * continuation byte matches {@code 10xxxxxx}.
     */
    private static int retreatToBoundary(byte[] bytes, int index) {
        int i = index;
        while (i > 0 && i < bytes.length && isContinuation(bytes[i])) {
            i--;
        }
        return i;
    }

    /**
     * Moves {@code index} forward to the start of the next UTF-8 code point, so a tail slice
     * starting at {@code index} does not begin in the middle of a multi-byte character.
     */
    private static int advanceToBoundary(byte[] bytes, int index) {
        int i = index;
        while (i > 0 && i < bytes.length && isContinuation(bytes[i])) {
            i++;
        }
        return i;
    }

    private static boolean isContinuation(byte b) {
        return (b & 0xC0) == 0x80;
    }
}
