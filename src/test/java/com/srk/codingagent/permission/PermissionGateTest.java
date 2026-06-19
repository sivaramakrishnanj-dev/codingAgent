package com.srk.codingagent.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.PermissionDecisionPayload;
import com.srk.codingagent.tool.ReadFileTool;
import com.srk.codingagent.tool.RunCommandTool;
import com.srk.codingagent.tool.WriteFileTool;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link PermissionGate} (C8, ADR-0004): the 4-mode decision table, the RD-2
 * destructive-denylist carve-out, RD-1 grant matching under {@code ASK_ONCE_THEN_REMEMBER},
 * and the gate-level satisfaction of CT-INV-7/8/9 and CT-SM-2.
 *
 * <p>Oracle: AC-9.1..9.6 (the modes), AC-10.1..10.6 (approval), INV-8/9/10, ADR-0004's
 * decision table, and the contract tests. The gate is the SUT and runs its real decision
 * logic; the {@link Approver} is a controllable stub collaborator (a stand-in for the T-1.1
 * prompt UI), not a mock of the gate. Expected outcomes come from the spec table, not from
 * the gate's code.
 */
class PermissionGateTest {

    /** An approver stub that always returns a fixed outcome and counts how often it was asked. */
    private static final class StubApprover implements Approver {
        private final PermissionDecisionOutcome answer;
        private final AtomicInteger prompts = new AtomicInteger();

        StubApprover(PermissionDecisionOutcome answer) {
            this.answer = answer;
        }

        @Override
        public PermissionDecisionOutcome confirm(GateRequest request) {
            prompts.incrementAndGet();
            return answer;
        }

        int promptCount() {
            return prompts.get();
        }
    }

    private static StubApprover approving() {
        return new StubApprover(PermissionDecisionOutcome.APPROVE);
    }

    private static StubApprover denying() {
        return new StubApprover(PermissionDecisionOutcome.DENY);
    }

    private static PermissionGate gate(PermissionMode mode, Approver approver) {
        return new PermissionGate(mode, GrantStore.forSession("root-1"), approver);
    }

    private static GateRequest readRequest() {
        return GateRequest.forTool("tu-r", ReadFileTool.NAME, OperationClass.READ);
    }

    private static GateRequest commandRequest(String command) {
        return GateRequest.forCommand("tu-c", command);
    }

    private static GateRequest writeRequest(String path) {
        return GateRequest.forWrite("tu-w", path, "changed");
    }

    // --- AC-9.6 / RD-4: Class R is non-gated in EVERY mode ---

    @Test
    @DisplayName("AC-9.6: Class R is auto-approved in every mode without prompting")
    void classReadAutoApprovedInEveryMode() {
        // Oracle: AC-9.6 — in every mode, Class R operations are non-gated.
        for (PermissionMode mode : PermissionMode.values()) {
            StubApprover approver = approving();
            GateDecision decision = gate(mode, approver).evaluate(readRequest());
            assertTrue(decision.approved(), "Class R auto-approves in " + mode);
            assertFalse(decision.shouldPrompt(), "Class R never prompts in " + mode);
            assertEquals(0, approver.promptCount(), "no prompt for Class R in " + mode);
            assertNull(decision.matchedGrant(), "Class R approval is not via a grant in " + mode);
        }
    }

    // --- AC-9.2 / RD-6: READ_ONLY denies all Class X ---

    @Test
    @DisplayName("AC-9.2: READ_ONLY denies a Class X command without prompting")
    void readOnlyDeniesClassX() {
        // Oracle: AC-9.2 — while in READ_ONLY, deny all Class X operations.
        GateDecision decision = gate(PermissionMode.READ_ONLY, approving())
                .evaluate(commandRequest("mvn test"));
        assertFalse(decision.approved(), "READ_ONLY denies a Class X command");
        assertEquals(PermissionDecisionOutcome.DENY, decision.outcome());
    }

    @Test
    @DisplayName("AC-9.2: READ_ONLY denies a Class X file write")
    void readOnlyDeniesWrite() {
        GateDecision decision = gate(PermissionMode.READ_ONLY, approving())
                .evaluate(writeRequest("/ws/src/Foo.java"));
        assertFalse(decision.approved(), "READ_ONLY denies a write");
    }

    // --- AC-9.4: ASK_EVERY_TIME prompts before EVERY Class X ---

    @Test
    @DisplayName("AC-9.4: ASK_EVERY_TIME prompts on every Class X call (no memory)")
    void askEveryTimePromptsEachTime() {
        // Oracle: AC-9.4 — prompt before every Class X operation. The same command twice
        // prompts twice (no remembering in this mode).
        StubApprover approver = approving();
        PermissionGate gate = gate(PermissionMode.ASK_EVERY_TIME, approver);

        gate.evaluate(commandRequest("mvn test"));
        gate.evaluate(commandRequest("mvn test"));

        assertEquals(2, approver.promptCount(), "ASK_EVERY_TIME prompts on every Class X call");
    }

