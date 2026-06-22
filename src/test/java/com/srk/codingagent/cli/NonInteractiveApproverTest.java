package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.permission.GateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NonInteractiveApprover} — the one-shot approval seam that turns a
 * required prompt into a blocking denial (exit {@code 3}).
 *
 * <p>Oracle: AC-10.2 / exit-code contract "{@code 3} user-aborted — denial of a gated op the
 * task cannot proceed without". A one-shot run is non-interactive, so any operation the gate
 * must prompt for is one the run cannot proceed past; the approver aborts the run by throwing
 * {@link UserAbortedException}, naming the operation (G2).
 */
class NonInteractiveApproverTest {

    @Test
    @DisplayName("AC-10.2: confirming a gated command aborts with a UserAbortedException")
    void confirmingCommandAborts() {
        // Oracle: AC-10.2 / cli-exit-codes 3 — a one-shot cannot answer an interactive prompt,
        // so a required prompt is a blocking denial. The approver must throw
        // UserAbortedException (which the runner maps to exit 3).
        NonInteractiveApprover approver = new NonInteractiveApprover();
        GateRequest request = GateRequest.forCommand("tu_1", "rm -rf /");

        UserAbortedException thrown = assertThrows(UserAbortedException.class,
                () -> approver.confirm(request));

        assertTrue(thrown.getMessage().contains("rm -rf /"),
                "G2: the abort message names the gated operation; was: " + thrown.getMessage());
    }

    @Test
    @DisplayName("AC-10.2: confirming a gated write aborts naming the file operation (G2)")
    void confirmingWriteAborts() {
        // Oracle: AC-10.2 / AC-10.1 — the presented operation (file path + summary) is named in
        // the blocking-denial message so the cause is reportable (G2).
        NonInteractiveApprover approver = new NonInteractiveApprover();
        GateRequest request = GateRequest.forWrite("tu_2", "src/Main.java", "rewrite main");

        UserAbortedException thrown = assertThrows(UserAbortedException.class,
                () -> approver.confirm(request));

        assertTrue(thrown.getMessage().contains("src/Main.java"),
                "G2: the abort message names the write target; was: " + thrown.getMessage());
    }
}
