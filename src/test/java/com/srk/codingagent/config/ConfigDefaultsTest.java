package com.srk.codingagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigDefaults} — the built-in (lowest-precedence) layer.
 *
 * <p>Each default is pinned by an NFR/AC, which is the oracle (NOT the constant's
 * declared literal):
 * <ul>
 *   <li>{@code MODEL_ID} &rarr; AC-8.3 / NFR-MODEL-DEFAULT / ADR-0001 (configured
 *       default is the cross-region inference-profile form
 *       {@code us.anthropic.claude-opus-4-8}, not the bare model id).</li>
 *   <li>{@code PERMISSION_MODE} &rarr; AC-8.4 / NFR-PERMISSION-DEFAULT
 *       ({@code ASK_EVERY_TIME}).</li>
 *   <li>{@code SUB_AGENT_MAX} &rarr; NFR-SUBAGENT-MAX (1).</li>
 *   <li>{@code CONTEXT_COMPACT_THRESHOLD} &rarr; NFR-CONTEXT-COMPACT-THRESHOLD (0.85).</li>
 *   <li>{@code OUTPUT_MAX_INLINE_BYTES} &rarr; NFR-OUTPUT-MAX-INLINE (16384).</li>
 *   <li>{@code VERIFY_MAX_ITERATIONS} &rarr; NFR-VERIFY-MAX-ITERATIONS (5).</li>
 *   <li>{@code COMMAND_TIMEOUT_SECONDS} &rarr; schema default (300).</li>
 *   <li>{@code BEDROCK_CALL_CONNECT_TIMEOUT_SECONDS} &rarr; NFR-BEDROCK-CALL-TIMEOUT /
 *       AC-8.11 / ADR-0001 (connect 10).</li>
 *   <li>{@code BEDROCK_CALL_RESPONSE_TIMEOUT_SECONDS} &rarr; NFR-BEDROCK-CALL-TIMEOUT /
 *       AC-8.11 / ADR-0001 (overall response 300).</li>
 * </ul>
 */
class ConfigDefaultsTest {

    @Test
    @DisplayName("default modelId is the pinned cross-region inference-profile id "
            + "(AC-8.3 / NFR-MODEL-DEFAULT / ADR-0001)")
    void modelId_isPinnedInferenceProfileDefault() {
        // Oracle: NFR-MODEL-DEFAULT, pinned by ADR-0001 (s Decision, "Default model")
        // to the cross-region inference-profile form us.anthropic.claude-opus-4-8
        // (preferred for availability; design-progress.md s6.A.3 names the us. form).
        assertEquals("us.anthropic.claude-opus-4-8", ConfigDefaults.MODEL_ID,
                "Default model id must be the pinned cross-region inference-profile id "
                        + "(AC-8.3 / ADR-0001)");
    }

    @Test
    @DisplayName("default modelId is an inference-profile form, not a bare on-demand id "
            + "(regression D1; NFR-MODEL-DEFAULT / ADR-0001)")
    void modelId_isInferenceProfileFormNotBareOnDemandId() {
        // Oracle: NFR-MODEL-DEFAULT is pinned by ADR-0001 to the CROSS-REGION
        // INFERENCE-PROFILE form. On-demand Converse for Opus rejects a bare model id:
        //   ValidationException "Invocation of model ID anthropic.claude-opus-4-8 with
        //   on-demand throughput isn't supported. Retry your request with the ID or ARN
        //   of an inference profile that contains this model." (HTTP 400, real Bedrock).
        // Shape (not literal) assertion so a future regression back to ANY bare
        // anthropic.* on-demand id fails here, regardless of the exact Opus version:
        //   - an inference-profile id is region/scope-prefixed (e.g. "us.", "global."),
        //   - a bare on-demand model id starts directly with "anthropic.".
        String modelId = ConfigDefaults.MODEL_ID;
        assertFalse(modelId.startsWith("anthropic."),
                "Default must NOT be a bare anthropic.* on-demand model id — on-demand "
                        + "Converse for Opus rejects it (ValidationException). ADR-0001 pins "
                        + "the cross-region inference-profile form.");
        assertTrue(modelId.matches("^[a-z]+\\..+"),
                "Default must be an inference-profile id, i.e. region/scope-prefixed "
                        + "(e.g. us.* or global.*) per NFR-MODEL-DEFAULT / ADR-0001");
        assertTrue(modelId.contains("anthropic.claude-opus"),
                "Default inference profile must still target Claude Opus (NFR-MODEL-DEFAULT)");
    }

