package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests CT-SCH-1/2/3/4 ({@code 06-formal/contract-tests.md} § 1), validated
 * against the <em>formal</em> {@code event.schema.json} itself (copied verbatim into
 * {@code src/test/resources/schemas/}). Validating event lines against the real
 * schema — rather than against this code's incidental deserializer rejection — keeps
 * each assertion's oracle anchored to the spec artifact (AC-13.1, AC-13.3, INV-1).
 *
 * <p>The schema {@code $id}s are mapped to their classpath copies so the relative
 * {@code $ref} to {@code content-block.schema.json} resolves.
 */
class EventSchemaContractTest {

    private static final String EVENT_SCHEMA_ID = "https://codingagent.srk/schemas/event.schema.json";
    private static final String CONTENT_BLOCK_SCHEMA_ID =
            "https://codingagent.srk/schemas/content-block.schema.json";

    private static JsonSchema eventSchema;

    @BeforeAll
    static void loadSchema() {
        // The schema $ids are absolute URIs; map them to the classpath copies so the
        // relative $ref (content-block.schema.json) resolves to its classpath sibling.
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder
                        .schemaLoaders(loaders -> loaders.add(new ClasspathSchemaLoader()))
                        .schemaMappers(mappers -> mappers.mappings(Map.of(
                                EVENT_SCHEMA_ID, "classpath:schemas/event.schema.json",
                                CONTENT_BLOCK_SCHEMA_ID, "classpath:schemas/content-block.schema.json"))));
        eventSchema = factory.getSchema(SchemaLocation.of(EVENT_SCHEMA_ID));
    }

    private Set<ValidationMessage> validate(String json) {
        return eventSchema.validate(json, InputFormat.JSON);
    }

    @Test
    @DisplayName("CT-SCH-1 (+): every line of the session-tool-use-cycle fixture validates (AC-13.1, AC-13.3)")
    void ctSch1_fixtureEveryLineValidates() throws IOException {
        // Oracle: CT-SCH-1 — the positive fixture's every JSONL line validates against
        // event.schema.json. AC-13.3 pins the JSONL line-oriented format; AC-13.1 pins
        // that every interaction-event kind is representable.
        List<String> lines = readFixtureLines("fixtures/session-tool-use-cycle.jsonl");
        assertEquals(10, lines.size(), "fixture has the expected 10 event lines (seq 0..9)");

        for (int i = 0; i < lines.size(); i++) {
            Set<ValidationMessage> errors = validate(lines.get(i));
            assertTrue(errors.isEmpty(),
                    "fixture line " + i + " must validate but reported: " + errors);
        }
    }

    @Test
    @DisplayName("CT-SCH-2 (-): an event with an unknown type is rejected (AC-13.1)")
    void ctSch2_unknownTypeRejected() {
        // Oracle: CT-SCH-2 — the type enum is closed; a type outside it is invalid.
        String unknownType =
                "{\"seq\":0,\"ts\":\"2026-06-17T09:00:00Z\",\"type\":\"NOT_A_REAL_TYPE\",\"payload\":{}}";

        assertFalse(validate(unknownType).isEmpty(),
                "an event with an unknown type must be rejected (CT-SCH-2)");
    }

    @Test
    @DisplayName("CT-SCH-3 (-): a MODEL_RESPONSE with an out-of-enum stopReason is rejected (§6.A.1)")
    void ctSch3_badStopReasonRejected() {
        // Oracle: CT-SCH-3 — the modelResponse stopReason enum is the Converse
        // vocabulary (§6.A.1); a value outside it is invalid.
        String badStopReason = "{\"seq\":7,\"ts\":\"2026-06-17T09:01:16Z\",\"type\":\"MODEL_RESPONSE\","
                + "\"payload\":{\"stopReason\":\"NOPE\",\"content\":[{\"kind\":\"text\",\"text\":\"hi\"}]}}";

        assertFalse(validate(badStopReason).isEmpty(),
                "a MODEL_RESPONSE with a stopReason outside the enum must be rejected (CT-SCH-3)");
    }

    @Test
    @DisplayName("CT-SCH-4 (-): an event missing seq is flagged (INV-1 structural check)")
    void ctSch4_missingSeqFlagged() {
        // Oracle: CT-SCH-4 — seq is a required envelope field (INV-1: monotonic,
        // gap-free). An event line missing seq is structurally invalid.
        String missingSeq = "{\"ts\":\"2026-06-17T09:00:00Z\",\"type\":\"OUTCOME\","
                + "\"payload\":{\"taskRef\":\"t\",\"success\":true,\"iterations\":0}}";

        assertFalse(validate(missingSeq).isEmpty(),
                "an event missing the required seq must be flagged (CT-SCH-4, INV-1)");
    }

    @Test
    @DisplayName("CT-SCH-4 (-): a negative seq is flagged (INV-1: seq >= 0)")
    void ctSch4_negativeSeqFlagged() {
        // Oracle: CT-SCH-4 / INV-1 — seq is monotonic and gap-free from 0; the schema
        // pins minimum 0. A negative seq is a structurally invalid (non-monotonic)
        // sequence value.
        String negativeSeq = "{\"seq\":-1,\"ts\":\"2026-06-17T09:00:00Z\",\"type\":\"OUTCOME\","
                + "\"payload\":{\"taskRef\":\"t\",\"success\":true,\"iterations\":0}}";

        assertFalse(validate(negativeSeq).isEmpty(),
                "a negative seq must be flagged (CT-SCH-4, INV-1: seq >= 0)");
    }

    private static List<String> readFixtureLines(String resource) throws IOException {
        try (InputStream in = EventSchemaContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("fixture not found on classpath: " + resource);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines().filter(line -> !line.isBlank()).toList();
        }
    }
}
