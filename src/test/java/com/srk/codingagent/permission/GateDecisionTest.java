package com.srk.codingagent.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.PermissionDecisionPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GateDecision} — the value the agent loop (T-0.8) consumes to authorize a
 * tool and to record the {@code PERMISSION_DECISION} event.
 *
 * <p>Oracle: INV-9 (a denylisted command never carries a Grant), and the
 * {@code PermissionDecisionPayload} contract (the loop logs exactly the decision's data —
 * toolUseId, operationClass, mode, decision, matchedGrant).
 */
class GateDecisionTest {

    @Test
    @DisplayName("INV-9: a denylisted decision with a matched grant is rejected at construction")
    void denylistedWithGrantRejected() {
        // Oracle: INV-9 — a denylisted destructive command is never auto-approved and produces/
        // matches no Grant. The record guards this invariant directly.
        assertThrows(IllegalArgumentException.class,
                () -> new GateDecision(PermissionDecisionOutcome.APPROVE, OperationClass.SIDE_EFFECTING,
                        PermissionMode.UNRESTRICTED, "run_command:rm", false, true),
                "a denylisted decision must never carry a matched grant (INV-9)");
    }

    @Test
    @DisplayName("approved() reflects an APPROVE outcome")
    void approvedReflectsOutcome() {
        GateDecision approve = new GateDecision(PermissionDecisionOutcome.APPROVE,
                OperationClass.READ, PermissionMode.ASK_EVERY_TIME, null, false, false);
        GateDecision deny = new GateDecision(PermissionDecisionOutcome.DENY,
                OperationClass.SIDE_EFFECTING, PermissionMode.READ_ONLY, null, false, false);
        assertTrue(approve.approved());
        assertFalse(deny.approved());
    }

    @Test
    @DisplayName("toPayload maps the decision to the PERMISSION_DECISION payload the loop logs")
    void toPayloadMapsToEventPayload() {
        // Oracle: PermissionDecisionPayload contract (reused from persistence) — toolUseId,
        // operationClass, mode, decision, matchedGrant.
        GateDecision decision = new GateDecision(PermissionDecisionOutcome.APPROVE,
                OperationClass.SIDE_EFFECTING, PermissionMode.ASK_ONCE_THEN_REMEMBER,
                "run_command:mvn test", false, false);

        PermissionDecisionPayload payload = decision.toPayload("tu-9");

        assertEquals("tu-9", payload.toolUseId());
        assertEquals(OperationClass.SIDE_EFFECTING, payload.operationClass());
        assertEquals(PermissionMode.ASK_ONCE_THEN_REMEMBER, payload.mode());
        assertEquals(PermissionDecisionOutcome.APPROVE, payload.decision());
        assertEquals("run_command:mvn test", payload.matchedGrant());
    }

    @Test
    @DisplayName("GateDecision rejects null outcome / class / mode")
    void rejectsNullRequiredFields() {
        assertThrows(NullPointerException.class,
                () -> new GateDecision(null, OperationClass.READ, PermissionMode.UNRESTRICTED,
                        null, false, false));
        assertThrows(NullPointerException.class,
                () -> new GateDecision(PermissionDecisionOutcome.APPROVE, null,
                        PermissionMode.UNRESTRICTED, null, false, false));
        assertThrows(NullPointerException.class,
                () -> new GateDecision(PermissionDecisionOutcome.APPROVE, OperationClass.READ,
                        null, null, false, false));
    }
}
