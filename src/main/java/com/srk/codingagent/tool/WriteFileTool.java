package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.OperationClass;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code write_file} tool (component C9, 04-apis § 3): writes {@code content} to a
 * workspace-relative {@code path}, creating or replacing the file. It is Class X
 * ({@link OperationClass#SIDE_EFFECTING}) — a mutating edit the permission mode gates
 * (AC-5.2). This task records the class so the gate (T-0.7) can classify the tool; it
 * does <em>not</em> implement the gate, and a wired agent must route the call through the
 * gate before invoking the handler.
 *
 * <p>Inputs: {@code path} (required, workspace-relative) and {@code content} (required).
 * The result is a short "ok / diff summary" string (04-apis § 3) reporting whether the
 * file was created or modified and the resulting byte and line counts, so the model has a
 * concise confirmation without echoing the whole file back.
 *
 * <p>The path is confined to the injected workspace root via {@link WorkspacePaths}; a
 * write that would escape the workspace is refused with a {@link ToolInvocationException}.
 */
public final class WriteFileTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WriteFileTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "write_file";

    private final WorkspacePaths paths;

    /**
     * Creates the tool rooted at the given workspace.
     *
     * @param workspaceRoot the workspace root all paths resolve against; must not be
     *                      {@code null}.
     * @throws NullPointerException if {@code workspaceRoot} is {@code null}.
     */
    public WriteFileTool(Path workspaceRoot) {
        this.paths = new WorkspacePaths(Objects.requireNonNull(workspaceRoot, "workspaceRoot"));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Write content to a workspace-relative file, creating or replacing it. "
                + "Subject to the active permission mode.";
    }

    @Override
    public Document inputSchema() {
        return ToolSchemas.writeFile();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.SIDE_EFFECTING;
    }

    /**
     * Writes the content to the file and returns a one-line ok/diff summary.
     *
     * @param input the {@code toolUse.input}: requires {@code path} and {@code content}.
     * @return a summary string reporting created/modified and the new size.
     * @throws ToolInvocationException if {@code path} is missing/blank or escapes the
     *                                 workspace, {@code content} is missing, the target
     *                                 is an existing directory, or the write fails.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        Path file = paths.resolve(ToolInputs.requireString(input, "path"));
        String content = requireContent(input);
        if (Files.isDirectory(file)) {
            throw new ToolInvocationException("cannot write to a directory: " + file);
        }
        boolean existed = Files.exists(file);

        write(file, content);
        long lineCount = content.isEmpty() ? 0 : content.lines().count();
        int byteCount = content.getBytes(StandardCharsets.UTF_8).length;
        LOGGER.info("Wrote {} ({} bytes, {} lines, {})",
                file, byteCount, lineCount, existed ? "modified" : "created");
        return (existed ? "ok: modified " : "ok: created ")
                + file + " (" + byteCount + " bytes, " + lineCount + " lines)";
    }

    private static String requireContent(Map<String, Object> input) {
        Object value = input.get("content");
        if (value == null) {
            throw new ToolInvocationException("missing required input 'content'");
        }
        if (!(value instanceof String s)) {
            throw new ToolInvocationException(
                    "input 'content' must be a string but was " + value.getClass().getSimpleName());
        }
        return s;
    }

    private static void write(Path file, String content) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to write file {}", file, e);
            throw new ToolInvocationException("failed to write file: " + file, e);
        }
    }
}
