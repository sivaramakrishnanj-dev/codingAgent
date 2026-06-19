package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.ContentBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

/**
 * The Tool Registry (component C7, ADR-0001): holds the built-in {@link ToolHandler}s,
 * renders them to a Converse {@link ToolConfiguration} the model client sends
 * ({@link com.srk.codingagent.model.converse.ConverseWireMapper#toRequest} consumes it),
 * and dispatches a model {@link ContentBlock.ToolUse} to the matching handler, producing
 * a {@link ContentBlock.ToolResult}.
 *
 * <p><b>Invariant (C7).</b> A tool's schema and its handler agree — both come from the
 * same {@link ToolHandler}, so a rendered {@code toolSpec} and the handler that receives
 * its {@code toolUse} cannot drift. An unknown tool name (a {@code toolUse} for a tool not
 * registered) produces a structured {@code error} tool result, never an exception that
 * crashes the loop.
 *
 * <p>The agent loop that calls Converse with this {@code toolConfig} and routes a
 * {@code toolUse} stop reason here is a later task (T-0.8); this task delivers the
 * registry, the rendering, and the dispatch. Authorization (the permission gate, T-0.7)
 * sits between the model's {@code toolUse} and {@link #dispatch}: a wired agent classifies
 * with {@link ToolHandler#operationClass()} and gates a Class-X tool before dispatching.
 */
public final class ToolRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRegistry.class);

    /** The tool-result status for a successful invocation (ContentBlock.ToolResult enum). */
    private static final String STATUS_OK = "ok";

    /** The tool-result status for a failed invocation (ContentBlock.ToolResult enum). */
    private static final String STATUS_ERROR = "error";

    private final Map<String, ToolHandler> handlers;

    private ToolRegistry(Map<String, ToolHandler> handlers) {
        this.handlers = handlers;
    }

    /**
     * Builds a registry from the given handlers, preserving registration order (so the
     * rendered {@code toolConfig} lists tools deterministically). Later tools register
     * through this same constructor; the order is the order supplied.
     *
     * @param handlers the tools to register; must not be {@code null} or contain a
     *                 {@code null}.
     * @return a registry over the handlers.
     * @throws NullPointerException     if {@code handlers} or any element is {@code null}.
     * @throws IllegalArgumentException if two handlers share a name (the registry keys by
     *                                  name; a collision would make dispatch ambiguous).
     */
    public static ToolRegistry of(List<ToolHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers");
        Map<String, ToolHandler> byName = new LinkedHashMap<>();
        for (ToolHandler handler : handlers) {
            Objects.requireNonNull(handler, "handler");
            ToolHandler previous = byName.putIfAbsent(handler.name(), handler);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate tool name: " + handler.name());
            }
        }
        return new ToolRegistry(byName);
    }

    /**
     * The registered tool names, in registration order.
     *
     * @return an unmodifiable list of tool names.
     */
    public List<String> toolNames() {
        return List.copyOf(handlers.keySet());
    }

    /**
     * Renders the registered tools to a Converse {@link ToolConfiguration}: each tool
     * becomes a {@code toolSpec {name, description, inputSchema}} (ADR-0001). The result
     * is what {@link com.srk.codingagent.model.converse.ConverseWireMapper#toRequest}
     * attaches to a request so the model can call the tools.
     *
     * @return the tool configuration; never {@code null}. When no tools are registered,
     *         the configuration has an empty tool list.
     */
    public ToolConfiguration toToolConfiguration() {
        List<Tool> tools = new ArrayList<>(handlers.size());
        for (ToolHandler handler : handlers.values()) {
            tools.add(Tool.fromToolSpec(ToolSpecification.builder()
                    .name(handler.name())
                    .description(handler.description())
                    .inputSchema(ToolInputSchema.fromJson(handler.inputSchema()))
                    .build()));
        }
        return ToolConfiguration.builder().tools(tools).build();
    }

    /**
     * Dispatches a model {@link ContentBlock.ToolUse} to the matching handler and wraps
     * the outcome as a {@link ContentBlock.ToolResult} correlated by {@code toolUseId}.
     *
     * <ul>
     *   <li>Unknown tool name → an {@code error} tool result naming the unknown tool (the
     *       loop does not crash — C7 invariant).</li>
     *   <li>Handler throws {@link ToolInvocationException} → an {@code error} tool result
     *       carrying the message (04-apis § 3: tool errors return as
     *       {@code toolResult {status: error}} so the model reacts).</li>
     *   <li>Handler returns normally → an {@code ok} tool result whose content is the
     *       handler's return value.</li>
     * </ul>
     *
     * @param toolUse the model's tool-call block; must not be {@code null}.
     * @return the correlated {@link ContentBlock.ToolResult}.
     * @throws NullPointerException if {@code toolUse} is {@code null}.
     */
    public ContentBlock.ToolResult dispatch(ContentBlock.ToolUse toolUse) {
        Objects.requireNonNull(toolUse, "toolUse");
        ToolHandler handler = handlers.get(toolUse.name());
        if (handler == null) {
            LOGGER.warn("Dispatch for unknown tool '{}' (toolUseId {})",
                    toolUse.name(), toolUse.toolUseId());
            return ContentBlock.toolResult(toolUse.toolUseId(), STATUS_ERROR,
                    "unknown tool: " + toolUse.name());
        }
        try {
            Object content = handler.handle(toolUse.input());
            return ContentBlock.toolResult(toolUse.toolUseId(), STATUS_OK, content);
        } catch (ToolInvocationException e) {
            LOGGER.warn("Tool '{}' (toolUseId {}) failed: {}",
                    toolUse.name(), toolUse.toolUseId(), e.getMessage());
            return ContentBlock.toolResult(toolUse.toolUseId(), STATUS_ERROR, e.getMessage());
        }
    }
}
