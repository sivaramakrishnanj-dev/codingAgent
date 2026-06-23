package com.srk.codingagent.loop;

import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.model.converse.ConverseMessage;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.GateDecision;
import com.srk.codingagent.permission.GateRequest;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.EventPayload;
import com.srk.codingagent.persistence.ModelResponsePayload;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.StopReason;
import com.srk.codingagent.persistence.ToolResultPayload;
import com.srk.codingagent.persistence.ToolResultStatus;
import com.srk.codingagent.persistence.ToolUsePayload;
import com.srk.codingagent.persistence.UserMessagePayload;
import com.srk.codingagent.tool.RunCommandTool;
import com.srk.codingagent.tool.ToolRegistry;
import com.srk.codingagent.tool.WriteFileTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Agent Loop (component C2, ADR-0001) — the system's heartbeat. It drives the
 * Converse client-side tool-use cycle (state machine A) with the permission gate inline
 * and every block logged before it acts, composing the four collaborators it is handed:
 * the {@link ModelClient} (T-0.5), the {@link ToolRegistry} (T-0.6), the
 * {@link PermissionGate} (T-0.7), the {@link EventLog} (T-0.4), and the
 * {@link OutputDisposer} (T-1.5).
 *
 * <p><b>The cycle (state machine A, 02-architecture.md § 2/§ 3.1).</b> Given an initial
 * user prompt, the loop appends a {@code USER_MESSAGE} (T1), then repeats: call
 * {@link ModelClient#converse} (S1), append {@code MODEL_RESPONSE} + {@code MODEL_USAGE}
 * (T2/T3), and dispatch on the turn's {@link StopReason}:
 * <ul>
 *   <li><b>{@link StopReason#TOOL_USE} (T2 → S2).</b> For each {@code toolUse} block in
 *       the response, the loop logs a {@code TOOL_USE} digest, routes a
 *       {@link GateRequest} through the gate and logs the {@code PERMISSION_DECISION}
 *       (T6/T7/T8), and — only on approve — dispatches the tool, logs its
 *       {@code TOOL_RESULT} with the <em>full</em> output (T9), and reduces an oversized
 *       result for context via the {@link OutputDisposer} (US-19, ADR-0006). A denial logs
 *       a {@code TOOL_RESULT(denied)} and runs no
 *       handler (T8, CT-SM-2). After every block, the batched tool results are sent back
 *       as one user message and the loop re-calls (T10).</li>
 *   <li><b>{@link StopReason#END_TURN} / {@link StopReason#STOP_SEQUENCE} (T3 → S5).</b>
 *       The loop returns the final assistant text. {@code stop_sequence} is treated as
 *       {@code end_turn} (§ 3.1).</li>
 *   <li><b>Budget seam (T13 → machine B).</b> After each turn's usage is logged, the loop
 *       consults the {@link BudgetGuard}; on {@link BudgetGuard.Decision#COMPACT} it invokes
 *       the {@link CompactionSeam} (summarize → derive → continue, ADR-0006). On a successful
 *       derive (T14) the loop continues driving in the derived session; on a compaction failure
 *       (T15) it surfaces. With the {@link BudgetGuard#NONE} / {@link CompactionSeam#NONE}
 *       wiring it never compacts.</li>
 *   <li><b>Edge reasons (T4/T5 → S6/S7).</b> {@code max_tokens},
 *       {@code model_context_window_exceeded}, {@code guardrail_intervened},
 *       {@code content_filtered}, and the {@code malformed_*} reasons are surfaced as a
 *       {@link LoopOutcome.Kind#SURFACED} outcome; the bounded repair-retry (state machine A
 *       T5) is deliberately NOT built here.</li>
 * </ul>
 *
 * <p><b>Log-before-act (INV-2, CT-INV-2).</b> Every event is appended via
 * {@link EventLog#append} (which flushes per event, T-0.4) <em>before</em> the loop acts
 * on its consequence: the assistant {@code MODEL_RESPONSE} is logged before any tool is
 * dispatched, and a tool's {@code PERMISSION_DECISION} is logged before the tool runs.
 *
 * <p><b>Gate-in-the-middle (INV-8).</b> The loop is the only caller of
 * {@link ToolRegistry#dispatch}, and it calls it only after a {@link GateDecision} with
 * {@link GateDecision#approved()} true — so no side-effecting tool runs without a
 * preceding {@code PERMISSION_DECISION} (ADR-0004).
 *
 * <p><b>Pairing (INV-6).</b> Every {@code TOOL_RESULT} the loop appends and every tool
 * result block it batches back carries the {@code toolUseId} of the {@code toolUse} block
 * it answers.
 *
 * <p><b>Boundary-captured timestamps (ADR-0005).</b> The loop never calls
 * {@code Instant.now()}; it draws every event's timestamp from the injected
 * {@code clock} {@link Supplier}, so a run is reproducible and tests are deterministic.
 *
 * <p><b>Out of scope (later tasks).</b> Exit-code dispatch and one-shot vs. REPL (S8 — the
 * loop returns a {@link LoopOutcome}, it does not call {@code System.exit}), {@code SIGINT}
 * handling, and sub-agent orchestration are not implemented here; the loop leaves clean seams
 * for them. Compaction (S6) is delegated to the injected {@link CompactionSeam}: the loop owns
 * only consulting it at T13 and continuing-or-surfacing on its result.
 *
 * <p>Not thread-safe: one loop drives one session on a single thread (the C2 invariant —
 * one in-flight model call per conversation).
 */
public final class AgentLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentLoop.class);

    /** The {@code status} of a tool result the model never asked to run because the gate denied it. */
    private static final String DENIED_RESULT_CONTENT = "permission denied";

    private final ModelClient modelClient;
    private final ToolRegistry tools;
    private final PermissionGate gate;
    private final EventLog log;
    private final Supplier<String> clock;
    private final BudgetGuard budgetGuard;
    private final CompactionSeam compaction;
    private final OutputDisposer disposer;
    private final String modelId;
    private final List<String> system;

    /**
     * Creates a loop over its composed collaborators with no compaction wired
     * ({@link CompactionSeam#NONE} — a {@code COMPACT} signal surfaces, as it did before a
     * compaction seam existed). Equivalent to
     * {@link #AgentLoop(ModelClient, ToolRegistry, PermissionGate, EventLog, Supplier,
     * BudgetGuard, CompactionSeam, OutputDisposer, String, List)} with
     * {@link CompactionSeam#NONE}.
     *
     * @param modelClient the Converse adapter (T-0.5); must not be {@code null}.
     * @param tools       the tool registry (T-0.6), whose {@code toolConfig} is sent on
     *                    every call and whose handlers receive approved {@code toolUse}s;
     *                    must not be {@code null}.
     * @param gate        the permission gate (T-0.7) consulted before every side effect;
     *                    must not be {@code null}.
     * @param log         the session event log (T-0.4); must not be {@code null}.
     * @param clock       the timestamp source for every appended event (ADR-0005 — the
     *                    loop never calls {@code Instant.now()}); must not be {@code null}.
     * @param budgetGuard the budget-check seam consulted after each turn (state machine A
     *                    T13); use {@link BudgetGuard#NONE} for the no-compaction wiring;
     *                    must not be {@code null}.
     * @param disposer    the output disposer (C6, US-19) consulted between persisting a tool
     *                    result and returning it to the model context: it reduces an oversized
     *                    result (head+tail) for context while the full output stays in the log
     *                    (AC-19.1/19.2); must not be {@code null}.
     * @param modelId     the Bedrock model id sent on every Converse call; non-blank.
     * @param system      the system-prompt blocks, or {@code null} for none.
     * @throws NullPointerException     if any required argument is {@code null}.
     * @throws IllegalArgumentException if {@code modelId} is blank.
     */
    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry tools,
            PermissionGate gate,
            EventLog log,
            Supplier<String> clock,
            BudgetGuard budgetGuard,
            OutputDisposer disposer,
            String modelId,
            List<String> system) {
        this(modelClient, tools, gate, log, clock, budgetGuard, CompactionSeam.NONE,
                disposer, modelId, system);
    }

    /**
     * Creates a loop over its composed collaborators, including the compaction seam
     * (state machine A T13&rarr;T14/T15).
     *
     * @param modelClient the Converse adapter (T-0.5); must not be {@code null}.
     * @param tools       the tool registry (T-0.6), whose {@code toolConfig} is sent on
     *                    every call and whose handlers receive approved {@code toolUse}s;
     *                    must not be {@code null}.
     * @param gate        the permission gate (T-0.7) consulted before every side effect;
     *                    must not be {@code null}.
     * @param log         the session event log (T-0.4); must not be {@code null}.
     * @param clock       the timestamp source for every appended event (ADR-0005 — the
     *                    loop never calls {@code Instant.now()}); must not be {@code null}.
     * @param budgetGuard the budget-check seam consulted after each turn (state machine A
     *                    T13); use {@link BudgetGuard#NONE} for the no-compaction wiring;
     *                    must not be {@code null}.
     * @param compaction  the compaction seam invoked when {@code budgetGuard} returns
     *                    {@link BudgetGuard.Decision#COMPACT} (state machine A T13&rarr;machine
     *                    B): on success the loop continues in the derived session (T14), on
     *                    failure it surfaces (T15). Use {@link CompactionSeam#NONE} for the
     *                    no-compaction wiring; must not be {@code null}.
     * @param disposer    the output disposer (C6, US-19) consulted between persisting a tool
     *                    result and returning it to the model context: it reduces an oversized
     *                    result (head+tail) for context while the full output stays in the log
     *                    (AC-19.1/19.2); must not be {@code null}.
     * @param modelId     the Bedrock model id sent on every Converse call; non-blank.
     * @param system      the system-prompt blocks, or {@code null} for none.
     * @throws NullPointerException     if any required argument is {@code null}.
     * @throws IllegalArgumentException if {@code modelId} is blank.
     */
    public AgentLoop(
            ModelClient modelClient,
            ToolRegistry tools,
            PermissionGate gate,
            EventLog log,
            Supplier<String> clock,
            BudgetGuard budgetGuard,
            CompactionSeam compaction,
            OutputDisposer disposer,
            String modelId,
            List<String> system) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.log = Objects.requireNonNull(log, "log");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budgetGuard = Objects.requireNonNull(budgetGuard, "budgetGuard");
        this.compaction = Objects.requireNonNull(compaction, "compaction");
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        if (Objects.requireNonNull(modelId, "modelId").isBlank()) {
            throw new IllegalArgumentException("modelId must be non-blank");
        }
        this.modelId = modelId;
        this.system = system == null ? null : List.copyOf(system);
    }

    /**
     * Runs the tool-use cycle from an initial user prompt until the model ends its turn
     * (or an edge stop reason is surfaced), and returns the terminal {@link LoopOutcome}.
     *
     * @param userPrompt the developer's prompt that starts the turn; non-blank.
     * @return the terminal outcome (a completed final answer, or a surfaced edge
     *         condition); never {@code null}.
     * @throws NullPointerException     if {@code userPrompt} is {@code null}.
     * @throws IllegalArgumentException if {@code userPrompt} is blank.
     */
    public LoopOutcome run(String userPrompt) {
        if (Objects.requireNonNull(userPrompt, "userPrompt").isBlank()) {
            throw new IllegalArgumentException("userPrompt must be non-blank");
        }
        // T1 (S0 -> S1): record the user turn, then seed the transcript with it. The event
        // is appended (and flushed) before the first model call acts on it (INV-2).
        List<ContentBlock> promptBlocks = List.of(ContentBlock.text(userPrompt));
        append(new UserMessagePayload(promptBlocks));
        List<ConverseMessage> transcript = new ArrayList<>();
        transcript.add(ConverseMessage.user(promptBlocks));
        LOGGER.info("Agent loop started: modelId={}, tools={}", modelId, tools.toolNames());

        return drive(transcript);
    }

    /** The S1 -> ... loop: call the model, log the turn, dispatch on stop reason, repeat. */
    private LoopOutcome drive(List<ConverseMessage> seedTranscript) {
        // A mutable handle so a successful compaction (T14) can swap the live transcript for the
        // derived session's replayed messages[] and the loop keeps driving in the child.
        List<ConverseMessage> transcript = seedTranscript;
        while (true) {
            // S1: one in-flight model call per conversation (the full transcript is resent;
            // Converse is stateless).
            ModelClient.Turn turn = modelClient.converse(
                    modelId, transcript, system, tools.toToolConfiguration());
            ModelResponsePayload response = turn.response();

            // T2/T3: log the assistant turn (MODEL_RESPONSE then MODEL_USAGE) BEFORE acting
            // on it (INV-2 — the response is durably recorded before tools dispatch), then
            // add it to the transcript the next call resends.
            append(response);
            append(turn.usage());
            transcript.add(ConverseMessage.assistant(response.content()));

            // T6->T9 (complete the turn BEFORE the budget seam): on a TOOL_USE turn the
            // assistant turn just appended ends the transcript in a dangling toolUse block. A
            // tool_use turn is NOT complete until its toolResult is appended (state machine B
            // LT1 — "a turn completes"; § 6.A.1 / INV-6 — every toolUse must be followed by its
            // matching toolResult). So dispatch the tool(s) and append the batched toolResult
            // here, pairing the transcript, BEFORE consulting the budget guard. This guarantees
            // the compaction seam below always summarizes a well-formed (toolUse/toolResult-
            // paired) transcript, never a dangling toolUse that live Bedrock rejects with a
            // ValidationException (the D4 regression). The budget signal is preserved: it is
            // still evaluated once for this turn's usage immediately after, at the now-paired
            // boundary.
            if (response.stopReason() == StopReason.TOOL_USE) {
                transcript.add(dispatchTools(response.content()));
            }

            // T13 (budget seam): after the turn's usage is logged AND the transcript is in a
            // well-formed (paired) state for this turn, consult the guard. On COMPACT (machine A
            // T13 -> machine B) invoke the compaction seam: summarize -> derive -> the derived
            // session's replayed messages[] (AC-18.1/18.4, INV-4/5/7). On success (LT3, T14)
            // continue driving in the derived conversation; on failure (LT4 -> LT7, T15) surface
            // so the one-shot boundary exits 5 (context-exhausted). With BudgetGuard.NONE the
            // seam is never consulted (the no-compaction wiring). Because a TOOL_USE turn's
            // toolResult has already been appended above, the transcript handed to the seam is
            // toolUse/toolResult-paired at every stop reason (T13's source is S1/S0 -> S6, a
            // between-complete-turns boundary).
            if (budgetGuard.evaluate(turn.usage()) == BudgetGuard.Decision.COMPACT) {
                LOGGER.info("Budget guard signalled compaction; invoking the compaction seam (T13)");
                CompactionSeam.CompactionResult result =
                        compaction.compact(transcript, response.stopReason());
                if (!result.continued()) {
                    LOGGER.info("Compaction did not continue; surfacing stopReason={} (T15)",
                            result.surfacedStopReason());
                    return LoopOutcome.surfaced(result.surfacedStopReason());
                }
                // T14 -> S1: continue in the derived session. The seam returned the derived
                // session's replayed transcript (summary context + recent-tail verbatim turns,
                // INV-7 signatures intact); the next Converse call happens in the child.
                LOGGER.info("Compaction derived a successor session; continuing the loop in it (T14)");
                transcript = new ArrayList<>(result.derivedTranscript());
                continue;
            }

            switch (response.stopReason()) {
                case TOOL_USE -> {
                    // T10 -> S1: the toolResult was appended above (completing the turn before
                    // the budget seam); just re-call the model in the next iteration.
                }
                case END_TURN, STOP_SEQUENCE -> {
                    // T3 -> S5: stop_sequence is treated as end_turn (§ 3.1).
                    return LoopOutcome.completed(finalText(response.content()));
                }
                default -> {
                    // T4/T5 -> S6/S7: max_tokens, model_context_window_exceeded,
                    // guardrail_intervened, content_filtered, malformed_* are surfaced
                    // without running tools (the bounded repair-retry handler is a later
                    // task; the context-window backstop is handled at the budget seam above).
                    // The loop stops and reports the reason for T-0.9 to map.
                    LOGGER.warn("Surfacing edge stop reason without acting: {}", response.stopReason());
                    return LoopOutcome.surfaced(response.stopReason());
                }
            }
        }
    }

    /**
     * S2: route each {@code toolUse} block through the gate and (on approve) the registry,
     * logging the per-block events in {@code TOOL_USE -> PERMISSION_DECISION -> TOOL_RESULT}
     * order (the contract fixture's ordering), then batch the results into one user message
     * for the re-call (T10).
     */
    private ConverseMessage dispatchTools(List<ContentBlock> content) {
        List<ContentBlock> toolResults = new ArrayList<>();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.ToolUse toolUse) {
                toolResults.add(handleToolUse(toolUse));
            }
        }
        // T10: append the batched tool results as one user message BEFORE the re-call acts
        // on them (INV-2), and seed the transcript so the next call resends them.
        append(new UserMessagePayload(toolResults));
        return ConverseMessage.user(toolResults);
    }

    /** S3/S4 for one block: log the toolUse digest, gate, then dispatch-or-deny (INV-8). */
    private ContentBlock handleToolUse(ContentBlock.ToolUse toolUse) {
        // The TOOL_USE digest event (fixture seq 4): logged before gating so the trace
        // records the decision the agent made before any side effect (INV-2).
        append(new ToolUsePayload(toolUse.toolUseId(), toolUse.name(), toolUse.input()));

        // T6 -> T7/T8: evaluate the gate and log the PERMISSION_DECISION BEFORE executing
        // (INV-8 gate-before-side-effect; INV-2 log-before-act).
        GateRequest request = gateRequestFor(toolUse);
        GateDecision decision = gate.evaluate(request);
        append(decision.toPayload(toolUse.toolUseId()));

        if (decision.approved()) {
            // T7 -> S4 -> T9: the loop is the only caller of dispatch, only after approve
            // (INV-8). The result carries the toolUseId (INV-6).
            ContentBlock.ToolResult result = tools.dispatch(toolUse);
            // Output disposal (US-19, ADR-0006): persist the FULL output to the log first —
            // the log is the durable full store (AC-19.2) and append returns the stamped seq —
            // then reduce what enters the model context (AC-19.1). The full TOOL_RESULT event
            // is the un-truncated copy (truncated=false); the model-context block is reduced
            // (head+tail) only when the output exceeds the cap, pointing back to the persisted
            // full output by its seq so the model can retrieve it (AC-19.3) rather than re-run.
            Event logged = append(
                    ToolResultPayload.of(toolUse.toolUseId(), statusOf(result), result.content()));
            return disposer.reduceForContext(result, logged.seq());
        }

        // T8 -> S2: a denial logs a TOOL_RESULT(denied) event and runs no handler (CT-SM-2).
        // The denied result is sent back so the model can react (no side effect occurred).
        // The EVENT records the broad status DENIED (ToolResultStatus enum: ok/error/denied),
        // but the content block resent to the model carries the narrower content-block status
        // 'error' the Converse wire accepts (ContentBlock.ToolResult is ok/error only), with a
        // message naming the denial so the model understands why.
        LOGGER.info("Tool '{}' (toolUseId {}) denied by the gate; no handler run",
                toolUse.name(), toolUse.toolUseId());
        append(ToolResultPayload.of(
                toolUse.toolUseId(), ToolResultStatus.DENIED, DENIED_RESULT_CONTENT));
        return ContentBlock.toolResult(
                toolUse.toolUseId(), ToolResultStatus.ERROR.wireValue(), DENIED_RESULT_CONTENT);
    }

    /**
     * Builds the gate request for a {@code toolUse} by tool kind: {@code run_command} keys
     * on its command string, {@code write_file} on its path + a change summary, and any
     * other tool on its registered operation class (a Class-R read auto-approves). An
     * unknown tool is gated as side-effecting (fail-closed) so an unregistered name cannot
     * slip past the gate; its {@code toolUse} still routes to {@link ToolRegistry#dispatch}
     * for the structured-error result.
     *
     * <p>A {@code run_command}/{@code write_file} call missing its required input (a
     * malformed {@code toolUse}) falls back to a coarse side-effecting tool request so the
     * gate still runs (INV-8 stays satisfied — fail-closed); the missing-input error is
     * then produced by {@link ToolRegistry#dispatch} as a structured tool error rather than
     * crashing the loop.
     */
    private GateRequest gateRequestFor(ContentBlock.ToolUse toolUse) {
        String name = toolUse.name();
        String command = stringInput(toolUse, "command");
        if (RunCommandTool.NAME.equals(name) && command != null) {
            return GateRequest.forCommand(toolUse.toolUseId(), command);
        }
        String path = stringInput(toolUse, "path");
        if (WriteFileTool.NAME.equals(name) && path != null) {
            return GateRequest.forWrite(toolUse.toolUseId(), path, "write " + path);
        }
        OperationClass operationClass = tools.operationClass(name).orElse(OperationClass.SIDE_EFFECTING);
        return GateRequest.forTool(toolUse.toolUseId(), name, operationClass);
    }

    /** The non-blank string value of an input field, or {@code null} when absent/blank/non-string. */
    private static String stringInput(ContentBlock.ToolUse toolUse, String field) {
        Object value = toolUse.input().get(field);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static ToolResultStatus statusOf(ContentBlock.ToolResult result) {
        return ToolResultStatus.ERROR.wireValue().equals(result.status())
                ? ToolResultStatus.ERROR
                : ToolResultStatus.OK;
    }

    /** The concatenated text of an end_turn response's text blocks (empty when none). */
    private static String finalText(List<ContentBlock> content) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.Text textBlock) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(textBlock.text());
            }
        }
        return text.toString();
    }

    /** Appends an event with a boundary-captured timestamp (ADR-0005); flushed per event. */
    private Event append(EventPayload payload) {
        return log.append(new Event(log.nextSeq(), clock.get(), payload));
    }
}
