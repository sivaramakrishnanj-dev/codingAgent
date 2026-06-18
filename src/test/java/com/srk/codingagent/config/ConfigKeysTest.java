package com.srk.codingagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigKeys}.
 *
 * <p>Oracle: the resolved-config schema's property set under
 * {@code additionalProperties: false}. The schema enumerates exactly these
 * top-level properties — {@code modelId, permissionMode, region, awsProfile,
 * subAgentMax, summarizerModelId, commands, contextCompactThreshold,
 * outputMaxInlineBytes, verifyMaxIterations, commandTimeoutSeconds} — and the
 * {@code commands} object's properties {@code build, test, lint}. The recognized
 * vocabulary must match this set exactly so the loader rejects exactly the keys the
 * schema would reject (CT-SCH-14). The schema is the oracle, not the constant set.
 */
class ConfigKeysTest {

    @Test
    @DisplayName("recognized top-level keys mirror the schema properties (additionalProperties:false)")
    void topLevel_mirrorsSchemaProperties() {
        // Oracle: the schema's top-level "properties" object.
        Set<String> schemaTopLevel = Set.of(
                "modelId",
                "permissionMode",
                "region",
                "awsProfile",
                "subAgentMax",
                "summarizerModelId",
                "commands",
                "contextCompactThreshold",
                "outputMaxInlineBytes",
                "verifyMaxIterations",
                "commandTimeoutSeconds");

        assertEquals(schemaTopLevel, ConfigKeys.TOP_LEVEL,
                "Top-level recognized keys must equal the schema's property names");
    }

    @Test
    @DisplayName("recognized commands keys mirror the schema commands object (build/test/lint)")
    void commandsKeys_mirrorSchemaCommandsObject() {
        // Oracle: the schema's commands.properties { build, test, lint }.
        Set<String> schemaCommandKeys = Set.of("build", "test", "lint");

        assertEquals(schemaCommandKeys, ConfigKeys.COMMANDS_KEYS,
                "Recognized commands keys must equal the schema's commands properties");
    }
}