    @Test
    @DisplayName("AC-10.2: a denied Class X call produces a deny decision the loop turns into TOOL_RESULT(denied)")
    void deniedCallProducesDenyDecision() {
        // Oracle: AC-10.2 / CT-SM-2 — on deny, no side effect; the loop records a denial and
        // continues. The gate produces the DENY value the loop consumes.
        GateDecision decision = gate(PermissionMode.ASK_EVERY_TIME, denying())
                .evaluate(commandRequest("mvn test"));
        assertEquals(PermissionDecisionOutcome.DENY, decision.outcome(), "a denied prompt yields DENY");
        assertFalse(decision.approved(), "a denied call is not authorized to run (no side effect)");
    }

    // --- AC-9.3: UNRESTRICTED auto-approves all except denylisted ---

    @Test
    @DisplayName("AC-9.3: UNRESTRICTED auto-approves a non-denylisted Class X command without prompting")
    void unrestrictedAutoApprovesNonDenylisted() {
        // Oracle: AC-9.3 — auto-approve all operations except denylisted destructive commands.
        StubApprover approver = approving();
        GateDecision decision = gate(PermissionMode.UNRESTRICTED, approver)
                .evaluate(commandRequest("mvn test"));
        assertTrue(decision.approved(), "UNRESTRICTED auto-approves a non-denylisted command");
        assertFalse(decision.shouldPrompt(), "no prompt for a non-denylisted command in UNRESTRICTED");
        assertEquals(0, approver.promptCount());
    }

    // --- AC-10.4 / INV-9: the denylist always prompts, never remembers, denied in READ_ONLY ---

    @Test
    @DisplayName("AC-9.3 / AC-10.4: UNRESTRICTED still PROMPTS for a denylisted command (carve-out)")
    void unrestrictedPromptsForDenylisted() {
        // Oracle: AC-9.3 / AC-10.4 — a denylisted destructive command always prompts, even in
        // UNRESTRICTED.
        StubApprover approver = approving();
        GateDecision decision = gate(PermissionMode.UNRESTRICTED, approver)
                .evaluate(commandRequest("rm -rf build"));
        assertTrue(decision.denylisted(), "rm -rf is denylisted");
        assertTrue(decision.shouldPrompt(), "a denylisted command prompts even in UNRESTRICTED");
        assertEquals(1, approver.promptCount(), "the approver was consulted for the denylisted command");
        assertNull(decision.matchedGrant(), "a denylisted command is never approved via a grant (INV-9)");
    }

    @Test
    @DisplayName("AC-10.4: a denylisted command is DENIED OUTRIGHT in READ_ONLY (no prompt)")
    void readOnlyDeniesDenylistedOutright() {
        // Oracle: AC-10.4 — denied outright in READ_ONLY.
        StubApprover approver = approving();
        GateDecision decision = gate(PermissionMode.READ_ONLY, approver)
                .evaluate(commandRequest("rm -rf build"));
        assertEquals(PermissionDecisionOutcome.DENY, decision.outcome(), "denied outright in READ_ONLY");
        assertEquals(0, approver.promptCount(), "READ_ONLY denies a denylisted command without prompting");
        assertTrue(decision.denylisted());
    }

    @Test
    @DisplayName("CT-INV-8 / INV-9: a denylisted command NEVER auto-approves via a remembered grant")
    void denylistedNeverAutoApprovesViaGrant() {
        // Oracle: CT-INV-8 / INV-9 / AC-10.4 — a denylisted command is always prompted and
        // never auto-approved from a remembered grant. Even after approving it once under
        // ASK_ONCE_THEN_REMEMBER, the SAME denylisted command prompts again (records no grant).
        StubApprover approver = approving();
        PermissionGate gate = gate(PermissionMode.ASK_ONCE_THEN_REMEMBER, approver);

        GateDecision first = gate.evaluate(commandRequest("rm -rf build"));
        GateDecision second = gate.evaluate(commandRequest("rm -rf build"));

        assertEquals(2, approver.promptCount(),
                "a denylisted command prompts every time; the first approval is not remembered (INV-9)");
        assertNull(first.matchedGrant(), "the first denylisted approval records no grant");
        assertNull(second.matchedGrant(), "the second denylisted call matches no grant");
        assertTrue(second.denylisted());
    }

    // --- AC-9.5 / RD-1: ASK_ONCE_THEN_REMEMBER prompts first, then auto-approves matches ---

