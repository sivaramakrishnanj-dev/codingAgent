package com.srk.codingagent.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EdgeType;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the sub-agent value types {@link SubAgentSpec}, {@link SubAgentResult}, and
 * {@link ChildLoopContext} — their validation and the spec's optional-override semantics
 * (ADR-0010, AC-17.2/AC-17.4/AC-17.6, INV-11). The SUTs (the real records) are never mocked.
 */
class SubAgentValueTypesTest {

    private static PermissionGate aGate() {
        Approver approver = req -> PermissionDecisionOutcome.APPROVE;
        return new PermissionGate(PermissionMode.ASK_EVERY_TIME, GrantStore.forSession("c"), approver);
    }

    // --- SubAgentSpec ----------------------------------------------------------------

    @Test
    @DisplayName("AC-17.2: SubAgentSpec.of carries only the prompt — no model or budget override")
    void specOf_noOverrides() {
        // Oracle: ADR-0010 — "v1 default: inherit parent model unless overridden"; the default
        // budget is the orchestrator's. SubAgentSpec.of is the no-override form.
        SubAgentSpec spec = SubAgentSpec.of("do the subtask");

        assertEquals("do the subtask", spec.prompt());
        assertEquals(Optional.empty(), spec.modelIdIfPresent(),
                "AC-17.2: no model override means inherit the parent's");
        assertEquals(Optional.empty(), spec.wallClockCapIfPresent(),
                "AC-17.6: no cap override means use the orchestrator default");
    }

    @Test
    @DisplayName("AC-17.2/AC-17.6: SubAgentSpec exposes a model and budget override when present")
    void spec_overridesPresent() {
        SubAgentSpec spec = new SubAgentSpec("p", "anthropic.claude-haiku-4-8", Duration.ofSeconds(30));

        assertEquals(Optional.of("anthropic.claude-haiku-4-8"), spec.modelIdIfPresent(),
                "AC-17.2: the model override is exposed");
        assertEquals(Optional.of(Duration.ofSeconds(30)), spec.wallClockCapIfPresent(),
                "AC-17.6: the budget override is exposed");
    }

    @Test
    @DisplayName("SubAgentSpec rejects a blank prompt, a blank model, and a non-positive budget")
    void spec_validates() {
        assertThrows(IllegalArgumentException.class, () -> SubAgentSpec.of(" "),
                "a blank prompt is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentSpec("p", " ", null),
                "a present-but-blank model is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentSpec("p", null, Duration.ZERO),
                "a non-positive budget is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentSpec("p", null, Duration.ofSeconds(-1)),
                "a negative budget is rejected");
    }

    // --- SubAgentResult --------------------------------------------------------------

    @Test
    @DisplayName("AC-17.4: SubAgentResult.completed carries the summary and is linked SPAWNED_BY")
    void result_completed() {
        // Oracle: AC-17.4 / INV-11 — a successful result carries the summary; AC-17.5 — the edge
        // is SPAWNED_BY.
        SubAgentResult result = SubAgentResult.completed("child-1", "the summary");

        assertTrue(result.success(), "a completed result is successful");
        assertEquals("the summary", result.summary());
        assertEquals("child-1", result.childSessionId());
        assertEquals(EdgeType.SPAWNED_BY, result.edgeType(),
                "AC-17.5/INV-11: a sub-agent result is linked SPAWNED_BY");
    }

    @Test
    @DisplayName("AC-17.6: SubAgentResult.failed carries a failure summary and is not successful")
    void result_failed() {
        SubAgentResult result = SubAgentResult.failed("child-1", "it exceeded its budget");

        assertFalse(result.success(), "AC-17.6: a failed result is not successful");
        assertEquals("it exceeded its budget", result.summary());
        assertEquals(EdgeType.SPAWNED_BY, result.edgeType());
    }

    @Test
    @DisplayName("SubAgentResult rejects a blank child session id and a blank summary")
    void result_validates() {
        assertThrows(IllegalArgumentException.class,
                () -> SubAgentResult.completed(" ", "s"), "a blank childSessionId is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> SubAgentResult.failed("c", " "), "a blank summary is rejected");
    }

    // --- ChildLoopContext ------------------------------------------------------------

    @Test
    @DisplayName("ChildLoopContext bundles the child's own log, fresh gate, model, and prompt")
    void childContext_accessors() {
        // Oracle: ADR-0010 — the context carries the child's isolated collaborators (its own
        // log, a fresh gate, resolved model, scoped prompt) and nothing of the parent's.
        EventLog childLog = EventLog.over(new StringWriter(), "child");
        PermissionGate gate = aGate();
        ChildLoopContext ctx = new ChildLoopContext("child-1", childLog, gate, "m", "the prompt");

        assertEquals("child-1", ctx.childSessionId());
        assertEquals(childLog, ctx.childLog());
        assertEquals(gate, ctx.childGate());
        assertEquals("m", ctx.modelId());
        assertEquals("the prompt", ctx.prompt());
    }

    @Test
    @DisplayName("ChildLoopContext rejects a null log/gate and a blank id/model/prompt")
    void childContext_validates() {
        EventLog log = EventLog.over(new StringWriter(), "child");
        PermissionGate gate = aGate();
        assertThrows(NullPointerException.class,
                () -> new ChildLoopContext("c", null, gate, "m", "p"), "a null log is rejected");
        assertThrows(NullPointerException.class,
                () -> new ChildLoopContext("c", log, null, "m", "p"), "a null gate is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new ChildLoopContext(" ", log, gate, "m", "p"), "a blank id is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new ChildLoopContext("c", log, gate, " ", "p"), "a blank model is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new ChildLoopContext("c", log, gate, "m", " "), "a blank prompt is rejected");
    }
}
