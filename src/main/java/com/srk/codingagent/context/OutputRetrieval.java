package com.srk.codingagent.context;

import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.persistence.ToolResultPayload;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full-output retrieval (component C6, AC-19.3): resolves a {@code fullRef} back to the full
 * tool/command output that was persisted to the session event log when the output was reduced
 * for context ({@link OutputDisposer}). This is the "retrieve rather than re-run" path of
 * US-19: where the reduced output later proves insufficient, the model can recover the full
 * output from the log instead of re-running the command.
 *
 * <p><b>The log is the full store (AC-19.2).</b> When the disposer reduced an output, the
 * agent loop had already persisted the <em>full</em> result as a TOOL_RESULT event; the
 * {@code fullRef} the reduction carries is {@code "evt:<seq>"} ({@link FullRef}), the
 * sequence number of that event. Retrieval reads the session's events in order
 * ({@link SessionStore#readEvents}) and returns the {@code result} of the TOOL_RESULT event at
 * that {@code seq}.
 *
 * <p><b>Not found is surfaced as empty, not a crash.</b> A {@code fullRef} that is malformed,
 * points past the log, or lands on a non-TOOL_RESULT event yields {@link Optional#empty()} so
 * the caller can decide how to react, rather than throwing. A genuine I/O / corruption failure
 * reading the log still surfaces as the store's {@code PersistenceException} (AC-7.5) &mdash;
 * a missing pointer and an unreadable log are different conditions.
 *
 * <p>Immutable: holds only the session store it reads through.
 */
public final class OutputRetrieval {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutputRetrieval.class);

    private final SessionStore store;

    /**
     * Creates a retriever that resolves references against the given session store.
     *
     * @param store the session store whose event logs hold the full outputs; must not be
     *              {@code null}.
     * @throws NullPointerException if {@code store} is {@code null}.
     */
    public OutputRetrieval(SessionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Retrieves the full output a {@code fullRef} points at, from the given session's event
     * log (AC-19.3).
     *
     * @param repoKey   the repository key the session lives under; non-blank.
     * @param sessionId the session whose log holds the full output; non-blank.
     * @param fullRef   the pointer carried by the reduced result (an {@code "evt:<seq>"}
     *                  reference); must not be {@code null}.
     * @return the full result content persisted at the referenced event, or
     *         {@link Optional#empty()} when the reference is malformed or does not resolve to a
     *         TOOL_RESULT event in the session. The returned value is the original, un-reduced
     *         content (the same object the registry produced, e.g. a {@code CommandResult}'s
     *         deserialized map or the full text).
     * @throws NullPointerException     if {@code fullRef} is {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} or {@code sessionId} is blank.
     * @throws com.srk.codingagent.persistence.PersistenceException if the session log exists
     *                                  but cannot be read (AC-7.5).
     */
    public Optional<Object> retrieve(String repoKey, String sessionId, String fullRef) {
        Objects.requireNonNull(fullRef, "fullRef");
        Optional<Integer> seq = FullRef.seqOf(fullRef);
        if (seq.isEmpty()) {
            LOGGER.warn("fullRef '{}' is not a well-formed evt: pointer; nothing to retrieve", fullRef);
            return Optional.empty();
        }

        int target = seq.get();
        for (Event event : store.readEvents(repoKey, sessionId)) {
            if (event.seq() == target) {
                if (event.payload() instanceof ToolResultPayload result) {
                    LOGGER.info("Retrieved full output for {} from session {} (AC-19.3)",
                            fullRef, sessionId);
                    return Optional.ofNullable(result.result());
                }
                LOGGER.warn("fullRef '{}' resolves to a {} event, not a TOOL_RESULT",
                        fullRef, event.type());
                return Optional.empty();
            }
        }
        LOGGER.warn("fullRef '{}' did not resolve to any event in session {}", fullRef, sessionId);
        return Optional.empty();
    }
}