    @Test
    @DisplayName("AC-9.5 / RD-1: ASK_ONCE prompts the first matching op, then auto-approves later matches")
    void askOncePromptsFirstThenRemembers() {
        // Oracle: AC-9.5 / RD-1 — prompt on the first matching operation, auto-approve later
        // operations matching the same tool + normalized prefix. Approving "mvn test" lets
        // "mvn test -X" auto-approve.
        StubApprover approver = approving();
        PermissionGate gate = gate(PermissionMode.ASK_ONCE_THEN_REMEMBER, approver);

        GateDecision first = gate.evaluate(commandRequest("mvn test"));
        GateDecision second = gate.evaluate(commandRequest("mvn test -X"));

        assertTrue(first.shouldPrompt(), "the first matching op prompts");
        assertEquals(1, approver.promptCount(), "only the first op prompts; the second is remembered");
        assertTrue(second.approved(), "a later matching op auto-approves");
        assertFalse(second.shouldPrompt(), "the remembered op does not prompt again");
        assertEquals("run_command:mvn test", second.matchedGrant(),
                "the auto-approval cites the matched grant key (RD-1 normalized prefix)");
    }

    @Test
    @DisplayName("RD-1: a different subcommand re-prompts (mvn deploy does not match mvn test)")
    void askOnceDifferentSubcommandReprompts() {
        // Oracle: ADR-0004 worked example — "mvn deploy" does NOT match "mvn test".
        StubApprover approver = approving();
        PermissionGate gate = gate(PermissionMode.ASK_ONCE_THEN_REMEMBER, approver);

        gate.evaluate(commandRequest("mvn test"));
        GateDecision deploy = gate.evaluate(commandRequest("mvn deploy"));

        assertEquals(2, approver.promptCount(), "a different subcommand re-prompts");
        assertTrue(deploy.shouldPrompt(), "mvn deploy is not covered by the mvn test grant");
    }

    @Test
    @DisplayName("AC-9.5 / RD-1: a denied first prompt records no grant (the op re-prompts)")
    void askOnceDeniedRecordsNoGrant() {
        StubApprover approver = denying();
        PermissionGate gate = gate(PermissionMode.ASK_ONCE_THEN_REMEMBER, approver);

        GateDecision first = gate.evaluate(commandRequest("mvn test"));
        GateDecision second = gate.evaluate(commandRequest("mvn test"));

        assertFalse(first.approved(), "the denied op is not authorized");
        assertEquals(2, approver.promptCount(), "a denial records no grant, so the op prompts again");
        assertNull(second.matchedGrant(), "no grant was recorded from a denial");
    }

    @Test
    @DisplayName("AC-9.5 / RD-1: ASK_ONCE remembers a file-write subtree; later writes under it auto-approve")
    void askOnceRemembersWriteSubtree() {
        // Oracle: RD-1 — file-writes remembered per directory subtree; a later write under the
        // same subtree matches.
        StubApprover approver = approving();
        PermissionGate gate = gate(PermissionMode.ASK_ONCE_THEN_REMEMBER, approver);

        gate.evaluate(writeRequest("/ws/src/Foo.java"));
        GateDecision sibling = gate.evaluate(writeRequest("/ws/src/deep/Bar.java"));

        assertEquals(1, approver.promptCount(), "only the first write under the subtree prompts");
        assertTrue(sibling.approved(), "a later write under the granted subtree auto-approves");
        assertTrue(sibling.matchedGrant().startsWith("write:"), "the auto-approval cites the write grant");
    }

    @Test
    @DisplayName("RD-1: a write outside the granted subtree re-prompts")
    void askOnceWriteOutsideSubtreeReprompts() {
        StubApprover approver = approving();
        PermissionGate gate = gate(PermissionMode.ASK_ONCE_THEN_REMEMBER, approver);

        gate.evaluate(writeRequest("/ws/src/Foo.java"));
        GateDecision outside = gate.evaluate(writeRequest("/ws/other/Baz.java"));

        assertEquals(2, approver.promptCount(), "a write outside the granted subtree re-prompts");
        assertTrue(outside.shouldPrompt());
    }

    // --- CT-INV-7 / INV-8: the gate is the sole authorizer of a Class X op ---

    @Test
    @DisplayName("CT-INV-7 / INV-8: every Class X decision carries the operationClass + mode the loop logs")
    void classXDecisionCarriesPermissionDecisionData() {
        // Oracle: INV-8 / CT-INV-7 (gate-level) — no Class X op has an execute path that
        // bypasses a gate decision; the decision IS the precondition the loop enforces, and it
        // carries exactly the PERMISSION_DECISION payload data (toolUseId, operationClass, mode,
        // decision, matchedGrant).
        GateDecision decision = gate(PermissionMode.ASK_EVERY_TIME, approving())
                .evaluate(commandRequest("mvn test"));

        PermissionDecisionPayload payload = decision.toPayload("tu-c");
        assertEquals("tu-c", payload.toolUseId());
        assertEquals(OperationClass.SIDE_EFFECTING, payload.operationClass(),
                "a run_command is Class X (SIDE_EFFECTING)");
        assertEquals(PermissionMode.ASK_EVERY_TIME, payload.mode(), "the payload records the active mode");
        assertEquals(PermissionDecisionOutcome.APPROVE, payload.decision());
    }

