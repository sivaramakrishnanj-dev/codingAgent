package com.srk.codingagent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.tool.CommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link RemedyPrompt}: the shared failure-feedback prompt builder both workflow-driver remedies
 * use (AC-20.3 &mdash; feed the failure output back into reasoning; AC-20.5 &mdash; with the relevant
 * output).
 *
 * <p><b>Oracles trace to AC-20.3/AC-20.5:</b> the prompt must carry the failing command, its exit
 * code, and its captured {@code stdout}/{@code stderr} so the model reasons over the real failure
 * rather than a canned "it failed" string. The output tokens asserted below are the inputs' own
 * output, not the builder's wording.
 */
class RemedyPromptTest {

    @Test
    @DisplayName("AC-20.3/AC-20.5: the remedy prompt carries the failing command, exit code, and output")
    void carriesCommandExitAndOutput() {
        // Oracle: AC-20.3 — feed the failure OUTPUT back; AC-20.5 — with the relevant output. The
        // prompt must name the command, its exit code, and carry both stdout and stderr verbatim.
        CommandResult fail = CommandResult.completed(
                "mvn test", 7, "BUILD output here", "FAILED: missing import", 12L);

        String prompt = RemedyPrompt.forFailure(fail);

        assertTrue(prompt.contains("mvn test"), "the failing command is named");
        assertTrue(prompt.contains("7"), "the failing exit code is named");
        assertTrue(prompt.contains("BUILD output here"), "AC-20.3: stdout is fed back");
        assertTrue(prompt.contains("FAILED: missing import"), "AC-20.5: stderr is fed back");
    }

    @Test
    @DisplayName("a blank output stream is omitted (only present output is fed back)")
    void omitsBlankOutput() {
        // Oracle: AC-20.5 — the RELEVANT output is fed back. An empty stderr adds no empty section; the
        // present stdout is carried. This keeps the prompt to the output the model can reason over.
        CommandResult fail = CommandResult.completed("mvn test", 1, "only stdout", "", 5L);

        String prompt = RemedyPrompt.forFailure(fail);

        assertTrue(prompt.contains("only stdout"), "present stdout is fed back");
        assertFalse(prompt.contains("stderr:"),
                "a blank stderr stream adds no empty stderr section; was: " + prompt);
    }

    @Test
    @DisplayName("forFailure rejects a null failure")
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> RemedyPrompt.forFailure(null));
    }
}
