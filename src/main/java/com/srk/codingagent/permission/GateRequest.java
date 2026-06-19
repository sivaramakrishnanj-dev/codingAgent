package com.srk.codingagent.permission;

import com.srk.codingagent.persistence.OperationClass;
import java.util.Objects;

/**
 * The operation the permission gate evaluates: a single tool call, identified by its
 * correlating {@code toolUseId}, its tool {@code name}, its read/side-effecting
 * {@link OperationClass}, and — for {@code run_command} — the exact {@code command} string.
 * This is the input to {@link PermissionGate#evaluate}, and it carries everything the gate
 * needs to classify (Class R/X), to test the destructive denylist (the command string),
 * and to present the exact operation on a prompt (AC-10.1).
 *
 * <p><b>Presentation (AC-10.1).</b> When the gate must prompt, it shows the exact
 * operation: the full {@code command} string for a {@code run_command}, or the
 * {@code filePath} plus a {@code changeSummary} for a file write. Tools that are neither
 * carry just the tool name. The actual prompt UI is T-1.1; this type supplies the content
 * an {@link Approver} renders.
 *
 * @param toolUseId     the correlating tool-use id; non-blank.
 * @param toolName      the tool name (e.g. {@code run_command}, {@code write_file}); non-blank.
 * @param operationClass the read/side-effecting classification, from the tool's
 *                       {@link com.srk.codingagent.tool.ToolHandler#operationClass()};
 *                       must not be {@code null}.
 * @param command       the command string, for a {@code run_command}; {@code null} for any
 *                       other tool.
 * @param filePath      the target file path, for a file write; {@code null} otherwise.
 * @param changeSummary a short human-readable change summary for a file write (AC-10.1);
 *                       {@code null} otherwise.
 */
public record GateRequest(
        String toolUseId,
        String toolName,
        OperationClass operationClass,
        String command,
        String filePath,
        String changeSummary) {

    /**
     * Validates the request.
     *
     * @throws NullPointerException     if {@code operationClass} is {@code null}.
     * @throws IllegalArgumentException if {@code toolUseId} or {@code toolName} is blank.
     */
    public GateRequest {
        requireNonBlank(toolUseId, "toolUseId");
        requireNonBlank(toolName, "toolName");
        Objects.requireNonNull(operationClass, "operationClass");
    }

    /**
     * A request for a {@code run_command} tool call.
     *
     * @param toolUseId the correlating tool-use id; non-blank.
     * @param command   the command string the model wants to run; non-blank.
     * @return the gate request.
     */
    public static GateRequest forCommand(String toolUseId, String command) {
        requireNonBlank(command, "command");
        return new GateRequest(toolUseId, com.srk.codingagent.tool.RunCommandTool.NAME,
                OperationClass.SIDE_EFFECTING, command, null, null);
    }

    /**
     * A request for a {@code write_file} tool call.
     *
     * @param toolUseId     the correlating tool-use id; non-blank.
     * @param filePath      the workspace-confined target file path; non-blank.
     * @param changeSummary a short change summary for the prompt (AC-10.1); non-blank.
     * @return the gate request.
     */
    public static GateRequest forWrite(String toolUseId, String filePath, String changeSummary) {
        requireNonBlank(filePath, "filePath");
        requireNonBlank(changeSummary, "changeSummary");
        return new GateRequest(toolUseId, com.srk.codingagent.tool.WriteFileTool.NAME,
                OperationClass.SIDE_EFFECTING, null, filePath, changeSummary);
    }

    /**
     * A request for any other tool call, classified by the tool's own operation class.
     *
     * @param toolUseId      the correlating tool-use id; non-blank.
     * @param toolName       the tool name; non-blank.
     * @param operationClass the tool's read/side-effecting class; must not be {@code null}.
     * @return the gate request.
     */
    public static GateRequest forTool(String toolUseId, String toolName, OperationClass operationClass) {
        return new GateRequest(toolUseId, toolName, operationClass, null, null, null);
    }

    /**
     * Renders the exact operation for a confirmation prompt (AC-10.1): the command string,
     * or the file path with its change summary, or the tool name.
     *
     * @return a one-line presentation of the operation; never {@code null}.
     */
    public String presentation() {
        if (command != null) {
            return toolName + ": " + command;
        }
        if (filePath != null) {
            return toolName + ": " + filePath + " (" + changeSummary + ")";
        }
        return toolName;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }
}
