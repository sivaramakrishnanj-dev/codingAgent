package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.subagent.ChildAgentLoopFactory;
import com.srk.codingagent.subagent.ChildAgentRun;
import com.srk.codingagent.subagent.SubAgentOrchestrator;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SpawnSubAgentTool} — the Class-X {@code spawn_subagent} tool
 * (ADR-0010, AC-17.1). The SUT is a real {@link SpawnSubAgentTool} over a <em>real</em>
 * {@link SubAgentOrchestrator}; only the child run (a {@link ChildAgentRun} seam) is a
 * controllable double. Nothing the tool or orchestrator owns is mocked.
 *
 * <p><b>Oracles.</b>
 * <ul>
 *   <li>AC-5.2 / ADR-0004: the tool is Class X ({@link OperationClass#SIDE_EFFECTING}) so the
 *       loop gates it.</li>
 *   <li>AC-17.1: a {@code prompt} spawns a child and the tool returns the child's summary.</li>
 *   <li>AC-17.4 / INV-11 / D2: the returned value is a plain {@link String} (the summary) —
 *       which routes to the Converse {@code text} member, never {@code json}.</li>
 *   <li>AC-17.6: a failed child yields a {@code [sub-agent failed]} string, so the model can
 *       decide a next step.</li>
 *   <li>AC-17.2: an optional {@code model} override is forwarded to the orchestrator.</li>
 *   <li>04-apis § 3: a missing/blank {@code prompt} is a tool error, not a crash.</li>
 * </ul>
 */
class SpawnSubAgentToolTest {

    private static final String REPO = "github.com/example/widget";
    private static final String PARENT_MODEL = "anthropic.claude-opus-4-8";
    private static final String CHILD_ID = "child-session-1";
    private static final String TS = "2026-06-22T09:00:00Z";

    private static Approver alwaysApprove() {
        return req -> PermissionDecisionOutcome.APPROVE;
    }

    /** Builds a real orchestrator whose child run returns the given outcome, recording the context. */
    private SubAgentOrchestrator orchestratorReturning(
            SessionStore store, AtomicReference<String> seenModelId, LoopOutcome childOutcome) {
        ChildAgentLoopFactory factory = ctx -> {
            seenModelId.set(ctx.modelId());
            return () -> childOutcome;
        };
        return new SubAgentOrchestrator(
                store, EventLog.over(new StringWriter(), "parent"), factory,
                GrantStore.forSession("parent"), PermissionMode.ASK_EVERY_TIME, alwaysApprove(),
                REPO, PARENT_MODEL, () -> CHILD_ID, () -> TS, 1, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("AC-5.2/ADR-0004: spawn_subagent is Class X (side-effecting) so the loop gates it")
    void isClassX(@TempDir Path storeRoot) {
        // Oracle: AC-5.2 / ADR-0004 — spawning a sub-agent (which may run tools) is a side
        // effect the permission mode gates, so the tool must declare SIDE_EFFECTING.
        SpawnSubAgentTool tool = new SpawnSubAgentTool(orchestratorReturning(
                new SessionStore(storeRoot), new AtomicReference<>(), LoopOutcome.completed("ok")));

        assertEquals(OperationClass.SIDE_EFFECTING, tool.operationClass(),
                "AC-5.2/ADR-0004: spawn_subagent is Class X");
        assertEquals("spawn_subagent", tool.name(), "the tool name is spawn_subagent");
    }

    @Test
    @DisplayName("AC-17.1/AC-17.4: the tool returns the child's summary as a plain string")
    void returnsChildSummaryAsPlainString(@TempDir Path storeRoot) {
        // Oracle: AC-17.1 ("return a summarized result") + AC-17.4 / INV-11 / D2. The completed
        // child's final text is the summary; the tool returns it as a plain String, so the loop
        // sends it on the Converse text member (D2). Expected text traces to the child outcome +
        // the AC, not to the tool's internals.
        SpawnSubAgentTool tool = new SpawnSubAgentTool(orchestratorReturning(
                new SessionStore(storeRoot), new AtomicReference<>(),
                LoopOutcome.completed("Summarized: applied the patch.")));

        Object result = tool.handle(Map.of("prompt", "do the subtask"));

        assertTrue(result instanceof String,
                "AC-17.4/D2: the result is a plain String (routes to the Converse text member)");
        assertEquals("Summarized: applied the patch.", result,
                "AC-17.1/AC-17.4: the tool returns the child's summary");
    }

    @Test
    @DisplayName("AC-17.6: a failed child yields a [sub-agent failed] summary, not a crash")
    void failedChildYieldsFailureSummary(@TempDir Path storeRoot) {
        // Oracle: AC-17.6 — a failed/surfaced child returns a failure result; the tool surfaces
        // it as a [sub-agent failed] string so the model decides a next step rather than hanging.
        // A SURFACED child outcome maps to a failure result inside the orchestrator.
        SpawnSubAgentTool tool = new SpawnSubAgentTool(orchestratorReturning(
                new SessionStore(storeRoot), new AtomicReference<>(),
                LoopOutcome.surfaced(com.srk.codingagent.persistence.StopReason.MAX_TOKENS)));

        Object result = tool.handle(Map.of("prompt", "do the subtask"));

        assertTrue(result instanceof String, "the tool always returns a string result");
        assertTrue(((String) result).startsWith("[sub-agent failed]"),
                "AC-17.6: a failed child is surfaced as [sub-agent failed] so the model can react");
    }

    @Test
    @DisplayName("AC-17.2: an optional model override is forwarded to the orchestrator")
    void modelOverrideForwarded(@TempDir Path storeRoot) {
        // Oracle: AC-17.2 — a sub-agent may run a different/cheaper model. The optional 'model'
        // input must reach the orchestrator as the child's resolved model id.
        AtomicReference<String> seenModelId = new AtomicReference<>();
        SpawnSubAgentTool tool = new SpawnSubAgentTool(orchestratorReturning(
                new SessionStore(storeRoot), seenModelId, LoopOutcome.completed("ok")));

        Map<String, Object> input = new HashMap<>();
        input.put("prompt", "subtask");
        input.put("model", "anthropic.claude-haiku-4-8");
        tool.handle(input);

        assertEquals("anthropic.claude-haiku-4-8", seenModelId.get(),
                "AC-17.2: the model override reaches the child as its resolved model id");
    }

    @Test
    @DisplayName("AC-17.2: with no model override the child inherits the parent's model")
    void noOverrideInheritsParentModel(@TempDir Path storeRoot) {
        // Oracle: AC-17.2 / ADR-0010 — "v1 default: inherit parent model unless overridden".
        AtomicReference<String> seenModelId = new AtomicReference<>();
        SpawnSubAgentTool tool = new SpawnSubAgentTool(orchestratorReturning(
                new SessionStore(storeRoot), seenModelId, LoopOutcome.completed("ok")));

        tool.handle(Map.of("prompt", "subtask"));

        assertEquals(PARENT_MODEL, seenModelId.get(),
                "AC-17.2: with no override the child inherits the parent's model");
    }

    @Test
    @DisplayName("04-apis § 3: a missing prompt is a tool error (ToolInvocationException), not a crash")
    void missingPromptIsToolError(@TempDir Path storeRoot) {
        // Oracle: 04-apis § 3 Notes — invalid input surfaces as a tool error the registry turns
        // into an error tool result, not an exception that crashes the loop.
        SpawnSubAgentTool tool = new SpawnSubAgentTool(orchestratorReturning(
                new SessionStore(storeRoot), new AtomicReference<>(), LoopOutcome.completed("ok")));

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of()),
                "a missing required 'prompt' is a tool error");
    }

    @Test
    @DisplayName("the tool renders a valid input schema requiring prompt")
    void inputSchemaRequiresPrompt(@TempDir Path storeRoot) {
        // Oracle: 04-apis § 3 — the spawn_subagent input contract requires 'prompt'. Assert the
        // rendered schema marks prompt required (the registry renders this into the toolSpec).
        SpawnSubAgentTool tool = new SpawnSubAgentTool(orchestratorReturning(
                new SessionStore(storeRoot), new AtomicReference<>(), LoopOutcome.completed("ok")));

        var schema = tool.inputSchema();
        var required = schema.asMap().get("required").asList();
        assertTrue(required.stream().anyMatch(d -> "prompt".equals(d.asString())),
                "the input schema marks 'prompt' as required");
    }
}
