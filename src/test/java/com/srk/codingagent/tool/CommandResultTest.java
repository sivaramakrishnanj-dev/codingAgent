package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link CommandResult} domain type — the schema-pinned field invariants
 * (command-result.schema.json) and the two factories the executor uses.
 */
class CommandResultTest {

    @Test
    @DisplayName("completed(): captures the verification signal and leaves disposal unset (T-1.5)")
    void completedShapesAResult() {
        // Oracle: command-result.schema.json + NFR-OUTPUT-MAX-INLINE scope note — a
        // completed result carries exitCode (RD-10), is not timed out, and (in T-0.6) is
        // not truncated with no fullRef.
        CommandResult result = CommandResult.completed("echo hi", 0, "hi", "", 5L);

        assertEquals("echo hi", result.command());
        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertFalse(result.truncated());
        assertNull(result.fullRef());
    }

    @Test
    @DisplayName("timedOut(): flags timedOut and reports a non-zero failure code (ADR-0003)")
    void timedOutShapesAResult() {
        // Oracle: ADR-0003 — a timed-out command is a tool failure; timedOut=true and the
        // exit code is non-zero so it reads as a failure under RD-10.
        CommandResult result = CommandResult.timedOut("sleep 10", "", "", 200L);

        assertTrue(result.timedOut());
        assertFalse(result.exitCode() == 0, "a timed-out command must not read as success (ADR-0003)");
    }

    @Test
    @DisplayName("Constructor rejects null required fields and a negative durationMs")
    void constructorRejectsInvalid() {
        // Oracle: command-result.schema.json — command/stdout/stderr are required;
        // durationMs has minimum 0.
        assertThrows(NullPointerException.class,
                () -> new CommandResult(null, 0, "", "", 0L, false, false, null));
        assertThrows(NullPointerException.class,
                () -> new CommandResult("c", 0, null, "", 0L, false, false, null));
        assertThrows(NullPointerException.class,
                () -> new CommandResult("c", 0, "", null, 0L, false, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandResult("c", 0, "", "", -1L, false, false, null));
    }
}
