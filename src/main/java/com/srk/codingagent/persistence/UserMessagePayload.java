package com.srk.codingagent.persistence;

import java.util.List;
import java.util.Objects;

/**
 * The body of a {@code USER_MESSAGE} event: the content blocks of a user turn
 * ({@code 06-formal/event.schema.json}, {@code $defs.userMessage}). The schema
 * requires a {@code content} array of {@link ContentBlock}s.
 *
 * @param content the user turn's content blocks; must not be {@code null} (an empty
 *                list is permitted by the schema's array type). Defensively copied.
 */
public record UserMessagePayload(List<ContentBlock> content) implements EventPayload {

    /**
     * Validates the payload and defensively copies {@code content}.
     *
     * @throws NullPointerException if {@code content} (or any element) is
     *                              {@code null}.
     */
    public UserMessagePayload {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
    }

    @Override
    public EventType type() {
        return EventType.USER_MESSAGE;
    }
}
