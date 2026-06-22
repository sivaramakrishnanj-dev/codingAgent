package com.srk.codingagent.persistence;

import com.srk.codingagent.model.converse.ConverseMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconstructs a conversation's request context by replaying a session's persisted
 * events back into a {@code List<ConverseMessage>} (resume, AC-7.2; the wire-format
 * reversal of {@code 03-data-model.md} § 7: <em>events &rarr; our blocks &rarr; Converse
 * {@code messages[]}</em>). This is the C2/C15-boundary reconstruction the session store
 * deliberately left to a later task (see {@link SessionStore#readEvents} Javadoc).
 *
 * <p><b>Which events are messages (the transcript projection).</b> The agent loop (T-0.8)
 * accumulates exactly two event kinds as conversation turns it resends on each stateless
 * Converse call: a {@code USER_MESSAGE} (the developer turn, and the batched tool-result
 * turn the loop sends back after a tool runs) becomes a {@link ConverseMessage#user(List)}
 * turn, and a {@code MODEL_RESPONSE} (the assistant turn carrying the model's content
 * blocks) becomes a {@link ConverseMessage#assistant(List)} turn. Replay selects those
 * message-bearing events in {@code seq} order and maps each by its payload content
 * (03-architecture § 5: "resume is just replaying these appends into a fresh
 * {@code messages[]}").
 *
 * <p><b>Audit-only events are not messages.</b> The remaining event kinds —
 * {@code SESSION_START}, {@code MODEL_USAGE}, {@code TOOL_USE} (the per-block digest),
 * {@code PERMISSION_DECISION}, {@code TOOL_RESULT} (the per-block audit), {@code OUTCOME},
 * {@code ERROR}, and the others — are the durable audit trail of <em>how</em> the
 * transcript came to be; they are not turns in the {@code messages[]} the model sees and
 * are skipped. In a real loop log the tool results the model is sent back live inside a
 * batched {@code USER_MESSAGE} event (the loop appends one {@link UserMessagePayload} whose
 * content is the tool-result blocks); the separate per-block {@code TOOL_RESULT} events are
 * the audit detail, so replaying {@code USER_MESSAGE} + {@code MODEL_RESPONSE} reproduces
 * exactly the transcript the loop accumulated — preserving the
 * {@code toolUse}&harr;{@code toolResult} pairing (INV-6) and the user/assistant ordering
 * the loop produced.
 *
 * <p><b>Order fidelity (INV-1).</b> The events are replayed in the order
 * {@link SessionStore#readEvents} returns them, which INV-1 guarantees is the gap-free
 * {@code seq} order. Replay never reorders; it projects.
 *
 * <p>Stateless: holds no per-session state; one instance can replay any number of sessions.
 */
public final class SessionReplay {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionReplay.class);

    /**
     * Replays a session's events into the {@code messages[]} that reconstructs the
     * conversation's request context (AC-7.2).
     *
     * @param events the session's events in {@code seq} order (as
     *               {@link SessionStore#readEvents} returns them); must not be
     *               {@code null}.
     * @return the reconstructed conversation turns in order — one {@link ConverseMessage}
     *         per {@code USER_MESSAGE}/{@code MODEL_RESPONSE} event, audit-only events
     *         skipped; never {@code null} (empty when the session has no message-bearing
     *         events).
     * @throws NullPointerException if {@code events} (or any element) is {@code null}.
     */
    public List<ConverseMessage> replay(List<Event> events) {
        Objects.requireNonNull(events, "events");
        List<ConverseMessage> messages = new ArrayList<>();
        for (Event event : events) {
            EventPayload payload = Objects.requireNonNull(event, "event").payload();
            if (payload instanceof UserMessagePayload user) {
                messages.add(ConverseMessage.user(user.content()));
            } else if (payload instanceof ModelResponsePayload response) {
                messages.add(ConverseMessage.assistant(response.content()));
            }
            // All other event kinds are audit detail, not conversation turns: skipped.
        }
        LOGGER.debug("replayed {} message turn(s) from {} event(s)", messages.size(), events.size());
        return messages;
    }
}
