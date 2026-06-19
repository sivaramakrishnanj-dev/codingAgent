package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.ClasspathSchemaLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests CT-SCH-9 / CT-SCH-10 ({@code 06-formal/contract-tests.md} § 1),
 * validated against the <em>formal</em> {@code command-result.schema.json} itself
 * (copied verbatim into {@code src/test/resources/schemas/}). Validating against the real
 * schema — rather than against this code's own deserializer — anchors each assertion's
 * oracle to the spec artifact (ADR-0003, RD-10, INV-17), matching the pattern the event
 * schema contract tests already use.
 *
 * <p>The positive subject is the embedded {@code CommandResult} from the TOOL_RESULT line
 * of {@code session-tool-use-cycle.jsonl} (CT-SCH-9 says "{@code fixtures} embedded
 * CommandResult validates"); the negative subject is a CommandResult missing
 * {@code exitCode}, the verification signal the schema lists as required (CT-SCH-10).
 */
class CommandResultSchemaContractTest {

    private static final String COMMAND_RESULT_SCHEMA_ID =
            "https://codingagent.srk/schemas/command-result.schema.json";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static JsonSchema commandResultSchema;

    @BeforeAll
    static void loadSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder
                        .schemaLoaders(loaders -> loaders.add(new ClasspathSchemaLoader()))
                        .schemaMappers(mappers -> mappers.mappings(Map.of(
                                COMMAND_RESULT_SCHEMA_ID,
                                "classpath:schemas/command-result.schema.json"))));
        commandResultSchema = factory.getSchema(SchemaLocation.of(COMMAND_RESULT_SCHEMA_ID));
    }

    private Set<ValidationMessage> validate(String json) {
        return commandResultSchema.validate(json, InputFormat.JSON);
    }

    @Test
    @DisplayName("CT-SCH-9 (+): the fixture's embedded CommandResult validates against the schema (ADR-0003)")
    void ctSch9_embeddedCommandResultValidates() throws IOException {
        // Oracle: CT-SCH-9 — the embedded CommandResult inside the TOOL_RESULT line of
        // session-tool-use-cycle.jsonl validates against command-result.schema.json. The
        // contract-tests note pins this exact object.
        String embedded = extractEmbeddedCommandResult();

        Set<ValidationMessage> errors = validate(embedded);

        assertTrue(errors.isEmpty(),
                "the fixture's embedded CommandResult must validate but reported: " + errors);
    }

    @Test
    @DisplayName("CT-SCH-10 (-): a CommandResult missing exitCode is rejected (RD-10, INV-17)")
    void ctSch10_missingExitCodeRejected() {
        // Oracle: CT-SCH-10 — exitCode is the verification signal (RD-10, INV-17) and the
        // schema lists it as required; a result without it is invalid.
        String missingExitCode = "{\"command\":\"mvn -q test\",\"stdout\":\"\",\"stderr\":\"\","
                + "\"durationMs\":0,\"timedOut\":false,\"truncated\":false}";

        assertFalse(validate(missingExitCode).isEmpty(),
                "a CommandResult missing the required exitCode must be rejected (CT-SCH-10)");
    }

    @Test
    @DisplayName("CT-SCH-9 (+): a CommandResult this task serializes validates against the schema")
    void ctSch9_serializedResultValidates() throws IOException {
        // Oracle: CT-SCH-9 — the CommandResult type this task produces is the same shape
        // the schema pins, so a serialized instance (with null fullRef omitted) validates.
        CommandResult result = CommandResult.completed("echo hi", 0, "hi\n", "", 12L);

        String json = MAPPER.writeValueAsString(result);

        assertTrue(validate(json).isEmpty(),
                "a serialized CommandResult must validate against the formal schema: " + json);
    }

    private static String extractEmbeddedCommandResult() throws IOException {
        // The TOOL_RESULT line (seq 6) carries payload.result — the embedded CommandResult.
        try (InputStream in = CommandResultSchemaContractTest.class.getClassLoader()
                .getResourceAsStream("fixtures/session-tool-use-cycle.jsonl")) {
            if (in == null) {
                throw new IOException("fixture not found on classpath");
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.lines().toList()) {
                JsonNode root = MAPPER.readTree(line);
                if ("TOOL_RESULT".equals(root.path("type").asText())) {
                    JsonNode result = root.path("payload").path("result");
                    if (!result.isObject()) {
                        throw new IOException("TOOL_RESULT line has no object payload.result");
                    }
                    return MAPPER.writeValueAsString(result);
                }
            }
            throw new IOException("no TOOL_RESULT line in fixture");
        }
    }
}
