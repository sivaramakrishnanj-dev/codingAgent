package com.srk.codingagent.config;

/**
 * The immutable configuration the agent runs against, produced once at startup by
 * a layered merge (CLI flags &gt; project config &gt; global config &gt; built-in
 * defaults) and validated before any model call (ADR-0009,
 * {@code 03-data-model.md} § 2.8). Every component reads this object; no component
 * reads the raw YAML files, so there is no scattered reading and no mid-run drift.
 *
 * <p>The field set and constraints mirror the resolved-config schema
 * ({@code 06-formal/resolved-config.schema.json}). Construct instances through
 * {@link ConfigResolver}, which enforces those constraints; the canonical
 * constructor validates the invariants the schema pins so an invalid
 * {@code ResolvedConfig} cannot be created.
 *
 * @param modelId                 the Bedrock model id; non-blank (schema:
 *                                {@code minLength 1}). Configured default is the
 *                                cross-region inference-profile form
 *                                {@code us.anthropic.claude-opus-4-8}
 *                                (NFR-MODEL-DEFAULT / ADR-0001).
 * @param permissionMode          the authorization mode; default
 *                                {@link PermissionMode#ASK_EVERY_TIME}
 *                                (NFR-PERMISSION-DEFAULT).
 * @param region                  the AWS region for Bedrock; default
 *                                {@code us-east-1}.
 * @param awsProfile              the named AWS profile, or {@code null} to use the
 *                                default credential chain (the bearer token is
 *                                never used — ADR-0011, INV-16).
 * @param subAgentMax             the maximum concurrent sub-agents; {@code >= 1}
 *                                (NFR-SUBAGENT-MAX), default {@code 1}.
 * @param summarizerModelId       an optional cheaper model for compaction
 *                                summaries (ADR-0006), or {@code null}.
 * @param commands                the named project commands (build/test/lint);
 *                                never {@code null} (use {@link Commands#empty()}).
 * @param contextCompactThreshold the context-utilization fraction that triggers
 *                                compaction; in {@code [0, 1]}, default
 *                                {@code 0.85}.
 * @param outputMaxInlineBytes    the maximum tool-output bytes inlined into
 *                                context; {@code >= 1}, default {@code 16384}.
 * @param verifyMaxIterations     the maximum verify-loop iterations; {@code >= 1},
 *                                default {@code 5}.
 * @param commandTimeoutSeconds   the per-command timeout in seconds; {@code >= 1},
 *                                default {@code 300}.
 * @param bedrockCallConnectTimeoutSeconds  the Bedrock-call connect timeout in
 *                                seconds (TCP/TLS establishment); {@code >= 1},
 *                                default {@code 10}. Wired to the Apache httpClient
 *                                {@code connectionTimeout} (NFR-BEDROCK-CALL-TIMEOUT,
 *                                ADR-0001).
 * @param bedrockCallResponseTimeoutSeconds the Bedrock-call overall-response timeout
 *                                in seconds (the end-to-end Converse budget, covers
 *                                streaming incl. extended thinking); {@code >= 1},
 *                                default {@code 300}. Wired to {@code apiCallTimeout}
 *                                and the Apache httpClient {@code socketTimeout}, and
 *                                counts toward the retry budget (NFR-BEDROCK-CALL-TIMEOUT,
 *                                ADR-0001).
 */
public record ResolvedConfig(
        String modelId,
        PermissionMode permissionMode,
        String region,
        String awsProfile,
        int subAgentMax,
        String summarizerModelId,
        Commands commands,
        double contextCompactThreshold,
        int outputMaxInlineBytes,
        int verifyMaxIterations,
        int commandTimeoutSeconds,
        int bedrockCallConnectTimeoutSeconds,
        int bedrockCallResponseTimeoutSeconds) {

    /**
     * Validates the schema-pinned invariants. A {@code ResolvedConfig} that
     * violates them cannot exist; {@link ConfigResolver} surfaces these as
     * {@link ConfigException}s naming the offending key before reaching here, but
     * the checks are duplicated as a defensive class invariant (EJ Item 17).
     *
     * @throws NullPointerException     if a non-nullable reference field is
     *                                  {@code null}.
     * @throws IllegalArgumentException if a numeric field is out of its schema
     *                                  range or {@code modelId}/{@code region} is
     *                                  blank.
     */
    public ResolvedConfig {
        requireNonBlank(modelId, "modelId");
        java.util.Objects.requireNonNull(permissionMode, "permissionMode");
        requireNonBlank(region, "region");
        java.util.Objects.requireNonNull(commands, "commands");
        requireAtLeast(subAgentMax, 1, "subAgentMax");
        requireInUnitInterval(contextCompactThreshold, "contextCompactThreshold");
        requireAtLeast(outputMaxInlineBytes, 1, "outputMaxInlineBytes");
        requireAtLeast(verifyMaxIterations, 1, "verifyMaxIterations");
        requireAtLeast(commandTimeoutSeconds, 1, "commandTimeoutSeconds");
        requireAtLeast(bedrockCallConnectTimeoutSeconds, 1, "bedrockCallConnectTimeoutSeconds");
        requireAtLeast(bedrockCallResponseTimeoutSeconds, 1, "bedrockCallResponseTimeoutSeconds");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }

    private static void requireAtLeast(int value, int min, String field) {
        if (value < min) {
            throw new IllegalArgumentException(field + " must be >= " + min + " (was " + value + ")");
        }
    }

    private static void requireInUnitInterval(double value, String field) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be in [0, 1] (was " + value + ")");
        }
    }

    /**
     * The named project commands a task may invoke (ADR-0003). Each field is the
     * shell command string, or {@code null} when unconfigured. Mirrors the
     * {@code commands} object in the resolved-config schema.
     *
     * @param build the build command, or {@code null}.
     * @param test  the test command, or {@code null}.
     * @param lint  the lint command, or {@code null}.
     */
    public record Commands(String build, String test, String lint) {

        private static final Commands EMPTY = new Commands(null, null, null);

        /**
         * Returns the empty command set (no build/test/lint configured).
         *
         * @return a shared {@code Commands} instance with all fields {@code null}.
         */
        public static Commands empty() {
            return EMPTY;
        }
    }
}
