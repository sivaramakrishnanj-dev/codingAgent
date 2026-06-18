package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.srk.codingagent.config.PermissionMode;
import java.util.Objects;

/**
 * The body of a {@code PERMISSION_DECISION} event: the authorization decision
 * recorded for a tool invocation ({@code 06-formal/event.schema.json},
 * {@code $defs.permissionDecision}). The schema requires {@code toolUseId},
 * {@code operationClass}, {@code mode}, and {@code decision}, and allows an optional
 * {@code matchedGrant}.
 *
 * @param toolUseId       the correlating tool-use id; non-blank.
 * @param operationClass  the read/side-effecting classification of the tool.
 * @param mode            the permission mode in effect when the decision was made;
 *                        reuses the shared {@link PermissionMode} vocabulary.
 * @param decision        the approve/deny outcome.
 * @param matchedGrant    the grant that matched (for remembered approvals), or
 *                        {@code null} when none applied. Omitted from JSON when
 *                        {@code null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionDecisionPayload(
        String toolUseId,
        OperationClass operationClass,
        PermissionMode mode,
        PermissionDecisionOutcome decision,
        String matchedGrant) implements EventPayload {

    /**
     * Validates the payload.
     *
     * @throws NullPointerException     if {@code operationClass}, {@code mode}, or
     *                                  {@code decision} is {@code null}.
     * @throws IllegalArgumentException if {@code toolUseId} is blank.
     */
    public PermissionDecisionPayload {
        Payloads.requireNonBlank(toolUseId, "toolUseId");
        Objects.requireNonNull(operationClass, "operationClass");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(decision, "decision");
    }

    @Override
    public EventType type() {
        return EventType.PERMISSION_DECISION;
    }
}