    @Test
    @DisplayName("default permission mode is ASK_EVERY_TIME (AC-8.4 / NFR-PERMISSION-DEFAULT)")
    void permissionMode_isAskEveryTime() {
        // Oracle: NFR-PERMISSION-DEFAULT = ASK_EVERY_TIME (RD-3, AC-8.4).
        assertEquals(PermissionMode.ASK_EVERY_TIME, ConfigDefaults.PERMISSION_MODE,
                "Default permission mode must be ASK_EVERY_TIME (AC-8.4)");
    }

    @Test
    @DisplayName("default region is us-east-1 (resolved-config schema default)")
    void region_isUsEast1() {
        // Oracle: schema "region ... default us-east-1".
        assertEquals("us-east-1", ConfigDefaults.REGION,
                "Default region must be us-east-1 (schema default)");
    }

    @Test
    @DisplayName("default subAgentMax is 1 (NFR-SUBAGENT-MAX)")
    void subAgentMax_isOne() {
        // Oracle: NFR-SUBAGENT-MAX = 1.
        assertEquals(1, ConfigDefaults.SUB_AGENT_MAX,
                "Default subAgentMax must be 1 (NFR-SUBAGENT-MAX)");
    }

    @Test
    @DisplayName("default contextCompactThreshold is 0.85 (NFR-CONTEXT-COMPACT-THRESHOLD)")
    void contextCompactThreshold_isPoint85() {
        // Oracle: NFR-CONTEXT-COMPACT-THRESHOLD = 0.85.
        assertEquals(0.85, ConfigDefaults.CONTEXT_COMPACT_THRESHOLD,
                "Default contextCompactThreshold must be 0.85 (NFR-CONTEXT-COMPACT-THRESHOLD)");
    }

    @Test
    @DisplayName("default outputMaxInlineBytes is 16384 (NFR-OUTPUT-MAX-INLINE)")
    void outputMaxInlineBytes_is16384() {
        // Oracle: NFR-OUTPUT-MAX-INLINE = 16384 (16 KB).
        assertEquals(16384, ConfigDefaults.OUTPUT_MAX_INLINE_BYTES,
                "Default outputMaxInlineBytes must be 16384 (NFR-OUTPUT-MAX-INLINE)");
    }

    @Test
    @DisplayName("default verifyMaxIterations is 5 (NFR-VERIFY-MAX-ITERATIONS)")
    void verifyMaxIterations_isFive() {
        // Oracle: NFR-VERIFY-MAX-ITERATIONS = 5.
        assertEquals(5, ConfigDefaults.VERIFY_MAX_ITERATIONS,
                "Default verifyMaxIterations must be 5 (NFR-VERIFY-MAX-ITERATIONS)");
    }

    @Test
    @DisplayName("default commandTimeoutSeconds is 300 (resolved-config schema default)")
    void commandTimeoutSeconds_is300() {
        // Oracle: schema "commandTimeoutSeconds ... default 300".
        assertEquals(300, ConfigDefaults.COMMAND_TIMEOUT_SECONDS,
                "Default commandTimeoutSeconds must be 300 (schema default)");
    }

    @Test
    @DisplayName("default bedrockCallConnectTimeoutSeconds is 10 "
            + "(NFR-BEDROCK-CALL-TIMEOUT / AC-8.11 / ADR-0001)")
    void bedrockCallConnectTimeoutSeconds_is10() {
        // Oracle: NFR-BEDROCK-CALL-TIMEOUT pins connect 10 s, folded into the config-key
        // set as the AC-8.11 default-when-absent; schema default 10.
        assertEquals(10, ConfigDefaults.BEDROCK_CALL_CONNECT_TIMEOUT_SECONDS,
                "Default bedrockCallConnectTimeoutSeconds must be 10 (NFR-BEDROCK-CALL-TIMEOUT / "
                        + "AC-8.11)");
    }

    @Test
    @DisplayName("default bedrockCallResponseTimeoutSeconds is 300 "
            + "(NFR-BEDROCK-CALL-TIMEOUT / AC-8.11 / ADR-0001)")
    void bedrockCallResponseTimeoutSeconds_is300() {
        // Oracle: NFR-BEDROCK-CALL-TIMEOUT pins overall response 300 s, folded into the
        // config-key set as the AC-8.11 default-when-absent; schema default 300.
        assertEquals(300, ConfigDefaults.BEDROCK_CALL_RESPONSE_TIMEOUT_SECONDS,
                "Default bedrockCallResponseTimeoutSeconds must be 300 (NFR-BEDROCK-CALL-TIMEOUT / "
                        + "AC-8.11)");
    }
}
