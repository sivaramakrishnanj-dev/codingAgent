package com.srk.codingagent.memory;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Contract tests CT-SCH-11 / CT-SCH-12 ({@code 06-formal/contract-tests.md} § 3.1, M2),
 * validated against the <em>formal</em> {@code memory-entry.schema.json} itself (copied
 * verbatim into {@code src/test/resources/schemas/}). Validating front-matter against the
 * real schema — rather than against this code's incidental construction rules — keeps each
 * assertion's oracle anchored to the spec artifact (AC-12.2, RD-9), the same approach
 * {@code EventSchemaContractTest} uses for the event schema.
 *
 * <p>A memory file's front-matter is a YAML mapping; the schema is JSON Schema, so each test
 * parses the front-matter to a map and validates its JSON form against the schema (JSON and
 * YAML mappings share the same data model for these scalar fields).
 */
class MemoryEntrySchemaContractTest {

    private static final String MEMORY_SCHEMA_ID =
            "https://codingagent.srk/schemas/memory-entry.schema.json";

    private static JsonSchema memorySchema;

    @BeforeAll
    static void loadSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder
                        .schemaLoaders(loaders -> loaders.add(new ClasspathSchemaLoader()))
                        .schemaMappers(mappers -> mappers.mappings(Map.of(
                                MEMORY_SCHEMA_ID, "classpath:schemas/memory-entry.schema.json"))));
        memorySchema = factory.getSchema(SchemaLocation.of(MEMORY_SCHEMA_ID));
    }

    @Test
    @DisplayName("CT-SCH-11 (+): the validated fixture's front-matter validates against the schema (AC-12.2)")
    void ctSch11_fixtureFrontMatterValidates() throws IOException {
        // Oracle: CT-SCH-11 — the positive fixture memory-entry.example.md front-matter
        // validates against memory-entry.schema.json. AC-12.2 pins human-readable markdown
        // with provenance.
        String fixture = readFixture("fixtures/memory-entry.example.md");
        String frontMatterJson = frontMatterAsJson(fixture);

        assertTrue(validate(frontMatterJson).isEmpty(),
                "the fixture front-matter must validate against the schema (CT-SCH-11), reported: "
                        + validate(frontMatterJson));
    }

    @Test
    @DisplayName("CT-SCH-11 (+): an entry this code renders produces schema-valid front-matter (AC-12.2)")
    void ctSch11_renderedEntryValidates() {
        // Oracle: CT-SCH-11 — our serialization must produce front-matter that validates.
        // Build an entry, render it to markdown, parse the front-matter back, validate.
        MemoryEntry entry = new MemoryEntry("retry-uses-jitter", MemoryTier.GLOBAL,
                "2026-06-22T10:00:00Z", "2026-06-22T09-00-00-sess", "Approved durable learning.",
                MemoryStatus.ACTIVE, "Always add jitter to retries (cite Release It!).");

        String frontMatterJson = frontMatterAsJson(MemoryMarkdown.render(entry));

        assertTrue(validate(frontMatterJson).isEmpty(),
                "a rendered entry's front-matter must validate against the schema (CT-SCH-11), reported: "
                        + validate(frontMatterJson));
    }

    @Test
    @DisplayName("CT-SCH-11 (+): tier GLOBAL and status active/retired are the schema's casing")
    void ctSch11_schemaCasing() {
        // Oracle: memory-entry.schema.json — tier enum is uppercase GLOBAL/PROJECT, status
        // enum is lowercase active/retired. A rendered entry must use exactly that casing.
        String retired = MemoryMarkdown.render(new MemoryEntry("a-slug", MemoryTier.PROJECT,
                "2026-06-22T10:00:00Z", "sess", "why", MemoryStatus.RETIRED, "body"));

        assertTrue(retired.contains("tier: PROJECT"), "tier is uppercase per the schema: " + retired);
        assertTrue(retired.contains("status: retired"), "status is lowercase per the schema: " + retired);
        assertTrue(validate(frontMatterAsJson(retired)).isEmpty(),
                "the retired-status front-matter validates (CT-SCH-11)");
    }

    @Test
    @DisplayName("CT-SCH-12 (-): a tier outside GLOBAL/PROJECT is rejected by the schema (RD-9)")
    void ctSch12_badTierRejected() {
        // Oracle: CT-SCH-12 — the tier enum is {GLOBAL, PROJECT}; a value outside it is
        // invalid. The schema is the oracle (not this code's enum), per RD-9's tier classes.
        String badTier = "{\"slug\":\"a-slug\",\"tier\":\"LOCAL\","
                + "\"created\":\"2026-06-22T10:00:00Z\",\"originSession\":\"sess\","
                + "\"why\":\"because\",\"status\":\"active\"}";

        assertFalse(validate(badTier).isEmpty(),
                "an entry with a tier outside GLOBAL/PROJECT must be rejected (CT-SCH-12)");
    }

    @Test
    @DisplayName("CT-SCH-12 (-): a status outside active/retired is rejected by the schema")
    void ctSch12_badStatusRejected() {
        // Oracle: memory-entry.schema.json status enum is {active, retired} (lowercase). A
        // value outside it — e.g. the uppercase ACTIVE the data-model prose names — is
        // rejected by the schema, confirming the lowercase wire vocabulary is load-bearing.
        String badStatus = "{\"slug\":\"a-slug\",\"tier\":\"GLOBAL\","
                + "\"created\":\"2026-06-22T10:00:00Z\",\"originSession\":\"sess\","
                + "\"why\":\"because\",\"status\":\"ACTIVE\"}";

        assertFalse(validate(badStatus).isEmpty(),
                "a status outside the lowercase active/retired enum must be rejected");
    }

    @Test
    @DisplayName("CT-SCH-12 (-): a missing required field is rejected by the schema")
    void ctSch12_missingRequiredFieldRejected() {
        // Oracle: memory-entry.schema.json required = [slug, tier, created, originSession,
        // why, status]. Front-matter missing 'why' is structurally invalid.
        String missingWhy = "{\"slug\":\"a-slug\",\"tier\":\"GLOBAL\","
                + "\"created\":\"2026-06-22T10:00:00Z\",\"originSession\":\"sess\","
                + "\"status\":\"active\"}";

        assertFalse(validate(missingWhy).isEmpty(),
                "front-matter missing a required field must be rejected");
    }

    private Set<ValidationMessage> validate(String json) {
        return memorySchema.validate(json, InputFormat.JSON);
    }

    /** Parses the YAML front-matter block out of a memory markdown file and JSON-encodes it. */
    private static String frontMatterAsJson(String markdown) {
        String normalized = markdown.replace("\r\n", "\n");
        int firstDelim = normalized.indexOf("---\n");
        int secondDelim = normalized.indexOf("\n---", firstDelim + 4);
        String yamlBlock = normalized.substring(firstDelim + 4, secondDelim + 1);
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlBlock);
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = new LinkedHashMap<>((Map<String, Object>) root);
        return toJson(fields);
    }

    private static String toJson(Map<String, Object> fields) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(e.getKey()).append("\":\"")
                    .append(String.valueOf(e.getValue()).replace("\"", "\\\"")).append('"');
        }
        return json.append('}').toString();
    }

    private static String readFixture(String resource) throws IOException {
        try (InputStream in = MemoryEntrySchemaContractTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("fixture not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("CT-SCH-11 (+): the fixture parses to a MemoryEntry via this code's parser")
    void ctSch11_fixtureParsesToEntry() throws IOException {
        // Oracle: AC-12.2 / AC-14.1 — the fixture is human-readable markdown the store reads.
        // This code's parser must read the validated fixture into a MemoryEntry with the
        // schema-pinned fields (tier PROJECT, status active).
        MemoryEntry entry = MemoryMarkdown.parse(readFixture("fixtures/memory-entry.example.md"));

        assertEquals("integration-tests-need-profile", entry.slug());
        assertEquals(MemoryTier.PROJECT, entry.tier());
        assertEquals(MemoryStatus.ACTIVE, entry.status(), "the fixture's lowercase 'active' parses to ACTIVE");
        assertEquals("2026-06-17T10:30:00Z", entry.created());
        assertTrue(entry.body().contains("@Tag(\"integration\")"), "the prose body is preserved");
    }
}
