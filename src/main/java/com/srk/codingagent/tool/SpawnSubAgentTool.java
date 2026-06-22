package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.subagent.SubAgentOrchestrator;
import com.srk.codingagent.subagent.SubAgentResult;
import com.srk.codingagent.subagent.SubAgentSpec;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code spawn_subagent} tool (ADR-0010, AC-17.1): the Class-X tool the parent loop
 * dispatches to delegate a well-scoped, isolable subtask to a sub-agent. It is Class X
 * ({@link OperationClass#SIDE_EFFECTING}) — spawning a sub-agent that may itself run tools is
 * a side effect the permission mode gates (ADR-0004), so the loop routes this call through
 * the gate before invoking the handler, exactly as for {@code write_file}/{@code run_command}.
 *
 * <p>Inputs: {@code prompt} (required — the scoped task), optional {@code model} (a
 * different/cheaper model the child runs; default the parent's, AC-17.2), and optional
 * {@code budgetSeconds} (the child's wall-clock cap; default NFR-SUBAGENT-BUDGET, AC-17.6).
 * The handler builds a {@link SubAgentSpec} and invokes the {@link SubAgentOrchestrator},
 * which runs the nested child loop with isolated context, a fresh permission gate (no
 * inherited grants), and the budget, then returns the child's summary.
 *
 * <p><b>Summary-only result (AC-17.4, INV-11).</b> The handler returns the child's summary
 * as a plain {@link String} — never the child's transcript. As a plain string it routes
 * through the Converse {@code text} member, never {@code json} (D2-safe), when the parent
 * loop sends the tool result back to the model. A failed/over-budget child (AC-17.6) returns
 * a {@code [sub-agent failed]} summary so the model can decide a next step rather than
 * hanging.
 */
public final class SpawnSubAgentTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpawnSubAgentTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "spawn_subagent";

    private final SubAgentOrchestrator orchestrator;

    /**
     * Creates the tool over the sub-agent orchestrator it delegates to.
     *
     * @param orchestrator the orchestrator that runs the nested child loop (C13, ADR-0010);
     *                     must not be {@code null}.
     * @throws NullPointerException if {@code orchestrator} is {@code null}.
     */
    public SpawnSubAgentTool(SubAgentOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Spawn a sub-agent to perform a well-scoped, isolable subtask with its own "
                + "isolated context and budget, and return only its summarized result.";
    }

    @Override
    public Document inputSchema() {
        return ToolSchemas.spawnSubagent();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.SIDE_EFFECTING;
    }

    /**
     * Spawns the sub-agent for the {@code prompt} and returns its summary as a plain string.
     *
     * @param input the {@code toolUse.input}: requires {@code prompt}; optional {@code model}
     *              and {@code budgetSeconds}.
     * @return the child's summary string (D2-safe — a plain {@code String} routing to the
     *         Converse {@code text} member); a failure summary when the child failed or
     *         exceeded its budget (AC-17.6).
     * @throws ToolInvocationException if {@code prompt} is missing/blank, {@code model} is
     *                                 present but blank, or {@code budgetSeconds} is present
     *                                 but not a positive integer.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        String prompt = ToolInputs.requireString(input, "prompt");
        String model = ToolInputs.optionalString(input, "model");
        Integer budgetSeconds = ToolInputs.optionalPositiveInt(input, "budgetSeconds");
        Duration cap = budgetSeconds == null ? null : Duration.ofSeconds(budgetSeconds);

        SubAgentResult result = orchestrator.spawn(new SubAgentSpec(prompt, model, cap));
        LOGGER.info("spawn_subagent returned: childSessionId={}, success={}",
                result.childSessionId(), result.success());
        return result.success()
                ? result.summary()
                : "[sub-agent failed] " + result.summary();
    }
}
