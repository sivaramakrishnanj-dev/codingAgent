package com.srk.codingagent.model.converse;

/**
 * The conversation role of a {@link ConverseMessage}: who produced the turn.
 *
 * <p>This is the Model Client's provider-agnostic role surface (component C4
 * invariant: provider-agnostic, no business logic). The wire-format mapper translates
 * it to the Bedrock Converse {@code ConversationRole} at the boundary; the rest of the
 * system never sees the SDK enum. The Converse protocol uses only {@code USER} and
 * {@code ASSISTANT} for messages (§ 6.A.1): the model emits {@code ASSISTANT} turns,
 * and every turn we send — including the {@code toolResult} blocks appended after a
 * tool runs — is a {@code USER} turn.
 */
public enum Role {

    /** A turn produced by the user (or the agent on the user's behalf, e.g. tool results). */
    USER,

    /** A turn produced by the model. */
    ASSISTANT
}
