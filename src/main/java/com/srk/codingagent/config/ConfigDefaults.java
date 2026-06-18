package com.srk.codingagent.config;

/**
 * The built-in (compiled-in) configuration defaults — the lowest-precedence layer
 * in the resolution order (flags &gt; project &gt; global &gt; <em>defaults</em>,
 * ADR-0009). These are the NFR default values; a key absent from every file and
 * every flag resolves to the value here.
 *
 * <p>Each constant cites the requirement that pins it. The default model id is
 * {@code anthropic.claude-opus-4-8} (the verified GA id for the newest Claude
 * Opus); the exact id is finalized by ADR-0001/0002 at the model-client task, and
 * is carried here as the configured default per the T-0.2 scope note.
 */
public final class ConfigDefaults {

    /** Default Bedrock model id (newest Claude Opus GA id). AC-8.3 / NFR-MODEL-DEFAULT. */
    public static final String MODEL_ID = "anthropic.claude-opus-4-8";

    /** Default permission mode. AC-8.4 / NFR-PERMISSION-DEFAULT. */
    public static final PermissionMode PERMISSION_MODE = PermissionMode.ASK_EVERY_TIME;

    /** Default AWS region for Bedrock. */
    public static final String REGION = "us-east-1";

    /** Default maximum concurrent sub-agents. NFR-SUBAGENT-MAX. */
    public static final int SUB_AGENT_MAX = 1;

    /** Default context-utilization fraction that triggers compaction. */
    public static final double CONTEXT_COMPACT_THRESHOLD = 0.85;

    /** Default maximum tool-output bytes inlined into context. */
    public static final int OUTPUT_MAX_INLINE_BYTES = 16384;

    /** Default maximum verify-loop iterations. */
    public static final int VERIFY_MAX_ITERATIONS = 5;

    /** Default per-command timeout, in seconds. */
    public static final int COMMAND_TIMEOUT_SECONDS = 300;

    private ConfigDefaults() {
        // Constants holder; not instantiable.
    }
}
