package com.srk.codingagent.config;

import java.util.Set;

/**
 * The recognized configuration keys, matching the property names of the
 * resolved-config schema ({@code 06-formal/resolved-config.schema.json}). The
 * schema declares {@code additionalProperties: false}, so any key in a config
 * file that is not listed here is an error per ADR-0009 ("unknown keys are an
 * error, not silently ignored — catches typos early").
 *
 * <p>This holder is the single source of truth for the allowed top-level key
 * vocabulary and for the nested {@code commands} key vocabulary, shared by the
 * loader (unknown-key rejection) and the resolver (binding).
 */
public final class ConfigKeys {

    /** {@code modelId} — the Bedrock model id. */
    public static final String MODEL_ID = "modelId";

    /** {@code permissionMode} — the authorization mode. */
    public static final String PERMISSION_MODE = "permissionMode";

    /** {@code region} — the AWS region for Bedrock. */
    public static final String REGION = "region";

    /** {@code awsProfile} — the named AWS profile (or null for the default chain). */
    public static final String AWS_PROFILE = "awsProfile";

    /** {@code subAgentMax} — the maximum concurrent sub-agents. */
    public static final String SUB_AGENT_MAX = "subAgentMax";

    /** {@code summarizerModelId} — the optional compaction-summary model. */
    public static final String SUMMARIZER_MODEL_ID = "summarizerModelId";

    /** {@code commands} — the named project commands object. */
    public static final String COMMANDS = "commands";

    /** {@code contextCompactThreshold} — the compaction-trigger fraction. */
    public static final String CONTEXT_COMPACT_THRESHOLD = "contextCompactThreshold";

    /** {@code outputMaxInlineBytes} — the max inlined tool-output bytes. */
    public static final String OUTPUT_MAX_INLINE_BYTES = "outputMaxInlineBytes";

    /** {@code verifyMaxIterations} — the max verify-loop iterations. */
    public static final String VERIFY_MAX_ITERATIONS = "verifyMaxIterations";

    /** {@code commandTimeoutSeconds} — the per-command timeout in seconds. */
    public static final String COMMAND_TIMEOUT_SECONDS = "commandTimeoutSeconds";

    /**
     * {@code bedrockCallConnectTimeoutSeconds} — the Bedrock-call connect timeout in
     * seconds (NFR-BEDROCK-CALL-TIMEOUT; wired to the Apache httpClient
     * connectionTimeout, ADR-0001).
     */
    public static final String BEDROCK_CALL_CONNECT_TIMEOUT_SECONDS = "bedrockCallConnectTimeoutSeconds";

    /**
     * {@code bedrockCallResponseTimeoutSeconds} — the Bedrock-call overall-response
     * timeout in seconds (NFR-BEDROCK-CALL-TIMEOUT; wired to {@code apiCallTimeout} and
     * the Apache httpClient socketTimeout, ADR-0001; counts toward the retry budget).
     */
    public static final String BEDROCK_CALL_RESPONSE_TIMEOUT_SECONDS = "bedrockCallResponseTimeoutSeconds";

    /** {@code commands.build} — the build command. */
    public static final String COMMANDS_BUILD = "build";

    /** {@code commands.test} — the test command. */
    public static final String COMMANDS_TEST = "test";

    /** {@code commands.lint} — the lint command. */
    public static final String COMMANDS_LINT = "lint";

    /** All recognized top-level keys; any other top-level key is an error. */
    public static final Set<String> TOP_LEVEL = Set.of(
            MODEL_ID,
            PERMISSION_MODE,
            REGION,
            AWS_PROFILE,
            SUB_AGENT_MAX,
            SUMMARIZER_MODEL_ID,
            COMMANDS,
            CONTEXT_COMPACT_THRESHOLD,
            OUTPUT_MAX_INLINE_BYTES,
            VERIFY_MAX_ITERATIONS,
            COMMAND_TIMEOUT_SECONDS,
            BEDROCK_CALL_CONNECT_TIMEOUT_SECONDS,
            BEDROCK_CALL_RESPONSE_TIMEOUT_SECONDS);

    /** All recognized keys inside the {@code commands} object. */
    public static final Set<String> COMMANDS_KEYS = Set.of(
            COMMANDS_BUILD,
            COMMANDS_TEST,
            COMMANDS_LINT);

    private ConfigKeys() {
        // Constants holder; not instantiable.
    }
}
