package com.srk.codingagent.loop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.srk.codingagent.tool.CommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link RemedyAttempt#NONE} — the no-op remedy seam used when no model-driven
 * remedy is wired (a pure re-run verify loop). Oracle: AC-20.3 boundary — T-1.4 models the
 * remedy as an injected seam; NONE is the default that does nothing, so a verify loop with no
 * remedy simply re-runs the command. It must accept a failure without acting or throwing.
 */
class RemedyAttemptTest {

    @Test
    @DisplayName("RemedyAttempt.NONE accepts a failure and does nothing (the pure re-run default)")
    void noneIsANoOp() {
        CommandResult failure = CommandResult.completed("mvn test", 1, "out", "err", 10L);

        assertDoesNotThrow(() -> RemedyAttempt.NONE.attempt(failure),
                "AC-20.3 default: NONE feeds nothing back and never throws — the loop just re-runs");
    }
}
