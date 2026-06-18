package com.srk.codingagent.persistence;

import java.util.List;
import java.util.Objects;

/**
 * The body of a {@code MODEL_RESPONSE} event: why the turn stopped plus the model's
 * content blocks ({@code 06-formal/event.schema.json}, {@code $defs.modelResponse}).
 * Both {@code stopReason} and {@code content} are required by the schema.
 *
 * @param stopReason why the model turn stopped.
 * @param content    the model's content blocks (text/toolUse for the fixture's
 *                   kinds); must not be {@code null}. Defensively copied.
 */
public record ModelResponsePayload(StopReason stopReason, List<ContentBlock> content)
        implements EventPayload {

    /**
     * Validates the payload and defensively copies {@code content}.
     *
     * @throws NullPointerException if {@code stopReason} or {@code content} (or any
     *                              element) is {@code null}.
     */
    public ModelResponsePayload {
        Objects.requireNonNull(stopReason, "stopReason");
        content = List.copyOf(Objects.requireNonNull(content, "content"));
    }

    @Override
    public EventType type() {
        return EventType.MODEL_RESPONSE;
    }
}