    @Test
    @DisplayName("CT-INV-7: a Class R decision also maps to a PERMISSION_DECISION payload (approve, READ)")
    void classRDecisionMapsToPayload() {
        GateDecision decision = gate(PermissionMode.ASK_EVERY_TIME, denying())
                .evaluate(readRequest());
        PermissionDecisionPayload payload = decision.toPayload("tu-r");
        assertEquals(OperationClass.READ, payload.operationClass());
        assertEquals(PermissionDecisionOutcome.APPROVE, payload.decision(), "Class R is approved");
        assertNull(payload.matchedGrant(), "a Class R approval has no matched grant");
    }

    // --- CT-SM-2: a gate denial is the value the loop turns into TOOL_RESULT(denied) ---

    @Test
    @DisplayName("CT-SM-2: a denied decision maps to a DENY PERMISSION_DECISION payload (loop emits TOOL_RESULT denied)")
    void denyDecisionMapsToDenyPayload() {
        // Oracle: CT-SM-2 / state machine A T8 — a gate DENY appends PERMISSION_DECISION(deny)
        // and the loop turns it into a TOOL_RESULT(denied) with no side effect. The gate
        // produces the DENY decision; this asserts it maps to the deny payload the loop logs.
        GateDecision decision = gate(PermissionMode.ASK_EVERY_TIME, denying())
                .evaluate(commandRequest("mvn test"));
        PermissionDecisionPayload payload = decision.toPayload("tu-c");
        assertEquals(PermissionDecisionOutcome.DENY, payload.decision(),
                "a denied op records a deny PERMISSION_DECISION the loop pairs with TOOL_RESULT(denied)");
    }

    // --- AC-9.1: exactly four modes, all handled by the gate ---

    @Test
    @DisplayName("AC-9.1: the gate handles exactly the four permission modes")
    void gateHandlesAllFourModes() {
        // Oracle: AC-9.1 — exactly four modes. Each must produce a decision for a Class X op
        // (no unhandled mode falls through).
        assertEquals(4, PermissionMode.values().length, "exactly four permission modes (AC-9.1)");
        for (PermissionMode mode : PermissionMode.values()) {
            GateDecision decision = gate(mode, approving()).evaluate(commandRequest("mvn test"));
            assertEquals(mode, decision.mode(), "the decision records the mode it was evaluated under");
        }
    }

    // --- AC-9.5: ASK_ONCE_THEN_REMEMBER coarse-key tools (web/subagent/memory) ---

    @Test
    @DisplayName("AC-9.5 / RD-1: a coarse-key Class X tool is remembered by tool name")
    void askOnceRemembersCoarseTool() {
        // Oracle: ADR-0004 — for other Class X (web_search, spawn_subagent, memory write) the
        // key is the tool name; approving once auto-approves later calls to the same tool.
        StubApprover approver = approving();
        PermissionGate gate = gate(PermissionMode.ASK_ONCE_THEN_REMEMBER, approver);
        GateRequest webLookup = GateRequest.forTool("tu-1", "web_search", OperationClass.SIDE_EFFECTING);
        GateRequest webLookupAgain = GateRequest.forTool("tu-2", "web_search", OperationClass.SIDE_EFFECTING);

        gate.evaluate(webLookup);
        GateDecision again = gate.evaluate(webLookupAgain);

        assertEquals(1, approver.promptCount(), "only the first web lookup prompts");
        assertTrue(again.approved(), "a later web lookup auto-approves via the coarse tool-name grant");
        assertEquals("web_search", again.matchedGrant());
    }

    @Test
    @DisplayName("ADR-0004 reuse: GateRequest factories pin the right tool name + class")
    void gateRequestFactoriesPinToolAndClass() {
        // Oracle: dependency contract — run_command/write_file are Class X (04-apis § 3,
        // reused from the tool layer). The factories must produce the correct tool name + class.
        assertEquals(RunCommandTool.NAME, commandRequest("mvn test").toolName());
        assertEquals(OperationClass.SIDE_EFFECTING, commandRequest("mvn test").operationClass());
        assertEquals(WriteFileTool.NAME, writeRequest("/ws/src/Foo.java").toolName());
        assertEquals(OperationClass.SIDE_EFFECTING, writeRequest("/ws/src/Foo.java").operationClass());
    }
}
