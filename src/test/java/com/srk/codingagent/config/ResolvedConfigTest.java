package com.srk.codingagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResolvedConfig} and its nested {@link ResolvedConfig.Commands}.
 *
 * <p>Oracle: the resolved-config schema ({@code 06-formal/resolved-config.schema.json}).
 * The compact constructor enforces the schema's invariants so an invalid
 * {@code ResolvedConfig} cannot exist (ADR-0009: "one immutable resolved object"). The
 * schema pins: {@code modelId} {@code minLength 1}; {@code permissionMode} required
 * (non-null); {@code region} required; {@code subAgentMax} {@code minimum 1};
 * {@code contextCompactThreshold} in {@code [0,1]}; {@code outputMaxInlineBytes},
 * {@code verifyMaxIterations}, {@code commandTimeoutSeconds},
 * {@code bedrockCallConnectTimeoutSeconds}, {@code bedrockCallResponseTimeoutSeconds}
 * {@code minimum 1} (the last two added by DCR-4 — NFR-BEDROCK-CALL-TIMEOUT, CT-SCH-16).
 * Each negative test derives its expectation from a schema constraint, not from the
 * constructor's observed throw.
 */
class ResolvedConfigTest {

    /** A schema-valid construction used as the baseline for single-field mutations. */
    private static ResolvedConfig valid(
            String modelId,
            PermissionMode mode,
            String region,
            int subAgentMax,
            double threshold,
            int outBytes,
            int verifyIters,
            int timeout) {
        return new ResolvedConfig(modelId, mode, region, null, subAgentMax, null,
                ResolvedConfig.Commands.empty(), threshold, outBytes, verifyIters, timeout,
                10, 300);
    }

    @Test
    @DisplayName("a fully schema-valid config constructs and exposes its components")
    void validConfig_constructsAndExposesFields() {
        // Oracle: all schema constraints satisfied -> construction succeeds.
        ResolvedConfig cfg = new ResolvedConfig(
                "anthropic.claude-opus-4-8", PermissionMode.ASK_EVERY_TIME, "us-east-1",
                "my-profile", 1, "anthropic.claude-haiku",
                new ResolvedConfig.Commands("mvn verify", "mvn test", "checkstyle"),
                0.85, 16384, 5, 300, 10, 300);

        assertEquals("anthropic.claude-opus-4-8", cfg.modelId());
        assertEquals(PermissionMode.ASK_EVERY_TIME, cfg.permissionMode());
        assertEquals("us-east-1", cfg.region());
        assertEquals("my-profile", cfg.awsProfile());
        assertEquals(1, cfg.subAgentMax());
        assertEquals("anthropic.claude-haiku", cfg.summarizerModelId());
        assertEquals("mvn verify", cfg.commands().build());
        assertEquals(0.85, cfg.contextCompactThreshold());
        assertEquals(16384, cfg.outputMaxInlineBytes());
        assertEquals(5, cfg.verifyMaxIterations());
        assertEquals(300, cfg.commandTimeoutSeconds());
        assertEquals(10, cfg.bedrockCallConnectTimeoutSeconds());
        assertEquals(300, cfg.bedrockCallResponseTimeoutSeconds());
    }

    @Test
    @DisplayName("awsProfile and summarizerModelId may be null (schema type [string, null])")
    void nullableFields_acceptNull() {
        // Oracle: schema marks awsProfile and summarizerModelId as type [string, null].
        ResolvedConfig cfg = valid("m", PermissionMode.READ_ONLY, "us-east-1",
                1, 0.5, 1, 1, 1);

        assertNull(cfg.awsProfile(), "awsProfile null is schema-legal (default credential chain)");
        assertNull(cfg.summarizerModelId(), "summarizerModelId null is schema-legal (optional)");
    }

    @Nested
    @DisplayName("schema invariant: modelId minLength 1")
    class ModelIdConstraint {

        @Test
        @DisplayName("blank modelId is rejected (schema: minLength 1)")
        void blankModelId_rejected() {
            // Oracle: schema modelId minLength 1 -> empty/blank is invalid.
            assertThrows(IllegalArgumentException.class,
                    () -> valid("   ", PermissionMode.ASK_EVERY_TIME, "us-east-1",
                            1, 0.85, 1, 1, 1),
                    "Blank modelId violates schema minLength 1");
        }

