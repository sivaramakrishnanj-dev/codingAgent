package com.srk.codingagent.config;

/**
 * The built-in (compiled-in) configuration defaults — the lowest-precedence layer
 * in the resolution order (flags &gt; project &gt; global &gt; <em>defaults</em>,
 * ADR-0009). These are the NFR default values; a key absent from every file and
 * every flag resolves to the value here.
 *
 * <p>Each constant cites the requirement that pins it. The default model id is
 * the cross-region inference-profile form {@code us.anthropic.claude-opus-4-8}
 * (NOT the bare model id): on-demand Converse for Claude Opus rejects the bare
 * {@code anthropic.claude-opus-4-8} id with a {@code ValidationException}
 * ("Invocation of model ID ... with on-demand throughput isn't supported. Retry
 * your request with the ID or ARN of an inference profile that contains this
 * model."), so the pinned default must be an inference-profile id (ADR-0001,
 * NFR-MODEL-DEFAULT). The exact id is finalized by ADR-0001/0002 at the
 * model-client task; the {@code us.} cross-region profile is preferred for
 * availability and is carried here as the configured default.
 */
public final class ConfigDefaults {

    /**
     * Default Bedrock model id: the cross-region inference-profile form for the
     * newest Claude Opus. AC-8.3 / NFR-MODEL-DEFAULT / ADR-0001.
     *
     * <p>It is the {@code us.}-prefixed inference-profile id, not the bare model
     * id, because on-demand Converse for Opus requires an inference-profile id (the
     * bare {@code anthropic.claude-opus-4-8} fails with a 400 ValidationException).
     */
    public static final String MODEL_ID = "us.anthropic.claude-opus-4-8";

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
