package com.srk.codingagent.model.converse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.ContentBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConverseMessage} — the provider-agnostic request-side turn
 * (role + content blocks) the wire mapper consumes (§ 6.A.1, § 7).
 *
 * <p>Oracle: § 6.A.1 — a Converse message carries a role and a typed content[] array.
 * The value type pins those invariants (non-null role and content, defensive copy) so
 * the request boundary cannot be handed a malformed turn.
 */
class ConverseMessageTest {

    @Test
    @DisplayName("§ 6.A.1: user(...) creates a USER-role turn carrying the given content")
    void userFactory() {
        // Oracle: § 6.A.1 — a user turn carries the USER role. The factory must set it.
        ConverseMessage message = ConverseMessage.user(List.of(ContentBlock.text("hi")));

        assertEquals(Role.USER, message.role(), "§ 6.A.1: user(...) is a USER-role turn");
        assertEquals(1, message.content().size());
    }

    @Test
    @DisplayName("§ 6.A.1: assistant(...) creates an ASSISTANT-role turn")
    void assistantFactory() {
        // Oracle: § 6.A.1 — the model emits ASSISTANT turns. The factory must set the role.
        ConverseMessage message = ConverseMessage.assistant(List.of(ContentBlock.text("ok")));

        assertEquals(Role.ASSISTANT, message.role(), "§ 6.A.1: assistant(...) is an ASSISTANT-role turn");
    }

    @Test
    @DisplayName("content is defensively copied (the turn is immutable)")
    void contentDefensivelyCopied() {
        // Oracle: EJ Item 50 — a value type must copy mutable input so later mutation of the
        // caller's list cannot change the turn. § 6.A.1 treats a message as a stable record.
        List<ContentBlock> mutable = new ArrayList<>();
        mutable.add(ContentBlock.text("first"));

        ConverseMessage message = ConverseMessage.user(mutable);
        mutable.add(ContentBlock.text("second"));

        assertEquals(1, message.content().size(),
                "the turn must not see a post-construction mutation of the caller's list");
    }

    @Test
    @DisplayName("a null role is rejected")
    void nullRoleRejected() {
        // Oracle: § 6.A.1 — every message has a role; a roleless turn is invalid.
        assertThrows(NullPointerException.class,
                () -> new ConverseMessage(null, List.of()));
    }

    @Test
    @DisplayName("null content is rejected")
    void nullContentRejected() {
        // Oracle: § 6.A.1 — a message carries a content[] array; null content is invalid.
        assertThrows(NullPointerException.class,
                () -> new ConverseMessage(Role.USER, null));
    }

    @Test
    @DisplayName("an empty content list is permitted")
    void emptyContentPermitted() {
        // Oracle: § 6.A.1 — content[] is an array; emptiness is a turn-assembly concern of
        // the loop, not a value-type invariant. An empty turn is constructable here.
        ConverseMessage message = ConverseMessage.user(List.of());

        assertTrue(message.content().isEmpty(), "an empty content list is permitted at the value-type level");
    }
}
