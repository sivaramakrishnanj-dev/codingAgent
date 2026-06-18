package com.srk.codingagent.config;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Parses a single on-disk YAML configuration source (a global or a project file)
 * into a raw key/value layer, rejecting unknown keys.
 *
 * <p>A loaded layer is an untyped {@code Map<String, Object>} as produced by
 * SnakeYAML; type coercion, range validation, and default-filling are the
 * resolver's responsibility ({@link ConfigResolver}). The loader's one validation
 * duty is structural: per ADR-0009 and the schema's
 * {@code additionalProperties: false}, an unrecognized key — at the top level or
 * inside the {@code commands} object — is an error (CT-SCH-14), surfaced as a
 * {@link ConfigException} naming the offending key (AC-8.5).
 *
 * <p>Sources are supplied as {@link Path}s or {@link Reader}s, never resolved from
 * a fixed on-disk location, so the loader is unit-testable in isolation. An absent
 * file yields an empty layer (a configuration layer is allowed to be missing); an
 * empty or comment-only file likewise yields an empty layer.
 */
public final class ConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

    public ConfigLoader() {
        // Stateless; default constructor.
    }

    /**
     * Loads and structurally validates the YAML file at {@code path}, if it exists.
     *
     * @param path the file to load; must not be {@code null}.
     * @return the raw configuration layer; an empty map if the file does not
     *         exist or is empty. Never {@code null}.
     * @throws ConfigException    if the file contains an unknown key.
     * @throws UncheckedIOException if the file exists but cannot be read.
     */
    public Map<String, Object> load(Path path) {
        if (!Files.exists(path)) {
            LOGGER.debug("config file absent, treating as empty layer: {}", path);
            return Collections.emptyMap();
        }
        LOGGER.info("loading config file: {}", path);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(reader, path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read config file: " + path, e);
        }
    }

    /**
     * Loads and structurally validates a YAML configuration from a reader.
     *
     * @param reader the YAML source; must not be {@code null}. The caller owns the
     *               reader's lifecycle.
     * @param source a label identifying the source (file path or a synthetic name)
     *               used only in diagnostics; must not be {@code null}.
     * @return the raw configuration layer; an empty map for an empty or
     *         comment-only document. Never {@code null}.
     * @throws ConfigException if the document is not a mapping or contains an
     *                         unknown key.
     */
    public Map<String, Object> load(Reader reader, String source) {
        Object root;
        try {
            root = newYaml().load(reader);
        } catch (YAMLException e) {
            throw new ConfigException(source, "config file is not valid YAML (" + source + "): "
                    + e.getMessage(), e);
        }
        if (root == null) {
            return Collections.emptyMap();
        }
        if (!(root instanceof Map<?, ?> rawMap)) {
            throw new ConfigException(source,
                    "config file must be a YAML mapping of keys to values (" + source + ")");
        }
        Map<String, Object> layer = toStringKeyedMap(rawMap, source);
        rejectUnknownKeys(layer, source);
        return layer;
    }

    private static Yaml newYaml() {
        // SafeConstructor: no arbitrary-type instantiation from the document.
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    private static Map<String, Object> toStringKeyedMap(Map<?, ?> rawMap, String source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String stringKey)) {
                throw new ConfigException(String.valueOf(key),
                        "config key must be a string but was '" + key + "' (" + source + ")");
            }
            result.put(stringKey, entry.getValue());
        }
        return result;
    }

    private static void rejectUnknownKeys(Map<String, Object> layer, String source) {
        for (String key : layer.keySet()) {
            if (!ConfigKeys.TOP_LEVEL.contains(key)) {
                throw new ConfigException(key,
                        "unknown config key '" + key + "' (" + source + "); "
                                + "unknown keys are rejected to catch typos early (ADR-0009)");
            }
        }
        rejectUnknownCommandKeys(layer, source);
    }

    private static void rejectUnknownCommandKeys(Map<String, Object> layer, String source) {
        Object commands = layer.get(ConfigKeys.COMMANDS);
        if (!(commands instanceof Map<?, ?> commandMap)) {
            return;
        }
        for (Object key : commandMap.keySet()) {
            if (!ConfigKeys.COMMANDS_KEYS.contains(key)) {
                String qualified = ConfigKeys.COMMANDS + "." + key;
                throw new ConfigException(qualified,
                        "unknown config key '" + qualified + "' (" + source + "); "
                                + "unknown keys are rejected to catch typos early (ADR-0009)");
            }
        }
    }
}