        @Test
        @DisplayName("null modelId is rejected (schema: required, minLength 1)")
        void nullModelId_rejected() {
            // Oracle: schema requires modelId.
            assertThrows(IllegalArgumentException.class,
                    () -> valid(null, PermissionMode.ASK_EVERY_TIME, "us-east-1",
                            1, 0.85, 1, 1, 1),
                    "Null modelId violates schema required/minLength");
        }
    }

    @Test
    @DisplayName("null permissionMode is rejected (schema: required)")
    void nullPermissionMode_rejected() {
        // Oracle: schema "required" includes permissionMode.
        assertThrows(NullPointerException.class,
                () -> valid("m", null, "us-east-1", 1, 0.85, 1, 1, 1),
                "Null permissionMode violates schema required");
    }

    @Test
    @DisplayName("blank region is rejected (schema: required region)")
    void blankRegion_rejected() {
        // Oracle: schema "required" includes region.
        assertThrows(IllegalArgumentException.class,
                () -> valid("m", PermissionMode.ASK_EVERY_TIME, "", 1, 0.85, 1, 1, 1),
                "Blank region violates schema required region");
    }

    @Test
    @DisplayName("null commands is rejected (resolver always supplies Commands.empty())")
    void nullCommands_rejected() {
        // Oracle: ResolvedConfig contract — commands is never null (ADR-0009 immutable
        // resolved object); resolver supplies Commands.empty() when unconfigured.
        assertThrows(NullPointerException.class,
                () -> new ResolvedConfig("m", PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                        1, null, null, 0.85, 1, 1, 1, 10, 300),
                "Null commands is not a valid resolved object");
    }

    @Test
    @DisplayName("subAgentMax below 1 is rejected (schema: minimum 1)")
    void subAgentMaxBelowOne_rejected() {
        // Oracle: schema subAgentMax minimum 1.
        assertThrows(IllegalArgumentException.class,
                () -> valid("m", PermissionMode.ASK_EVERY_TIME, "us-east-1", 0, 0.85, 1, 1, 1),
                "subAgentMax 0 violates schema minimum 1");
    }

    @Test
    @DisplayName("contextCompactThreshold above 1 is rejected (schema: maximum 1)")
    void thresholdAboveOne_rejected() {
        // Oracle: schema contextCompactThreshold maximum 1.
        assertThrows(IllegalArgumentException.class,
                () -> valid("m", PermissionMode.ASK_EVERY_TIME, "us-east-1", 1, 1.5, 1, 1, 1),
                "threshold 1.5 violates schema maximum 1");
    }

    @Test
    @DisplayName("contextCompactThreshold below 0 is rejected (schema: minimum 0)")
    void thresholdBelowZero_rejected() {
        // Oracle: schema contextCompactThreshold minimum 0.
        assertThrows(IllegalArgumentException.class,
                () -> valid("m", PermissionMode.ASK_EVERY_TIME, "us-east-1", 1, -0.1, 1, 1, 1),
                "threshold -0.1 violates schema minimum 0");
    }

    @Test
    @DisplayName("outputMaxInlineBytes below 1 is rejected (schema: minimum 1)")
    void outputMaxInlineBytesBelowOne_rejected() {
        // Oracle: schema outputMaxInlineBytes minimum 1.
        assertThrows(IllegalArgumentException.class,
                () -> valid("m", PermissionMode.ASK_EVERY_TIME, "us-east-1", 1, 0.85, 0, 1, 1),
                "outputMaxInlineBytes 0 violates schema minimum 1");
    }

    @Test
    @DisplayName("verifyMaxIterations below 1 is rejected (schema: minimum 1)")
    void verifyMaxIterationsBelowOne_rejected() {
        // Oracle: schema verifyMaxIterations minimum 1.
        assertThrows(IllegalArgumentException.class,
                () -> valid("m", PermissionMode.ASK_EVERY_TIME, "us-east-1", 1, 0.85, 1, 0, 1),
                "verifyMaxIterations 0 violates schema minimum 1");
    }

    @Test
    @DisplayName("commandTimeoutSeconds below 1 is rejected (schema: minimum 1)")
    void commandTimeoutSecondsBelowOne_rejected() {
        // Oracle: schema commandTimeoutSeconds minimum 1.
        assertThrows(IllegalArgumentException.class,
                () -> valid("m", PermissionMode.ASK_EVERY_TIME, "us-east-1", 1, 0.85, 1, 1, 0),
                "commandTimeoutSeconds 0 violates schema minimum 1");
    }

