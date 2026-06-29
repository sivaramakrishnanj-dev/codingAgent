package com.srk.codingagent.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.tool.WebFetchTool;
import com.srk.codingagent.tool.WebSearchTool;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Class-X gating contract for the web-lookup tools (C11, ADR-0008): {@code web_search}/{@code web_fetch}
 * are Class X (subprocess + network) and so are DENIED in {@code READ_ONLY} and gated/prompted in the
 * asking modes (AC-11.2, RD-6). This is the "CT (Class X gating)" the T-4.1 Verify column directs —
 * there is no numbered contract test for the web delegate, so the gating behavior itself is the
 * contract pinned here.
 *
 * <p>The {@link PermissionGate} is the SUT and runs its real decision logic; the {@link Approver} is a
 * controllable stub (a stand-in for the T-1.1 prompt UI), not a mock of the gate. Expected outcomes
 * come from the AC/RD spec, not from the gate's or the tools' code.
 */
class WebLookupGatingTest {

    /** An approver stub returning a fixed outcome and counting how often it was asked. */
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

    private static PermissionGate gate(PermissionMode mode, Approver approver) {
        return new PermissionGate(mode, GrantStore.forSession("root-web"), approver);
    }

    /** A web-lookup gate request, exactly as the loop builds it for a Class-X tool call. */
    private static GateRequest webRequest(String toolName) {
        return GateRequest.forTool("tu-web", toolName, OperationClass.SIDE_EFFECTING);
    }

    @Test
    @DisplayName("AC-11.2/RD-6: READ_ONLY denies web_search without prompting")
    void readOnlyDeniesWebSearch() {
        // Oracle: AC-11.2 — "while in READ_ONLY [web-lookup] shall be denied"; RD-6 — web-lookup is
        // Class X → denied in READ_ONLY. The gate must deny and must not prompt.
        StubApprover approver = new StubApprover(PermissionDecisionOutcome.APPROVE);

        GateDecision decision = gate(PermissionMode.READ_ONLY, approver).evaluate(webRequest(WebSearchTool.NAME));

        assertFalse(decision.approved(), "AC-11.2/RD-6: READ_ONLY denies web_search");
        assertEquals(PermissionDecisionOutcome.DENY, decision.outcome());
        assertEquals(0, approver.promptCount(), "READ_ONLY denial does not prompt");
    }

    @Test
    @DisplayName("AC-11.2/RD-6: READ_ONLY denies web_fetch without prompting")
    void readOnlyDeniesWebFetch() {
        // Oracle: AC-11.2 / RD-6 — same denial for the fetch tool (both are Class X).
        StubApprover approver = new StubApprover(PermissionDecisionOutcome.APPROVE);

        GateDecision decision = gate(PermissionMode.READ_ONLY, approver).evaluate(webRequest(WebFetchTool.NAME));

        assertFalse(decision.approved(), "AC-11.2/RD-6: READ_ONLY denies web_fetch");
        assertEquals(PermissionDecisionOutcome.DENY, decision.outcome());
        assertEquals(0, approver.promptCount(), "READ_ONLY denial does not prompt");
    }

    @Test
    @DisplayName("AC-11.2/RD-6: outside READ_ONLY web-lookup is available as Class X (prompted in ASK_EVERY_TIME)")
    void askEveryTimePromptsForWebLookup() {
        // Oracle: AC-11.2 — "while not in READ_ONLY, web-lookup shall be available as a Class X
        // operation subject to the active permission mode". RD-6 — "use an asking mode to allow
        // lookups." Under ASK_EVERY_TIME the gate prompts (the asking mode) and approves on a yes —
        // i.e. web-lookup is available subject to the mode, not denied.
        StubApprover approver = new StubApprover(PermissionDecisionOutcome.APPROVE);

        GateDecision decision = gate(PermissionMode.ASK_EVERY_TIME, approver).evaluate(webRequest(WebSearchTool.NAME));

        assertTrue(decision.approved(),
                "AC-11.2: outside READ_ONLY web-lookup is available subject to the mode");
        assertTrue(decision.shouldPrompt(),
                "RD-6: an asking mode prompts before the Class-X web lookup");
        assertEquals(1, approver.promptCount(), "ASK_EVERY_TIME prompts once for the web lookup");
    }

    @Test
    @DisplayName("AC-11.2: an asking mode that denies the prompt blocks the web lookup")
    void askEveryTimeDenyBlocksWebLookup() {
        // Oracle: AC-11.2 — web-lookup is "subject to the active permission mode"; a denied prompt in
        // an asking mode means the Class-X lookup does not run.
        StubApprover approver = new StubApprover(PermissionDecisionOutcome.DENY);

        GateDecision decision = gate(PermissionMode.ASK_EVERY_TIME, approver).evaluate(webRequest(WebFetchTool.NAME));

        assertFalse(decision.approved(),
                "AC-11.2: a denied prompt blocks the Class-X web lookup");
        assertEquals(1, approver.promptCount(), "the asking mode prompted before denying");
    }
}
