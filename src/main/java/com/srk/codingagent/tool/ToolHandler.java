package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.OperationClass;
import java.util.Map;
import software.amazon.awssdk.core.document.Document;

/**
 * One built-in tool the model may call (component C7, ADR-0001). A tool is a registry
 * entry — {@code name}, {@code description}, and a JSON-Schema {@code inputSchema} — that
 * the {@link ToolRegistry} renders to a Converse {@code toolSpec} and dispatches the
 * model's {@code toolUse.input} to. Each tool also declares its
 * {@link #operationClass() class} (read vs side-effecting) so the permission gate (C8,
 * T-0.7) can classify it; this task records the class but does <em>not</em> gate on it.
 *
 * <p>04-apis § 3 fixes the three tools this task delivers:
 * <ul>
 *   <li>{@code read_file} — Class R ({@link OperationClass#READ}); inputs {@code path},
 *       optional {@code offset}/{@code limit}.</li>
 *   <li>{@code write_file} — Class X ({@link OperationClass#SIDE_EFFECTING}); inputs
 *       {@code path}, {@code content}.</li>
 *   <li>{@code run_command} — Class X; input {@code command}.</li>
 * </ul>
 *
 * <p>A handler's {@link #handle(Map)} returns the JSON-serializable content that becomes
 * a {@link com.srk.codingagent.persistence.ContentBlock.ToolResult}'s {@code content}
 * when the registry dispatches a {@code toolUse}. A handler signals a tool-level failure
 * the model should react to by throwing a {@link ToolInvocationException}; the registry
 * turns that into an {@code error}-status tool result rather than letting it crash the
 * loop (04-apis § 3 Notes).
 */
public interface ToolHandler {

    /**
     * The tool's name, as it appears in the rendered {@code toolSpec} and in the model's
     * {@code toolUse.name}.
     *
     * @return the tool name; non-blank.
     */
    String name();

    /**
     * A one-line description rendered into the {@code toolSpec} so the model knows when
     * to call the tool.
     *
     * @return the tool description; non-blank.
     */
    String description();

    /**
     * The tool's JSON-Schema input contract, as an SDK {@link Document}, rendered into
     * the {@code toolSpec.inputSchema} (ADR-0001).
     *
     * @return the input schema document; never {@code null}.
     */
    Document inputSchema();

    /**
     * Whether this tool is read-only or side-effecting, so the permission gate (T-0.7)
     * can classify it. {@code read_file} is {@link OperationClass#READ}; {@code write_file}
     * and {@code run_command} are {@link OperationClass#SIDE_EFFECTING} (04-apis § 3,
     * AC-5.2).
     *
     * @return the operation class; never {@code null}.
     */
    OperationClass operationClass();

    /**
     * Runs the tool against the model-supplied input.
     *
     * @param input the {@code toolUse.input} object (JSON matching {@link #inputSchema()});
     *              never {@code null}.
     * @return the JSON-serializable result content for the {@code toolResult}.
     * @throws ToolInvocationException if the input is invalid or the tool fails in a way
     *                                 the model should react to (returned as an
     *                                 {@code error}-status tool result by the registry).
     */
    Object handle(Map<String, Object> input);
}
