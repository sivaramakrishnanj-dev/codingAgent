package com.srk.codingagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.ClasspathSchemaLoader;
import com.srk.codingagent.config.ConfigDefaults;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test <b>CT-SCH-15</b> ({@code 06-formal/contract-tests.md}; ADR-0002, OQ-J), validated
 * against the formal {@code model-capability-profile.schema.json} itself (the copy under
 * {@code src/test/resources/schemas/}). CT-SCH-15 (positive) asserts that <b>both</b> a resolved
 * Claude profile and the conservative default profile serialize to JSON that validates against
 * the schema. The schema is the oracle — every expectation traces to a schema branch, never to
 * this code's incidental serialization.
 *
 * <p>The positive cases serialize the profiles the registry actually resolves (the live default
 * Claude id; an unknown id) so the produced JSON is the production wire form. The negative cases
 * validate <em>literal JSON</em> against the schema (independent of this code's serializer) so the
 * oracle stays the schema artifact: an unknown {@code providerFamily} enum value, a missing
 * required field, a {@code ttls} token outside {@code 5m/1h}, and a disallowed extra property are
 * each rejected by the corresponding schema branch.
 */
class ModelCapabilityProfileSchemaContractTest {

    private static final String PROFILE_SCHEMA_ID =
            "https://codingagent.srk/schemas/model-capability-profile.schema.json";

    /**
     * A fallback window distinct from the real Claude window, so a serialized conservative-default
     * profile is unambiguously the fallback path. Not a spec literal — only the schema's
     * {@code minimum: 1} constrains the window; the value is an arbitrary positive count.
     */
    private static final int DISTINCT_FALLBACK_WINDOW = 12_345;

    private static JsonSchema profileSchema;
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @BeforeAll
    static void loadSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder
                        .schemaLoaders(loaders -> loaders.add(new ClasspathSchemaLoader()))
                        .schemaMappers(mappers -> mappers.mappings(Map.of(
                                PROFILE_SCHEMA_ID,
                                "classpath:schemas/model-capability-profile.schema.json"))));
        profileSchema = factory.getSchema(SchemaLocation.of(PROFILE_SCHEMA_ID));
    }

    private Set<ValidationMessage> validate(String json) {
        return profileSchema.validate(json, InputFormat.JSON);
    }

    private static String serialize(ModelCapabilityProfile profile) {
        try {
            return MAPPER.writeValueAsString(profile);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize capability profile", e);
        }
    }

    @Test
    @DisplayName("CT-SCH-15 (+): a resolved Claude profile validates against the schema (ADR-0002)")
    void ctSch15_claudeProfileValidates() {
        // Oracle: CT-SCH-15 — "a Claude profile ... validates". The profile the registry resolves
        // for the live default Claude id (ANTHROPIC, all required fields, promptCache present,
        // top_k passthrough, ttls 5m/1h) must serialize to schema-valid JSON.
        String json = serialize(ModelCapabilityProfile.forModelId(
                ConfigDefaults.MODEL_ID, DISTINCT_FALLBACK_WINDOW));

        assertTrue(validate(json).isEmpty(),
                "CT-SCH-15: the resolved Claude profile must validate; was: " + validate(json)
                        + " json=" + json);
    }

    @Test
    @DisplayName("CT-SCH-15 (+): the conservative default profile validates against the schema (ADR-0002)")
    void ctSch15_conservativeDefaultValidates() {
        // Oracle: CT-SCH-15 — "the conservative default profile ... validates". The profile the
        // registry resolves for an unknown id (OTHER, no thinking, promptCache null, empty
        // passthrough, no image/document input, fallback window) must serialize to schema-valid
        // JSON — promptCache:null is admitted by the schema's ["object","null"] type.
        String json = serialize(ModelCapabilityProfile.forModelId(
                "meta.llama3-70b-instruct-v1:0", DISTINCT_FALLBACK_WINDOW));

        assertTrue(validate(json).isEmpty(),
                "CT-SCH-15: the conservative default profile must validate; was: " + validate(json)
                        + " json=" + json);
    }

    @Test
    @DisplayName("CT-SCH-15 (+): both a Claude profile and the conservative default validate together")
    void ctSch15_bothProfilesValidate() {
        // Oracle: CT-SCH-15 — the binding assertion is that BOTH profiles validate. Asserting both
        // in one method mirrors the CT's "+ ... both validate" phrasing directly.
        String claude = serialize(ModelCapabilityProfile.forModelId(
                ConfigDefaults.MODEL_ID, DISTINCT_FALLBACK_WINDOW));
        String conservative = serialize(ModelCapabilityProfile.forModelId(
                "unknown-provider.some-model", DISTINCT_FALLBACK_WINDOW));

        assertTrue(validate(claude).isEmpty() && validate(conservative).isEmpty(),
                "CT-SCH-15: both the Claude profile and the conservative default must validate; "
                        + "claude=" + validate(claude) + " conservative=" + validate(conservative));
    }

    @Test
    @DisplayName("CT-SCH-15 (+): the serialized Claude promptCache.ttls are the schema tokens 5m/1h")
    void ctSch15_claudePromptCacheTtlsAreSchemaTokens() {
        // Oracle: model-capability-profile.schema.json promptCache.ttls item enum is "5m"/"1h".
        // The serialized profile's ttls must be those exact tokens (the schema would reject the
        // enum constant names). Anchors that the wire form matches the schema's ttls branch.
        String json = serialize(ModelCapabilityProfile.forModelId(
                ConfigDefaults.MODEL_ID, DISTINCT_FALLBACK_WINDOW));

        assertTrue(json.contains("\"5m\"") && json.contains("\"1h\""),
                "CT-SCH-15: the Claude promptCache.ttls must serialize to the schema tokens "
                        + "5m/1h; was: " + json);
    }

    @Test
    @DisplayName("CT-SCH-15 anchor (−): an unknown providerFamily enum value is rejected by the schema")
    void ctSch15_unknownProviderFamilyRejected() {
        // Oracle: schema providerFamily enum ANTHROPIC/AMAZON/META/MISTRAL/OTHER. A literal profile
        // with a family outside that set must be rejected — anchors the oracle to the schema enum,
        // independent of this code (the ProviderFamily enum cannot even express the bad value).
        String json = "{\"providerFamily\":\"OPENAI\",\"contextWindowTokens\":200000,"
                + "\"supportsExtendedThinking\":false,\"supportsToolUse\":true,"
                + "\"supportsImageInput\":false,\"supportsDocumentInput\":false}";

        assertEquals(false, validate(json).isEmpty(),
                "CT-SCH-15 anchor: a providerFamily outside the schema enum must be rejected");
    }

    @Test
    @DisplayName("CT-SCH-15 anchor (−): a profile missing a required field is rejected by the schema")
    void ctSch15_missingRequiredFieldRejected() {
        // Oracle: schema required = providerFamily, contextWindowTokens, supportsExtendedThinking,
        // supportsToolUse, supportsImageInput, supportsDocumentInput. Omitting contextWindowTokens
        // (a required field) must be rejected by the schema's required branch.
        String json = "{\"providerFamily\":\"ANTHROPIC\","
                + "\"supportsExtendedThinking\":true,\"supportsToolUse\":true,"
                + "\"supportsImageInput\":true,\"supportsDocumentInput\":true}";

        assertEquals(false, validate(json).isEmpty(),
                "CT-SCH-15 anchor: a profile missing a required field must be rejected");
    }

    @Test
    @DisplayName("CT-SCH-15 anchor (−): a promptCache.ttls token outside 5m/1h is rejected by the schema")
    void ctSch15_badTtlTokenRejected() {
        // Oracle: schema promptCache.ttls item enum "5m"/"1h". A ttls value outside that set
        // (here "30m") must be rejected by the schema's ttls item enum.
        String json = "{\"providerFamily\":\"ANTHROPIC\",\"contextWindowTokens\":200000,"
                + "\"supportsExtendedThinking\":true,\"supportsToolUse\":true,"
                + "\"supportsImageInput\":true,\"supportsDocumentInput\":true,"
                + "\"promptCache\":{\"minTokensPerCheckpoint\":4096,\"maxCheckpoints\":4,"
                + "\"ttls\":[\"30m\"]}}";

        assertEquals(false, validate(json).isEmpty(),
                "CT-SCH-15 anchor: a promptCache.ttls token outside 5m/1h must be rejected");
    }

    @Test
    @DisplayName("CT-SCH-15 anchor (−): an unexpected extra property is rejected (additionalProperties:false)")
    void ctSch15_extraPropertyRejected() {
        // Oracle: schema additionalProperties:false. A profile carrying a property the schema does
        // not declare must be rejected — anchors that the serialized shape carries no extra keys.
        String json = "{\"providerFamily\":\"OTHER\",\"contextWindowTokens\":100000,"
                + "\"supportsExtendedThinking\":false,\"supportsToolUse\":true,"
                + "\"supportsImageInput\":false,\"supportsDocumentInput\":false,"
                + "\"bogusField\":\"x\"}";

        assertEquals(false, validate(json).isEmpty(),
                "CT-SCH-15 anchor: an undeclared property must be rejected (additionalProperties:false)");
    }
}
