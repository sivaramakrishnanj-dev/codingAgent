package com.srk.codingagent.model.converse;

import com.srk.codingagent.persistence.ContentBlock;
import java.util.List;
import java.util.Objects;

/**
 * One conversation turn at the Model Client's request boundary: a {@link Role} plus
 * the ordered {@link ContentBlock}s that make up the turn (§ 6.A.1 / 03-data-model.md
 * § 7).
 *
 * <p>This is the provider-agnostic request shape the owned agent loop (T-0.8) assembles
 * and hands to {@link ConverseWireMapper#toRequest} — our domain {@link ContentBlock}s
 * carried with their role, not the SDK {@code Message} type. The mapper translates the
 * list to Converse {@code messages[]} at the wire boundary; nothing upstream of the
 * Model Client sees the SDK type (component C4 invariant: provider-agnostic surface).
 *
 * <p>Because the Converse API is stateless (§ 6.A.1 — resend all messages each call),
 * a request carries the full transcript as a {@code List<ConverseMessage>}. That whole
 * list is what {@link ConverseWireMapper} scans to enforce the toolUse&harr;toolResult
 * pairing invariant (INV-6): a {@code toolResult} block is valid only if some earlier
 * {@code toolUse} block in the same transcript produced its {@code toolUseId}.
 *
 * @param role    who produced the turn; never {@code null}.
 * @param content the turn's content blocks; never {@code null} (empty is permitted).
 *                Defensively copied.
 */
public record ConverseMessage(Role role, List<ContentBlock> content) {

    /**
     * Validates the turn and defensively copies {@code content}.
     *
     * @throws NullPointerException if {@code role} or {@code content} (or any element)
     *                              is {@code null}.
     */
    public ConverseMessage {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(Objects.requireNonNull(content, "content"));
    }

    /**
     * Creates a user turn from the given content blocks.
     *
     * @param content the user turn's content blocks; never {@code null}.
     * @return a {@link Role#USER} message.
     */
    public static ConverseMessage user(List<ContentBlock> content) {
        return new ConverseMessage(Role.USER, content);
    }

    /**
     * Creates an assistant turn from the given content blocks.
     *
     * @param content the assistant turn's content blocks; never {@code null}.
     * @return a {@link Role#ASSISTANT} message.
     */
    public static ConverseMessage assistant(List<ContentBlock> content) {
        return new ConverseMessage(Role.ASSISTANT, content);
    }
}