    @Test
    @DisplayName("CT-SCH-16: a config with both Bedrock-timeout keys (integers >= 1) validates "
            + "(NFR-BEDROCK-CALL-TIMEOUT, AC-8.10)")
    void ct_sch_16_bedrockTimeoutKeys_validate() {
        // Oracle: CT-SCH-16 (+) — a config carrying bedrockCallConnectTimeoutSeconds and
        // bedrockCallResponseTimeoutSeconds as integers >= 1 validates. Schema integers
        // >= 1 are also exercised at the record level (the compact constructor pins the
        // same minimum-1 invariant the schema declares). 1 is the inclusive lower bound.
        ResolvedConfig cfg = new ResolvedConfig("m", PermissionMode.ASK_EVERY_TIME, "us-east-1",
                null, 1, null, ResolvedConfig.Commands.empty(), 0.85, 1, 1, 1, 1, 1);

        assertEquals(1, cfg.bedrockCallConnectTimeoutSeconds(),
                "an integer >= 1 connect timeout must validate (CT-SCH-16)");
        assertEquals(1, cfg.bedrockCallResponseTimeoutSeconds(),
                "an integer >= 1 response timeout must validate (CT-SCH-16)");
    }

    @Test
    @DisplayName("bedrockCallConnectTimeoutSeconds below 1 is rejected (schema: minimum 1, DCR-4)")
    void bedrockCallConnectTimeoutSecondsBelowOne_rejected() {
        // Oracle: schema bedrockCallConnectTimeoutSeconds minimum 1 (NFR-BEDROCK-CALL-TIMEOUT).
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedConfig("m", PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                        1, null, ResolvedConfig.Commands.empty(), 0.85, 1, 1, 1, 0, 300),
                "bedrockCallConnectTimeoutSeconds 0 violates schema minimum 1");
    }

    @Test
    @DisplayName("bedrockCallResponseTimeoutSeconds below 1 is rejected (schema: minimum 1, DCR-4)")
    void bedrockCallResponseTimeoutSecondsBelowOne_rejected() {
        // Oracle: schema bedrockCallResponseTimeoutSeconds minimum 1 (NFR-BEDROCK-CALL-TIMEOUT).
        assertThrows(IllegalArgumentException.class,
                () -> new ResolvedConfig("m", PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                        1, null, ResolvedConfig.Commands.empty(), 0.85, 1, 1, 1, 10, 0),
                "bedrockCallResponseTimeoutSeconds 0 violates schema minimum 1");
    }

    @Test
    @DisplayName("threshold at the inclusive bounds 0 and 1 is accepted (schema: [0,1] inclusive)")
    void thresholdAtBounds_accepted() {
        // Oracle: schema minimum 0, maximum 1 are inclusive bounds.
        assertNotNull(valid("m", PermissionMode.UNRESTRICTED, "us-east-1", 1, 0.0, 1, 1, 1));
        assertNotNull(valid("m", PermissionMode.UNRESTRICTED, "us-east-1", 1, 1.0, 1, 1, 1));
    }

    @Nested
    @DisplayName("Commands value object")
    class CommandsTests {

        @Test
        @DisplayName("Commands.empty() has all-null build/test/lint (schema commands all optional)")
        void empty_hasAllNullFields() {
            // Oracle: schema commands object has no required properties -> empty is valid.
            ResolvedConfig.Commands empty = ResolvedConfig.Commands.empty();

            assertNull(empty.build(), "empty().build() must be null");
            assertNull(empty.test(), "empty().test() must be null");
            assertNull(empty.lint(), "empty().lint() must be null");
        }

        @Test
        @DisplayName("Commands.empty() returns a shared instance")
        void empty_isShared() {
            // empty() is a flyweight; same instance each call (no spec value, shape check).
            assertSame(ResolvedConfig.Commands.empty(), ResolvedConfig.Commands.empty(),
                    "empty() should return a shared instance");
        }

        @Test
        @DisplayName("Commands carries configured build/test/lint strings (AC-8.1)")
        void commands_carryConfiguredStrings() {
            // Oracle: AC-8.1 — project build/test commands are configurable.
            ResolvedConfig.Commands cmds = new ResolvedConfig.Commands("b", "t", "l");

            assertEquals("b", cmds.build());
            assertEquals("t", cmds.test());
            assertEquals("l", cmds.lint());
        }
    }
}
