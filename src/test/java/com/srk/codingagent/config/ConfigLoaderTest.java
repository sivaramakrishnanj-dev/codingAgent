package com.srk.codingagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ConfigLoader} — the per-file YAML reader and structural
 * (unknown-key) validator.
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>CT-SCH-14</b> (and AC-8.5): an unknown config key is rejected with a
 *       {@link ConfigException} naming the key. The schema's
 *       {@code additionalProperties: false} pins this for both the top level and the
 *       nested {@code commands} object.</li>
 *   <li>ADR-0009: "a configuration layer is allowed to be missing" &rarr; an absent
 *       or empty file yields an empty layer.</li>
 * </ul>
 * The SUT (a real {@link ConfigLoader}) is never mocked; YAML strings are fed via
 * the {@link Reader} overload, and the filesystem is exercised through {@code @TempDir}.
 */
class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader();

    private Map<String, Object> loadString(String yaml) {
        // A StringReader holds no OS resource and its close() never throws, so an
        // explicit try-with-resources only adds an unreachable checked-IOException.
        // The loader's Javadoc states the caller owns the reader lifecycle.
        return loader.load(new StringReader(yaml), "test-source");
    }

    @Test
    @DisplayName("a well-formed mapping loads into a raw key/value layer (CT-SCH-13 building block)")
    void wellFormedMapping_loadsRawLayer() {
        // Oracle: AC-8.1 — model/mode/commands are configurable; the loader returns
        // the raw mapping unchanged (typing is the resolver's job).
        Map<String, Object> layer = loadString(
                "modelId: some-model\npermissionMode: READ_ONLY\nsubAgentMax: 2\n");

        assertEquals("some-model", layer.get("modelId"));
        assertEquals("READ_ONLY", layer.get("permissionMode"));
        assertEquals(2, layer.get("subAgentMax"));
    }

    @Test
    @DisplayName("an unknown top-level key is rejected, naming the key (CT-SCH-14, AC-8.5)")
    void unknownTopLevelKey_rejectedNamingKey() {
        // Oracle: CT-SCH-14 "unknown config key rejected"; AC-8.5 "identifying the
        // offending key". The schema's additionalProperties:false makes any key not
        // in the vocabulary invalid.
        ConfigException ex = assertThrows(ConfigException.class,
                () -> loadString("modelId: m\nbogusKey: oops\n"),
                "an unrecognized top-level key must be rejected (CT-SCH-14)");

        assertEquals("bogusKey", ex.key(), "the exception must name the offending key (AC-8.5)");
        assertTrue(ex.getMessage().contains("bogusKey"),
                "the message must also embed the offending key for the stderr line (G2)");
    }

    @Test
    @DisplayName("an unknown commands.* key is rejected, naming the qualified key (CT-SCH-14)")
    void unknownCommandsKey_rejectedNamingQualifiedKey() {
        // Oracle: CT-SCH-14 — additionalProperties:false applies inside the commands
        // object too; AC-8.5 names the offending key (here qualified commands.deploy).
        ConfigException ex = assertThrows(ConfigException.class,
                () -> loadString("commands:\n  build: mvn\n  deploy: ./ship.sh\n"),
                "an unrecognized commands.* key must be rejected (CT-SCH-14)");

        assertEquals("commands.deploy", ex.key(),
                "the exception must name the qualified offending key commands.deploy");
        assertTrue(ex.getMessage().contains("commands.deploy"),
                "the message must embed the qualified key");
    }

    @Test
    @DisplayName("a known commands key (build/test/lint) is accepted")
    void knownCommandsKeys_accepted() {
        // Oracle: AC-8.1 — project build/test commands are configurable; the schema's
        // commands object lists build/test/lint.
        Map<String, Object> layer = loadString(
                "commands:\n  build: mvn verify\n  test: mvn test\n  lint: checkstyle\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> commands = (Map<String, Object>) layer.get("commands");
        assertEquals("mvn verify", commands.get("build"));
        assertEquals("mvn test", commands.get("test"));
        assertEquals("checkstyle", commands.get("lint"));
    }

    @Test
    @DisplayName("an empty document yields an empty layer (ADR-0009: a layer may be missing)")
    void emptyDocument_yieldsEmptyLayer() {
        // Oracle: ADR-0009 — configuration layers are allowed to be absent/empty.
        assertTrue(loadString("").isEmpty(), "an empty document must yield an empty layer");
    }

    @Test
    @DisplayName("a comment-only document yields an empty layer (ADR-0009)")
    void commentOnlyDocument_yieldsEmptyLayer() {
        // Oracle: ADR-0009 — YAML is comment-able; a comment-only file is empty config.
        assertTrue(loadString("# just a comment\n").isEmpty(),
                "a comment-only document must yield an empty layer");
    }

    @Test
    @DisplayName("a non-mapping root (a YAML scalar) is rejected (config must be a mapping)")
    void nonMappingRoot_rejected() {
        // Oracle: the schema's root "type": "object" -> the document must be a mapping
        // of keys to values, not a scalar or a sequence.
        assertThrows(ConfigException.class,
                () -> loadString("just-a-scalar-string\n"),
                "a scalar root is not a config mapping and must be rejected");
    }

    @Test
    @DisplayName("a non-mapping root (a YAML sequence) is rejected (config must be a mapping)")
    void sequenceRoot_rejected() {
        // Oracle: schema root type object -> a top-level sequence is invalid.
        assertThrows(ConfigException.class,
                () -> loadString("- a\n- b\n"),
                "a sequence root is not a config mapping and must be rejected");
    }

    @Test
    @DisplayName("malformed YAML syntax is rejected as a ConfigException (not valid YAML)")
    void malformedYamlSyntax_rejected() {
        // Oracle: AC-8.5 — a config file that cannot be parsed is a config fault that
        // must surface (the loader wraps the parser failure as a ConfigException, so
        // the CLI maps it to exit 2). Unbalanced flow-mapping braces are invalid YAML.
        assertThrows(ConfigException.class,
                () -> loadString("modelId: {unterminated\n"),
                "unparseable YAML must be rejected as a ConfigException");
    }

    @Test
    @DisplayName("a non-string config key is rejected, naming the bad key (schema keys are strings)")
    void nonStringKey_rejected() {
        // Oracle: the schema's properties are string-named; a non-string mapping key
        // (here an integer 1) is not a recognized key and must be rejected (AC-8.5).
        ConfigException ex = assertThrows(ConfigException.class,
                () -> loadString("1: value\n"),
                "a non-string config key must be rejected");
        assertTrue(ex.getMessage().contains("1"),
                "the message must reference the offending non-string key");
    }

    @Test
    @DisplayName("an absent file yields an empty layer (ADR-0009: a layer may be missing)")
    void absentFile_yieldsEmptyLayer(@TempDir Path dir) {
        // Oracle: ADR-0009 — a missing configuration file is a legal absent layer.
        Path missing = dir.resolve("nope.yaml");

        assertTrue(loader.load(missing).isEmpty(),
                "a missing file must resolve to an empty layer, not an error");
    }

    @Test
    @DisplayName("a present file is read and validated like the reader path (CT-SCH-14 via file)")
    void presentFile_readAndValidated(@TempDir Path dir) throws Exception {
        // Oracle: CT-SCH-14 exercised through the on-disk path — unknown key in a real
        // file is rejected naming the key.
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, "modelId: m\nmystery: x\n", StandardCharsets.UTF_8);

        ConfigException ex = assertThrows(ConfigException.class, () -> loader.load(file),
                "an unknown key in an on-disk file must be rejected");
        assertEquals("mystery", ex.key());
    }

    @Test
    @DisplayName("a well-formed on-disk file loads its values (file path parity with reader path)")
    void wellFormedFile_loadsValues(@TempDir Path dir) throws Exception {
        // Oracle: AC-8.1 — configurable values load identically from a file.
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, "modelId: file-model\nregion: eu-west-1\n", StandardCharsets.UTF_8);

        Map<String, Object> layer = loader.load(file);

        assertEquals("file-model", layer.get("modelId"));
        assertEquals("eu-west-1", layer.get("region"));
    }
}
