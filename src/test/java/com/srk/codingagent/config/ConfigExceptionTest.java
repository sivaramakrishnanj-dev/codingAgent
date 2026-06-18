package com.srk.codingagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigException}.
 *
 * <p>Oracle: AC-8.5 / AC-6.4 require that a config fault "name the offending key"
 * (AC-8.5) or "the missing field" (AC-6.4). {@link ConfigException} carries that
 * name as structured data ({@link ConfigException#key()}) so the CLI can surface it.
 * The tests pin the named-key requirement, not a particular message wording.
 */
class ConfigExceptionTest {

    @Test
    @DisplayName("key() returns the offending key supplied at construction (AC-8.5)")
    void key_returnsOffendingKey() {
        // Oracle: AC-8.5 "identifying the offending key".
        ConfigException ex = new ConfigException("permissionMode", "bad mode");

        assertEquals("permissionMode", ex.key(),
                "key() must carry the offending key as structured data (AC-8.5)");
    }

    @Test
    @DisplayName("the message is the supplied human-readable description")
    void message_isSuppliedDescription() {
        // The message text is not pinned by spec; only that it exists for the
        // stderr line. Shape check: getMessage() returns what was supplied.
        ConfigException ex = new ConfigException("modelId", "modelId must be a string");

        assertEquals("modelId must be a string", ex.getMessage());
    }

    @Test
    @DisplayName("the cause-chaining constructor preserves key, message, and cause")
    void causeChainingCtor_preservesAll() {
        // Oracle: error-handling discipline — causes are chained, key preserved.
        NumberFormatException cause = new NumberFormatException("nope");
        ConfigException ex = new ConfigException("subAgentMax", "not an integer", cause);

        assertEquals("subAgentMax", ex.key());
        assertEquals("not an integer", ex.getMessage());
        assertSame(cause, ex.getCause(), "the underlying cause must be chained");
    }

    @Test
    @DisplayName("ConfigException is a RuntimeException (unchecked, mapped by CLI to exit 2)")
    void isRuntimeException() {
        // Oracle: ADR-0009 maps the fault to exit 2 at the CLI boundary; unchecked
        // so it propagates to that single catch site without checked-exception noise.
        assertTrue(RuntimeException.class.isAssignableFrom(ConfigException.class),
                "ConfigException must be unchecked");
    }
}
