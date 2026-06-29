package com.srk.codingagent.cli;

import com.srk.codingagent.persistence.ContentBlock;
import java.util.Objects;

/**
 * The outcome of resolving an {@code --attach}/{@code /attach <path>} request (component C1,
 * the C1&rarr;C4 attachment pipeline, T-4.2). A request resolves to exactly one of two shapes:
 *
 * <ul>
 *   <li>{@link Attached} — the file was recognized, its {@link ContentBlock} (an
 *       {@link ContentBlock.Image} or {@link ContentBlock.Document}) was built, and the active
 *       {@code ModelCapabilityProfile} reports the matching input support (INV-19). The block is
 *       attached to the user turn so the {@code ConverseWireMapper} renders it into the
 *       request.</li>
 *   <li>{@link Declined} — the attachment is <em>not</em> sent. Either the model's capability
 *       profile lacks the corresponding input support (INV-19 — graceful degradation: decline
 *       with a message, do not fail the call), the extension maps to no supported format, or the
 *       request is otherwise unusable. The {@link Declined#message()} is the user-facing line the
 *       CLI prints; no block joins the turn.</li>
 * </ul>
 *
 * <p>This is a sealed result rather than a {@code null}/exception pair so the decline path is a
 * first-class, message-carrying outcome (INV-19's "declined with a message, not sent") that the
 * caller handles explicitly rather than by catching.
 */
public sealed interface Attachment permits Attachment.Attached, Attachment.Declined {

    /**
     * Whether this attachment was admitted (a block to add to the turn) versus declined.
     *
     * @return {@code true} for {@link Attached}, {@code false} for {@link Declined}.
     */
    boolean attached();

    /**
     * Creates an attached outcome carrying the content block to add to the user turn.
     *
     * @param block the resolved image/document content block; must not be {@code null}.
     * @return an {@link Attached} outcome.
     */
    static Attached of(ContentBlock block) {
        return new Attached(block);
    }

    /**
     * Creates a declined outcome carrying the user-facing reason (INV-19 — declined with a
     * message, not sent).
     *
     * @param message the user-facing decline reason; non-blank.
     * @return a {@link Declined} outcome.
     */
    static Declined declined(String message) {
        return new Declined(message);
    }

    /**
     * An admitted attachment: the content block to add to the user turn.
     *
     * @param block the resolved {@link ContentBlock.Image} or {@link ContentBlock.Document};
     *              never {@code null}.
     */
    record Attached(ContentBlock block) implements Attachment {

        /**
         * Validates the outcome.
         *
         * @throws NullPointerException if {@code block} is {@code null}.
         */
        public Attached {
            Objects.requireNonNull(block, "block");
        }

        @Override
        public boolean attached() {
            return true;
        }
    }

    /**
     * A declined attachment: the user-facing reason the attachment was not sent (INV-19).
     *
     * @param message the user-facing decline reason; never {@code null} or blank.
     */
    record Declined(String message) implements Attachment {

        /**
         * Validates the outcome.
         *
         * @throws NullPointerException     if {@code message} is {@code null}.
         * @throws IllegalArgumentException if {@code message} is blank.
         */
        public Declined {
            if (Objects.requireNonNull(message, "message").isBlank()) {
                throw new IllegalArgumentException("a declined attachment must carry a message");
            }
        }

        @Override
        public boolean attached() {
            return false;
        }
    }
}
