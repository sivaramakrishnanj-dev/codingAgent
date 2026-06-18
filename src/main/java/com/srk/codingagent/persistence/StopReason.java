package com.srk.codingagent.persistence;

/**
 * Why a model turn stopped, carried by a {@code MODEL_RESPONSE} event's
 * {@link ModelResponsePayload}. The constants are the schema's {@code stopReason}
 * enum ({@code 06-formal/event.schema.json}, {@code $defs.modelResponse}), which
 * mirrors the Bedrock Converse stop-reason vocabulary; a value outside this enum is
 * rejected (CT-SCH-3, § 6.A.1).
 *
 * <p>T-0.4 needs this enum to type the model-response payload it serializes for the
 * contract fixture. The behavioural handling of each stop reason (the agent loop's
 * branch on TOOL_USE vs. END_TURN, etc.) belongs to the loop task.
 */
public enum StopReason {

    /** The model finished its turn normally. */
    END_TURN,

    /** The model requested one or more tool invocations. */
    TOOL_USE,

    /** The response hit the max-tokens limit. */
    MAX_TOKENS,

    /** A configured stop sequence was emitted. */
    STOP_SEQUENCE,

    /** A guardrail intervened in the response. */
    GUARDRAIL_INTERVENED,

    /** Content was filtered. */
    CONTENT_FILTERED,

    /** The model produced a malformed tool-use block. */
    MALFORMED_TOOL_USE,

    /** The model produced malformed output. */
    MALFORMED_MODEL_OUTPUT,

    /** The model's context window was exceeded. */
    MODEL_CONTEXT_WINDOW_EXCEEDED
}
