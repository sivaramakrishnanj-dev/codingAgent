package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.ClasspathSchemaLoader;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test CT-SCH-5 ({@code 06-formal/contract-tests.md} § 1), validated against the
 * formal {@code content-block.schema.json} itself (the copy under
 * {@code src/test/resources/schemas/}). CT-SCH-5 asserts that each block variant —
 * text / toolUse / toolResult / reasoning / image / document / cachePoint — validates against
 * the schema. T-2.2 realizes the {@code reasoning} variant in code; this test confirms the
 * positive corpus (including reasoning, and the previously-realized text/toolUse/toolResult)
 * still validates against the unchanged schema, anchoring the assertion to the spec artifact
 * rather than to this code's incidental serialization.
 *
 * <p>The image / document / cachePoint variants are not yet modelled in code (deferred to
 * T-4.2 / T-4.4); CT-SCH-5's full enumeration of those variants belongs with the tasks that
 * realize them. This test covers the variants T-2.2's surface produces (the four modelled
 * {@link ContentBlock} kinds) plus the literal reasoning JSON shapes the schema permits.
 */
class ContentBlockSchemaContractTest {

    private static final String CONTENT_BLOCK_SCHEMA_ID =
            "https://codingagent.srk/schemas/content-block.schema.json";

    private static JsonSchema contentBlockSchema;
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @BeforeAll
    static void loadSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder
                        .schemaLoaders(loaders -> loaders.add(new ClasspathSchemaLoader()))
                        .schemaMappers(mappers -> mappers.mappings(Map.of(
                                CONTENT_BLOCK_SCHEMA_ID, "classpath:schemas/content-block.schema.json"))));
        contentBlockSchema = factory.getSchema(SchemaLocation.of(CONTENT_BLOCK_SCHEMA_ID));
    }

    private Set<ValidationMessage> validate(String json) {
        return contentBlockSchema.validate(json, InputFormat.JSON);
    }

    private static String serialize(ContentBlock block) {
        try {
            return MAPPER.writeValueAsString(block);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize content block", e);
        }
    }

    @Test
    @DisplayName("CT-SCH-5 (+): a serialized reasoning block (text+signature) validates (§2.3, ReasoningBlock)")
    void ctSch5_reasoningWithSignatureValidates() {
        // Oracle: CT-SCH-5 — the ReasoningBlock variant (kind reasoning, optional text/signature/
        // redactedContent) validates against content-block.schema.json. The serialized form of
        // the code's ContentBlock.Reasoning must match the schema branch.
        String json = serialize(ContentBlock.reasoning("step-by-step thinking", "sig-9f2a==", null));

        assertTrue(validate(json).isEmpty(),
                "CT-SCH-5: a reasoning block with text + signature must validate; was: " + validate(json));
    }

    @Test
    @DisplayName("CT-SCH-5 (+): a redacted-only reasoning block validates (only kind required)")
    void ctSch5_reasoningRedactedOnlyValidates() {
        // Oracle: CT-SCH-5 / ReasoningBlock — only kind is required; a redacted-only block
        // (kind + redactedContent) is a valid ReasoningBlock.
        String json = serialize(ContentBlock.reasoning(null, null, "cmVkYWN0ZWQ="));

        assertTrue(validate(json).isEmpty(),
                "CT-SCH-5: a redacted-only reasoning block must validate; was: " + validate(json));
    }

    @Test
    @DisplayName("CT-SCH-5 (+): the previously-realized text/toolUse/toolResult variants still validate")
    void ctSch5_otherModelledVariantsStillValidate() {
        // Oracle: CT-SCH-5 — adding the reasoning variant to the code must not regress the other
        // modelled variants' positive corpus. Each still validates against the unchanged schema.
        List<ContentBlock> variants = List.of(
                ContentBlock.text("hello"),
                ContentBlock.toolUse("tu-1", "read_file", Map.of("path", "/x")),
                ContentBlock.toolResult("tu-1", "ok", "done"));

        for (ContentBlock block : variants) {
            String json = serialize(block);
            assertTrue(validate(json).isEmpty(),
                    "CT-SCH-5: variant " + block.kind() + " must still validate; was: " + validate(json));
        }
    }

    @Test
    @DisplayName("CT-SCH-5 anchor: the schema admits the reasoning kind const (literal JSON)")
    void ctSch5_schemaAdmitsReasoningKind() {
        // Oracle: content-block.schema.json ReasoningBlock — kind const "reasoning". A literal
        // reasoning object (independent of the code's serializer) validates; a reasoning object
        // with an unknown field is rejected (additionalProperties:false), anchoring the variant.
        assertTrue(validate("{\"kind\":\"reasoning\",\"text\":\"t\",\"signature\":\"s\"}").isEmpty(),
                "the schema admits a reasoning block with text + signature");
        assertEquals(false,
                validate("{\"kind\":\"reasoning\",\"bogusField\":\"x\"}").isEmpty(),
                "the schema rejects a reasoning block with a disallowed field (additionalProperties:false)");
    }
}
