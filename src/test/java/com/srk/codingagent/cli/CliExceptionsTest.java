package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the CLI exit-code carrier exceptions — {@link UsageException},
 * {@link UserAbortedException}, and {@link InterruptedRunException}. They assert each
 * exception preserves the message / offending-argument / cause its contract role depends on
 * (G2: the cause is reportable; exit-code precedence keys off the exception type).
 */
class CliExceptionsTest {

    @Test
    @DisplayName("cli-exit-codes 2: UsageException carries the offending argument and message")
    void usageExceptionCarriesOffendingArgument() {
        // Oracle: cli-exit-codes "2 usage/config" requires the offending argument to be named
        // (G2). The exception must preserve both the offending argument and the message.
        UsageException e = new UsageException("--bogus", "unknown flag: --bogus");

        assertEquals("--bogus", e.offendingArgument(), "the offending argument is preserved (G2)");
        assertEquals("unknown flag: --bogus", e.getMessage(), "the message is preserved");
    }

    @Test
    @DisplayName("cli-exit-codes 3: UserAbortedException preserves its blocking-cause message")
    void userAbortedExceptionCarriesMessage() {
        // Oracle: cli-exit-codes "3 user-aborted" requires the cause to be named (G2).
        UserAbortedException e = new UserAbortedException("requires approval: run_command: ls");

        assertEquals("requires approval: run_command: ls", e.getMessage(),
                "the blocking-operation message is preserved (G2)");
    }

    @Test
    @DisplayName("cli-exit-codes 130: InterruptedRunException preserves message and chained cause")
    void interruptedRunExceptionChainsCause() {
        // Oracle: cli-exit-codes "130 interrupted (SIGINT)"; § 4 — the interrupt origin should
        // be debuggable, so the InterruptedException cause is chained when present.
        InterruptedException cause = new InterruptedException("step cancelled");
        InterruptedRunException e = new InterruptedRunException("interrupted by SIGINT", cause);

        assertEquals("interrupted by SIGINT", e.getMessage(), "the message is preserved");
        assertSame(cause, e.getCause(), "§ 4: the interrupt cause is chained for debuggability");
    }

    @Test
    @DisplayName("InterruptedRunException may carry no cause (a direct interrupt mapping)")
    void interruptedRunExceptionMayHaveNoCause() {
        // Oracle: the SIGINT seam may be raised without an InterruptedException cause (a direct
        // mapping); the no-cause constructor must produce a null cause, not crash.
        InterruptedRunException e = new InterruptedRunException("interrupted");

        assertNull(e.getCause(), "a direct interrupt mapping carries no cause");
    }
}
