package com.srk.codingagent.persistence;

import com.srk.codingagent.config.PermissionMode;
import java.util.Objects;

/**
 * The body of a {@code SESSION_START} event: the immutable facts captured when a
 * session begins (ADR-0005; {@code 06-formal/event.schema.json},
 * {@code $defs.sessionStart}).
 *
 * <p>All four fields are required by the schema. {@code permissionMode} reuses the
 * {@link PermissionMode} value type already defined for configuration — its four
 * constants are exactly the schema's {@code permissionMode} enum, so there is one
 * authorization-mode vocabulary across config and the event log.
 *
 * @param mode           the session working mode (greenfield/brownfield).
 * @param repoKey        the repository key the session is scoped to; non-blank. For
 *                       T-0.4 this is supplied by the caller (repo-key derivation
 *                       from a git remote is a later session task).
 * @param modelId        the Bedrock model id the session runs; non-blank.
 * @param permissionMode the authorization mode in effect at session start.
 */
public record SessionStartPayload(
        SessionMode mode,
        String repoKey,
        String modelId,
        PermissionMode permissionMode) implements EventPayload {

    /**
     * Validates the payload.
     *
     * @throws NullPointerException     if {@code mode} or {@code permissionMode} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} or {@code modelId} is
     *                                  blank.
     */
    public SessionStartPayload {
        Objects.requireNonNull(mode, "mode");
        Payloads.requireNonBlank(repoKey, "repoKey");
        Payloads.requireNonBlank(modelId, "modelId");
        Objects.requireNonNull(permissionMode, "permissionMode");
    }

    @Override
    public EventType type() {
        return EventType.SESSION_START;
    }
}
