package com.srk.codingagent.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves configuration into a single immutable {@link ResolvedConfig} by a
 * layered merge with fixed precedence (ADR-0009, AC-8.2):
 *
 * <pre>CLI flags &gt; project config &gt; global config &gt; built-in defaults</pre>
 *
 * <p>The merge is <em>first-wins per key</em>: for each key the value from the
 * highest-precedence layer that supplies it is taken; lower layers fill only the
 * keys left unset. After merging, each value is coerced to its typed form and
 * validated against the resolved-config schema
 * ({@code 06-formal/resolved-config.schema.json}); a malformed value, a missing
 * required field, or an out-of-range number is surfaced as a {@link ConfigException}
 * naming the offending key (AC-8.5, AC-6.4). Validation is fail-fast: it happens at
 * startup, before any model call or tool execution.
 *
 * <p>The flags and file layers are supplied as already-parsed raw maps (the CLI
 * flag-parsing surface is a later task); this keeps the merge and validation logic
 * unit-testable in isolation. The built-in defaults layer comes from
 * {@link ConfigDefaults}; unknown-key rejection is performed by {@link ConfigLoader}
 * when a layer is read from a file.
 */
public final class ConfigResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigResolver.class);

    private final ConfigLoader loader;

    /**
     * Creates a resolver with the default {@link ConfigLoader}.
     */
    public ConfigResolver() {
        this(new ConfigLoader());
    }

    /**
     * Creates a resolver with an explicit loader (for testing the file-reading
     * path with a substitute loader).
     *
     * @param loader the loader used to read file layers; must not be {@code null}.
     */
    public ConfigResolver(ConfigLoader loader) {
        this.loader = loader;
    }

    /**
     * Resolves configuration from the global and project files on disk plus an
     * already-parsed flag-overrides layer.
     *
     * @param globalConfigPath  the global config file ({@code ~/.codingagent/config.yaml});
     *                          may be absent. Must not be {@code null}.
     * @param projectConfigPath the project config file
     *                          ({@code ~/.codingagent/projects/<repo-key>/project.yaml});
     *                          may be absent. Must not be {@code null}.
     * @param flagOverrides     the CLI flag layer as already-parsed key/value
     *                          pairs; may be empty. Must not be {@code null}.
     * @return the validated resolved configuration; never {@code null}.
     * @throws ConfigException if any layer has an unknown key or any resolved
     *                         value is malformed, out of range, or missing.
     */
    public ResolvedConfig resolve(Path globalConfigPath, Path projectConfigPath,
            Map<String, Object> flagOverrides) {
        Map<String, Object> global = loader.load(globalConfigPath);
        Map<String, Object> project = loader.load(projectConfigPath);
        return resolve(flagOverrides, project, global);
    }

    /**
     * Resolves configuration from already-loaded layers, applying first-wins
     * precedence (flags &gt; project &gt; global &gt; defaults) and validating the
     * result.
     *
     * @param flagOverrides the CLI flag layer (highest precedence); must not be
     *                      {@code null}.
     * @param project       the project-config layer; must not be {@code null}.
     * @param global        the global-config layer (lowest file precedence); must
     *                      not be {@code null}.
     * @return the validated resolved configuration; never {@code null}.
     * @throws ConfigException if any resolved value is malformed, out of range, or
     *                         a required field is missing.
     */
    public ResolvedConfig resolve(Map<String, Object> flagOverrides,
            Map<String, Object> project, Map<String, Object> global) {
        Map<String, Object> merged = mergeFirstWins(flagOverrides, project, global);
        ResolvedConfig resolved = bindAndValidate(merged);
        LOGGER.info("resolved config: model={}, mode={}, region={}, subAgentMax={}",
                resolved.modelId(), resolved.permissionMode(), resolved.region(),
                resolved.subAgentMax());
        return resolved;
    }

    /**
     * Overlays layers in precedence order, highest first. A key present in an
     * earlier (higher-precedence) layer is never overwritten by a later one, which
     * realizes first-wins precedence (AC-8.2).
     */
    private static Map<String, Object> mergeFirstWins(Map<String, Object>... layersHighestFirst) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map<String, Object> layer : layersHighestFirst) {
            for (Map.Entry<String, Object> entry : layer.entrySet()) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private ResolvedConfig bindAndValidate(Map<String, Object> merged) {
        String modelId = stringOrDefault(merged, ConfigKeys.MODEL_ID, ConfigDefaults.MODEL_ID);
        PermissionMode permissionMode = permissionMode(merged);
        String region = stringOrDefault(merged, ConfigKeys.REGION, ConfigDefaults.REGION);
        String awsProfile = nullableString(merged, ConfigKeys.AWS_PROFILE);
        int subAgentMax = intInRange(merged, ConfigKeys.SUB_AGENT_MAX,
                ConfigDefaults.SUB_AGENT_MAX, 1, Integer.MAX_VALUE);
        String summarizerModelId = nullableString(merged, ConfigKeys.SUMMARIZER_MODEL_ID);
        ResolvedConfig.Commands commands = commands(merged);
        double contextCompactThreshold = doubleInRange(merged, ConfigKeys.CONTEXT_COMPACT_THRESHOLD,
                ConfigDefaults.CONTEXT_COMPACT_THRESHOLD, 0.0, 1.0);
        int outputMaxInlineBytes = intInRange(merged, ConfigKeys.OUTPUT_MAX_INLINE_BYTES,
                ConfigDefaults.OUTPUT_MAX_INLINE_BYTES, 1, Integer.MAX_VALUE);
        int verifyMaxIterations = intInRange(merged, ConfigKeys.VERIFY_MAX_ITERATIONS,
                ConfigDefaults.VERIFY_MAX_ITERATIONS, 1, Integer.MAX_VALUE);
        int commandTimeoutSeconds = intInRange(merged, ConfigKeys.COMMAND_TIMEOUT_SECONDS,
                ConfigDefaults.COMMAND_TIMEOUT_SECONDS, 1, Integer.MAX_VALUE);

        return new ResolvedConfig(modelId, permissionMode, region, awsProfile, subAgentMax,
                summarizerModelId, commands, contextCompactThreshold, outputMaxInlineBytes,
                verifyMaxIterations, commandTimeoutSeconds);
    }

    private static PermissionMode permissionMode(Map<String, Object> merged) {
        Object raw = merged.get(ConfigKeys.PERMISSION_MODE);
        if (raw == null) {
            return ConfigDefaults.PERMISSION_MODE;
        }
        String name = requireString(raw, ConfigKeys.PERMISSION_MODE);
        try {
            return PermissionMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(ConfigKeys.PERMISSION_MODE,
                    "unknown permission mode '" + name + "' for key '" + ConfigKeys.PERMISSION_MODE
                            + "'; expected one of " + java.util.Arrays.toString(PermissionMode.values()),
                    e);
        }
    }

    private static ResolvedConfig.Commands commands(Map<String, Object> merged) {
        Object raw = merged.get(ConfigKeys.COMMANDS);
        if (raw == null) {
            return ResolvedConfig.Commands.empty();
        }
        if (!(raw instanceof Map<?, ?> commandMap)) {
            throw new ConfigException(ConfigKeys.COMMANDS,
                    "key '" + ConfigKeys.COMMANDS + "' must be a mapping of command names to strings");
        }
        String build = nullableCommand(commandMap, ConfigKeys.COMMANDS_BUILD);
        String test = nullableCommand(commandMap, ConfigKeys.COMMANDS_TEST);
        String lint = nullableCommand(commandMap, ConfigKeys.COMMANDS_LINT);
        return new ResolvedConfig.Commands(build, test, lint);
    }

    private static String nullableCommand(Map<?, ?> commandMap, String key) {
        Object raw = commandMap.get(key);
        if (raw == null) {
            return null;
        }
        return requireString(raw, ConfigKeys.COMMANDS + "." + key);
    }

    private static String stringOrDefault(Map<String, Object> merged, String key, String fallback) {
        Object raw = merged.get(key);
        if (raw == null) {
            return fallback;
        }
        return requireString(raw, key);
    }

    private static String nullableString(Map<String, Object> merged, String key) {
        Object raw = merged.get(key);
        if (raw == null) {
            return null;
        }
        return requireString(raw, key);
    }

    private static String requireString(Object raw, String key) {
        if (raw instanceof String s) {
            return s;
        }
        throw new ConfigException(key,
                "key '" + key + "' must be a string but was " + typeName(raw) + " (" + raw + ")");
    }

    private static int intInRange(Map<String, Object> merged, String key, int fallback,
            int min, int max) {
        Object raw = merged.get(key);
        if (raw == null) {
            return fallback;
        }
        int value = requireInt(raw, key);
        if (value < min || value > max) {
            throw new ConfigException(key,
                    "key '" + key + "' must be in [" + min + ", " + max + "] but was " + value);
        }
        return value;
    }

    private static int requireInt(Object raw, String key) {
        if (raw instanceof Integer i) {
            return i;
        }
        // SnakeYAML may surface integral scalars as Long for large values; accept
        // only when the value fits an int, otherwise it is out of the schema range.
        if (raw instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return l.intValue();
        }
        throw new ConfigException(key,
                "key '" + key + "' must be an integer but was " + typeName(raw) + " (" + raw + ")");
    }

    private static double doubleInRange(Map<String, Object> merged, String key, double fallback,
            double min, double max) {
        Object raw = merged.get(key);
        if (raw == null) {
            return fallback;
        }
        double value = requireDouble(raw, key);
        if (value < min || value > max) {
            throw new ConfigException(key,
                    "key '" + key + "' must be in [" + min + ", " + max + "] but was " + value);
        }
        return value;
    }

    private static double requireDouble(Object raw, String key) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        throw new ConfigException(key,
                "key '" + key + "' must be a number but was " + typeName(raw) + " (" + raw + ")");
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
