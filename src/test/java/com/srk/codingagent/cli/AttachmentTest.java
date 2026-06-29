package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.ContentBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Attachment} — the attachment-resolution result type (T-4.2). Oracle:
 * INV-19's two outcomes — an attachment is either admitted (a block to add to the user turn) or
 * declined with a message (not sent).
 */
class AttachmentTest {

    @Test
    @DisplayName("an attached outcome carries its block and reports attached()=true")
    void attachedCarriesBlock() {
        // Oracle: INV-19 — an admitted attachment carries the block to add to the turn.
        ContentBlock block = ContentBlock.image("png", "/tmp/x.png");
        Attachment.Attached attached = Attachment.of(block);

        assertTrue(attached.attached(), "an attached outcome reports attached()=true");
        assertSame(block, attached.block(), "the attached outcome carries the resolved block");
    }

    @Test
    @DisplayName("a declined outcome carries its message and reports attached()=false (INV-19)")
    void declinedCarriesMessage() {
        // Oracle: INV-19 — a declined attachment is "declined with a message, not sent".
        Attachment.Declined declined = Attachment.declined("model does not support image input");

        assertFalse(declined.attached(), "a declined outcome reports attached()=false");
        assertEquals("model does not support image input", declined.message(),
                "the declined outcome carries the user-facing message");
    }

    @Test
    @DisplayName("an attached outcome rejects a null block")
    void attachedRejectsNullBlock() {
        assertThrows(NullPointerException.class, () -> Attachment.of(null),
                "an attached outcome must carry a block");
    }

    @Test
    @DisplayName("a declined outcome rejects a null or blank message (INV-19 requires a message)")
    void declinedRejectsBlankMessage() {
        // Oracle: INV-19 — a decline is "with a message"; an empty decline reason is meaningless.
        assertThrows(NullPointerException.class, () -> Attachment.declined(null),
                "a declined outcome requires a message");
        assertThrows(IllegalArgumentException.class, () -> Attachment.declined("  "),
                "a declined outcome requires a non-blank message");
    }
}
