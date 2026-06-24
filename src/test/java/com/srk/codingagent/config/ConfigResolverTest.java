package com.srk.codingagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ConfigResolver} — the layered merge + bind + validate that
 * produces the immutable {@link ResolvedConfig}.
 *
 * <p>Oracles (each test derives its expectation from the cited spec symbol, never
 * from the resolver's observed behavior):
 * <ul>
 *   <li><b>AC-8.2</b> — precedence CLI flags &gt; project &gt; global &gt; built-in
 *       defaults, first-wins per key.</li>
 *   <li><b>AC-8.3</b> — no model configured &rarr; NFR-MODEL-DEFAULT, pinned by
 *       ADR-0001 to the cross-region inference-profile form
 *       ({@code us.anthropic.claude-opus-4-8}).</li>
 *   <li><b>AC-8.4</b> — no permission mode configured &rarr; NFR-PERMISSION-DEFAULT
 *       ({@code ASK_EVERY_TIME}).</li>
 *   <li><b>AC-8.5</b> — a malformed value (unknown mode, non-integer, out-of-range)
 *       is rejected with a {@link ConfigException} naming the offending key.</li>
 *   <li><b>AC-8.1</b> — model/mode/commands are configurable.</li>
 * </ul>
 * The SUT (a real {@link ConfigResolver}) is never mocked.
 */
class ConfigResolverTest {

    private final ConfigResolver resolver = new ConfigResolver();

    @Nested
    @DisplayName("AC-8.3 / AC-8.4: defaults when keys are absent")
    class Defaults {

        @Test
        @DisplayName("all layers empty -> every field is the built-in default (AC-8.3, AC-8.4)")
        void allEmpty_yieldsBuiltInDefaults() {
            // Oracle: AC-8.3 NFR-MODEL-DEFAULT (pinned by ADR-0001 to the cross-region
            // inference-profile form us.anthropic.claude-opus-4-8), AC-8.4
            // NFR-PERMISSION-DEFAULT, plus the schema/NFR defaults for the remaining fields.
            ResolvedConfig cfg = resolver.resolve(Map.of(), Map.of(), Map.of());

            assertEquals("us.anthropic.claude-opus-4-8", cfg.modelId(),
                    "no model configured -> NFR-MODEL-DEFAULT (AC-8.3 / ADR-0001)");
            assertEquals(PermissionMode.ASK_EVERY_TIME, cfg.permissionMode(),
                    "no permission mode configured -> ASK_EVERY_TIME (AC-8.4)");
            assertEquals("us-east-1", cfg.region(), "default region us-east-1 (schema)");
            assertNull(cfg.awsProfile(), "no profile -> null (default credential chain)");
            assertEquals(1, cfg.subAgentMax(), "NFR-SUBAGENT-MAX default 1");
            assertNull(cfg.summarizerModelId(), "no summarizer -> null (optional)");
            assertEquals(0.85, cfg.contextCompactThreshold(),
                    "NFR-CONTEXT-COMPACT-THRESHOLD default 0.85");
            assertEquals(16384, cfg.outputMaxInlineBytes(), "NFR-OUTPUT-MAX-INLINE default 16384");
            assertEquals(5, cfg.verifyMaxIterations(), "NFR-VERIFY-MAX-ITERATIONS default 5");
            assertEquals(300, cfg.commandTimeoutSeconds(), "schema default 300");
            assertEquals(10, cfg.bedrockCallConnectTimeoutSeconds(),
                    "NFR-BEDROCK-CALL-TIMEOUT connect default 10 (AC-8.11)");
            assertEquals(300, cfg.bedrockCallResponseTimeoutSeconds(),
                    "NFR-BEDROCK-CALL-TIMEOUT response default 300 (AC-8.11)");
        }

        @Test
        @DisplayName("commands default to the empty set when unconfigured (AC-8.1)")
        void noCommands_yieldsEmptyCommandSet() {
            // Oracle: AC-8.1 commands are optional/configurable; absent -> empty set.
            ResolvedConfig cfg = resolver.resolve(Map.of(), Map.of(), Map.of());

            assertNull(cfg.commands().build(), "unconfigured build command is null");
            assertNull(cfg.commands().test(), "unconfigured test command is null");
            assertNull(cfg.commands().lint(), "unconfigured lint command is null");
        }
    }

    @Nested
    @DisplayName("DCR-4: Bedrock call-timeout keys (NFR-BEDROCK-CALL-TIMEOUT, AC-8.10/8.11)")
    class BedrockCallTimeouts {

        @Test
        @DisplayName("CT-SCH-17: both timeout keys absent -> resolver applies connect 10 / "
                + "response 300 (AC-8.11)")
        void ct_sch_17_bothAbsent_resolverAppliesDefaults() {
            // Oracle: CT-SCH-17 (+) — JSON-Schema `default` keywords are documentary
            // (validators don't inject them); the resolver (C17, ADR-0009) is what applies
            // the NFR-BEDROCK-CALL-TIMEOUT defaults when the keys are absent. AC-8.11 pins
            // connect 10 / response 300.
            ResolvedConfig cfg = resolver.resolve(Map.of(), Map.of(), Map.of());

            assertEquals(10, cfg.bedrockCallConnectTimeoutSeconds(),
                    "absent connect key -> NFR default 10 (AC-8.11, CT-SCH-17)");
            assertEquals(300, cfg.bedrockCallResponseTimeoutSeconds(),
                    "absent response key -> NFR default 300 (AC-8.11, CT-SCH-17)");
        }

        @Test
        @DisplayName("a configured connect timeout binds, overriding the default (AC-8.10)")
        void configuredConnectTimeout_binds() {
            // Oracle: AC-8.10 — the connect timeout is configurable via
            // bedrockCallConnectTimeoutSeconds. A configured value must override the default.
            ResolvedConfig cfg = resolver.resolve(
                    Map.of(), Map.of("bedrockCallConnectTimeoutSeconds", 25), Map.of());

            assertEquals(25, cfg.bedrockCallConnectTimeoutSeconds(),
                    "a configured connect timeout must bind (AC-8.10)");
            assertEquals(300, cfg.bedrockCallResponseTimeoutSeconds(),
                    "the unset response timeout still falls through to the NFR default (AC-8.11)");
        }

        @Test
        @DisplayName("a configured response timeout binds, overriding the default (AC-8.10)")
        void configuredResponseTimeout_binds() {
            // Oracle: AC-8.10 — the overall-response timeout is configurable via
            // bedrockCallResponseTimeoutSeconds. A configured value must override the default.
            ResolvedConfig cfg = resolver.resolve(
                    Map.of(), Map.of("bedrockCallResponseTimeoutSeconds", 600), Map.of());

            assertEquals(600, cfg.bedrockCallResponseTimeoutSeconds(),
                    "a configured response timeout must bind (AC-8.10)");
            assertEquals(10, cfg.bedrockCallConnectTimeoutSeconds(),
                    "the unset connect timeout still falls through to the NFR default (AC-8.11)");
        }

        @Test
        @DisplayName("a connect timeout below the schema minimum is rejected naming the key (AC-8.5)")
        void outOfRangeConnectTimeout_rejectedNamingKey() {
            // Oracle: schema bedrockCallConnectTimeoutSeconds minimum 1; below-min is
            // malformed and rejected naming the offending key (AC-8.5).
            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(
                            Map.of(), Map.of("bedrockCallConnectTimeoutSeconds", 0), Map.of()),
                    "a connect timeout below the schema minimum must be rejected (AC-8.5)");

            assertEquals("bedrockCallConnectTimeoutSeconds", ex.key(),
                    "the exception must name the offending key (AC-8.5)");
        }

        @Test
        @DisplayName("a response timeout below the schema minimum is rejected naming the key (AC-8.5)")
        void outOfRangeResponseTimeout_rejectedNamingKey() {
            // Oracle: schema bedrockCallResponseTimeoutSeconds minimum 1; below-min is
            // malformed and rejected naming the offending key (AC-8.5).
            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(
                            Map.of(), Map.of("bedrockCallResponseTimeoutSeconds", 0), Map.of()),
                    "a response timeout below the schema minimum must be rejected (AC-8.5)");

            assertEquals("bedrockCallResponseTimeoutSeconds", ex.key(),
                    "the exception must name the offending key (AC-8.5)");
        }
    }

    @Nested
    @DisplayName("AC-8.2: layered precedence (flags > project > global > defaults), first-wins")
    class Precedence {

        @Test
        @DisplayName("a flag value overrides project, global, and default for the same key (AC-8.2)")
        void flagWins_overEveryLowerLayer() {
            // Oracle: AC-8.2 — CLI flags are highest precedence.
            Map<String, Object> flags = Map.of("modelId", "from-flag");
            Map<String, Object> project = Map.of("modelId", "from-project");
            Map<String, Object> global = Map.of("modelId", "from-global");

            ResolvedConfig cfg = resolver.resolve(flags, project, global);

            assertEquals("from-flag", cfg.modelId(),
                    "the flag layer must win over project, global, and default (AC-8.2)");
        }

        @Test
        @DisplayName("project value overrides global and default when no flag is present (AC-8.2)")
        void projectWins_overGlobalAndDefault() {
            // Oracle: AC-8.2 — project config outranks global config.
            Map<String, Object> project = Map.of("modelId", "from-project");
            Map<String, Object> global = Map.of("modelId", "from-global");

            ResolvedConfig cfg = resolver.resolve(Map.of(), project, global);

            assertEquals("from-project", cfg.modelId(),
                    "the project layer must win over global and default (AC-8.2)");
        }

        @Test
        @DisplayName("global value overrides only the default when no flag/project is present (AC-8.2)")
        void globalWins_overDefaultOnly() {
            // Oracle: AC-8.2 — global config outranks only the built-in default.
            Map<String, Object> global = Map.of("modelId", "from-global");

            ResolvedConfig cfg = resolver.resolve(Map.of(), Map.of(), global);

            assertEquals("from-global", cfg.modelId(),
                    "the global layer must win over the built-in default (AC-8.2)");
        }

        @Test
        @DisplayName("precedence is per-key: each key resolves from its own highest layer (AC-8.2)")
        void precedenceIsPerKey() {
            // Oracle: AC-8.2 "first-wins per key" — different keys resolve from
            // different layers independently.
            Map<String, Object> flags = Map.of("modelId", "flag-model");
            Map<String, Object> project = Map.of("region", "project-region");
            Map<String, Object> global = Map.of("permissionMode", "READ_ONLY");

            ResolvedConfig cfg = resolver.resolve(flags, project, global);

            assertEquals("flag-model", cfg.modelId(), "modelId from the flag layer");
            assertEquals("project-region", cfg.region(), "region from the project layer");
            assertEquals(PermissionMode.READ_ONLY, cfg.permissionMode(),
                    "permissionMode from the global layer");
            assertEquals(1, cfg.subAgentMax(), "subAgentMax falls through to the default");
        }

        @Test
        @DisplayName("a higher layer setting one key does not mask a lower layer's other keys (AC-8.2)")
        void higherLayerDoesNotMaskUnsetKeys() {
            // Oracle: AC-8.2 first-wins is per-key, so a flag for modelId must not
            // suppress a global region.
            Map<String, Object> flags = Map.of("modelId", "flag-model");
            Map<String, Object> global = Map.of("region", "eu-central-1");

            ResolvedConfig cfg = resolver.resolve(flags, Map.of(), global);

            assertEquals("flag-model", cfg.modelId());
            assertEquals("eu-central-1", cfg.region(),
                    "the global region survives even though the flag set a different key");
        }
    }

    @Nested
    @DisplayName("AC-8.1: configurable values bind through")
    class ConfigurableValues {

        @Test
        @DisplayName("model id, permission mode, and build/test commands are configurable (AC-8.1)")
        void modelModeCommands_areConfigurable() {
            // Oracle: AC-8.1 — these are the values the requirement says must be
            // configurable.
            Map<String, Object> project = Map.of(
                    "modelId", "configured-model",
                    "permissionMode", "UNRESTRICTED",
                    "commands", Map.of("build", "make", "test", "make test"));

            ResolvedConfig cfg = resolver.resolve(Map.of(), project, Map.of());

            assertEquals("configured-model", cfg.modelId());
            assertEquals(PermissionMode.UNRESTRICTED, cfg.permissionMode());
            assertEquals("make", cfg.commands().build());
            assertEquals("make test", cfg.commands().test());
        }

        @Test
        @DisplayName("each permission-mode name binds to its enum constant (schema enum)")
        void everyPermissionModeName_binds() {
            // Oracle: the schema's permissionMode enum — every legal name must bind.
            for (PermissionMode mode : PermissionMode.values()) {
                ResolvedConfig cfg = resolver.resolve(
                        Map.of(), Map.of("permissionMode", mode.name()), Map.of());
                assertEquals(mode, cfg.permissionMode(),
                        "permission mode name '" + mode.name() + "' must bind to its constant");
            }
        }
    }

    @Nested
    @DisplayName("AC-8.5: malformed values are rejected, naming the offending key")
    class MalformedValues {

        @Test
        @DisplayName("an unknown permission mode is rejected naming permissionMode (AC-8.5)")
        void unknownPermissionMode_rejectedNamingKey() {
            // Oracle: AC-8.5 "unknown mode" -> exit 2 identifying the offending key.
            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(
                            Map.of(), Map.of("permissionMode", "YOLO"), Map.of()),
                    "an unknown permission mode must be rejected (AC-8.5)");

            assertEquals("permissionMode", ex.key(),
                    "the exception must name the offending key permissionMode (AC-8.5)");
            assertTrue(ex.getMessage().contains("permissionMode"),
                    "the message must embed the offending key for the stderr line");
        }

        @Test
        @DisplayName("a non-integer subAgentMax is rejected naming subAgentMax (AC-8.5)")
        void nonIntegerSubAgentMax_rejectedNamingKey() {
            // Oracle: AC-8.5 "unparseable" value -> exit 2 identifying the key. The
            // schema types subAgentMax as integer.
            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(
                            Map.of(), Map.of("subAgentMax", "not-a-number"), Map.of()),
                    "a non-integer subAgentMax must be rejected (AC-8.5)");

            assertEquals("subAgentMax", ex.key());
        }

        @Test
        @DisplayName("a subAgentMax below the schema minimum is rejected naming the key (AC-8.5)")
        void outOfRangeSubAgentMax_rejectedNamingKey() {
            // Oracle: schema subAgentMax minimum 1; out-of-range is malformed (AC-8.5).
            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(Map.of(), Map.of("subAgentMax", 0), Map.of()),
                    "subAgentMax below the schema minimum must be rejected (AC-8.5)");

            assertEquals("subAgentMax", ex.key());
        }

        @Test
        @DisplayName("an out-of-range contextCompactThreshold is rejected naming the key (AC-8.5)")
        void outOfRangeThreshold_rejectedNamingKey() {
            // Oracle: schema contextCompactThreshold in [0,1]; above 1 is malformed.
            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(
                            Map.of(), Map.of("contextCompactThreshold", 1.5), Map.of()),
                    "a threshold above the schema maximum must be rejected (AC-8.5)");

            assertEquals("contextCompactThreshold", ex.key());
        }

        @Test
        @DisplayName("a non-string modelId is rejected naming modelId (AC-8.5)")
        void nonStringModelId_rejectedNamingKey() {
            // Oracle: schema modelId type string; a non-string is malformed (AC-8.5).
            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(Map.of(), Map.of("modelId", 123), Map.of()),
                    "a non-string modelId must be rejected (AC-8.5)");

            assertEquals("modelId", ex.key());
        }

        @Test
        @DisplayName("a commands value that is not a mapping is rejected naming commands (AC-8.5)")
        void nonMappingCommands_rejectedNamingKey() {
            // Oracle: schema commands type object; a scalar is malformed (AC-8.5).
            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(
                            Map.of(), Map.of("commands", "not-a-map"), Map.of()),
                    "a non-mapping commands value must be rejected (AC-8.5)");

            assertEquals("commands", ex.key());
        }

        @Test
        @DisplayName("a non-string commands.build is rejected naming commands.build (AC-8.5)")
        void nonStringCommandValue_rejectedNamingQualifiedKey() {
            // Oracle: schema commands.build type string; AC-8.5 names the offending key.
            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(
                            Map.of(), Map.of("commands", Map.of("build", 42)), Map.of()),
                    "a non-string commands.build must be rejected (AC-8.5)");

            assertEquals("commands.build", ex.key());
        }

        @Test
        @DisplayName("an integer beyond int range (a YAML Long) is rejected naming the key (AC-8.5)")
        void integerBeyondIntRange_rejectedNamingKey() {
            // Oracle: schema commandTimeoutSeconds type integer with the resolved range
            // ceiling at Integer.MAX_VALUE; a YAML scalar larger than int (surfaced by
            // SnakeYAML as Long) is out of range and malformed (AC-8.5).
            long tooBig = (long) Integer.MAX_VALUE + 1L;

            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(
                            Map.of(), Map.of("commandTimeoutSeconds", tooBig), Map.of()),
                    "an integer beyond int range must be rejected (AC-8.5)");

            assertEquals("commandTimeoutSeconds", ex.key());
        }
    }

    @Test
    @DisplayName("a YAML Long that fits int binds (SnakeYAML surfaces integral scalars as Long)")
    void longThatFitsInt_binds() {
        // Oracle: schema integer fields accept integral scalars; SnakeYAML may surface
        // them as Long. A within-range Long must bind, not be treated as malformed.
        ResolvedConfig cfg = resolver.resolve(
                Map.of(), Map.of("commandTimeoutSeconds", 600L), Map.of());

        assertEquals(600, cfg.commandTimeoutSeconds(),
                "a within-int-range Long must bind to the int field");
    }

    @Nested
    @DisplayName("file-path resolution (Path overload) + CT-SCH-13")
    class FilePathResolution {

        @Test
        @DisplayName("CT-SCH-13: the global-config fixture validates into a ResolvedConfig")
        void ct_sch_13_globalFixtureValidates(@TempDir Path dir) throws Exception {
            // Oracle: CT-SCH-13 (+) — fixtures/config.global.yaml validates (traces
            // AC-8.1). Satisfied by loading-and-resolving the documented on-disk shape
            // into a ResolvedConfig and asserting the documented field set binds.
            Path global = dir.resolve("config.yaml");
            try (var in = ConfigResolverTest.class.getResourceAsStream(
                    "/fixtures/config.global.yaml")) {
                assertTrue(in != null, "the CT-SCH-13 fixture must be on the test classpath");
                Files.write(global, in.readAllBytes());
            }
            Path absentProject = dir.resolve("projects").resolve("project.yaml");

            ResolvedConfig cfg = resolver.resolve(global, absentProject, Map.of());

            // Field set documented by the fixture (AC-8.1 configurable values).
            assertEquals("anthropic.claude-opus-4-8", cfg.modelId());
            assertEquals(PermissionMode.ASK_EVERY_TIME, cfg.permissionMode());
            assertEquals("us-east-1", cfg.region());
            assertEquals("my-bedrock-profile", cfg.awsProfile());
            assertEquals(1, cfg.subAgentMax());
            assertEquals("anthropic.claude-haiku-4-5-20251001-v1:0", cfg.summarizerModelId());
            assertEquals(0.85, cfg.contextCompactThreshold());
            assertEquals(16384, cfg.outputMaxInlineBytes());
            assertEquals(5, cfg.verifyMaxIterations());
            assertEquals(300, cfg.commandTimeoutSeconds());
        }

        @Test
        @DisplayName("absent global and project files resolve to all-default config (ADR-0009)")
        void absentFiles_resolveToDefaults(@TempDir Path dir) {
            // Oracle: ADR-0009 — absent layers are legal; resolution falls through to
            // built-in defaults (AC-8.3, AC-8.4).
            Path global = dir.resolve("config.yaml");
            Path project = dir.resolve("projects").resolve("project.yaml");

            ResolvedConfig cfg = resolver.resolve(global, project, Map.of());

            // Oracle: NFR-MODEL-DEFAULT pinned by ADR-0001 to the cross-region
            // inference-profile form us.anthropic.claude-opus-4-8.
            assertEquals("us.anthropic.claude-opus-4-8", cfg.modelId());
            assertEquals(PermissionMode.ASK_EVERY_TIME, cfg.permissionMode());
        }

        @Test
        @DisplayName("a project file overrides a global file via the Path overload (AC-8.2)")
        void projectFileOverridesGlobalFile(@TempDir Path dir) throws Exception {
            // Oracle: AC-8.2 — project config outranks global config, exercised end to
            // end through file loading.
            Path global = dir.resolve("config.yaml");
            Path project = dir.resolve("project.yaml");
            Files.writeString(global, "modelId: global-model\nregion: us-east-1\n",
                    StandardCharsets.UTF_8);
            Files.writeString(project, "modelId: project-model\n", StandardCharsets.UTF_8);

            ResolvedConfig cfg = resolver.resolve(global, project, Map.of());

            assertEquals("project-model", cfg.modelId(),
                    "project file must override global file (AC-8.2)");
            assertEquals("us-east-1", cfg.region(),
                    "global region survives where project does not set it (AC-8.2 per-key)");
        }

        @Test
        @DisplayName("an unknown key in a file propagates as a ConfigException (CT-SCH-14, AC-8.5)")
        void unknownKeyInFile_propagates(@TempDir Path dir) throws Exception {
            // Oracle: CT-SCH-14 — the loader's unknown-key rejection surfaces through
            // the resolver's file path; AC-8.5 names the key.
            Path global = dir.resolve("config.yaml");
            Files.writeString(global, "modelId: m\ntypoKey: x\n", StandardCharsets.UTF_8);
            Path project = dir.resolve("project.yaml");

            ConfigException ex = assertThrows(ConfigException.class,
                    () -> resolver.resolve(global, project, Map.of()),
                    "an unknown key in a config file must reach the resolver's caller");
            assertEquals("typoKey", ex.key());
        }
    }
}
