package com.srk.codingagent.persistence;

/**
 * Whether a tool invocation is read-only or side-effecting, recorded on a
 * {@code PERMISSION_DECISION} event ({@code 06-formal/event.schema.json},
 * {@code $defs.permissionDecision}, {@code operationClass} enum). The permission
 * gate that classifies tools into these two classes is a later task; T-0.4 needs the
 * enum only to type the persisted permission-decision payload.
 */
public enum OperationClass {

    /** A non-mutating (read-only) operation. */
    READ,

    /** A mutating (side-effecting) operation that the permission mode may gate. */
    SIDE_EFFECTING
}
