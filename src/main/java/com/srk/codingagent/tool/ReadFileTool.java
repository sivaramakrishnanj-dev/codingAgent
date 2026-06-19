package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.OperationClass;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code read_file} tool (component C9, 04-apis § 3): reads a workspace-relative file
 * and returns its text. It is Class R ({@link OperationClass#READ}) — a read is
 * auto-approved and never gated (the gate itself is T-0.7).
 *
 * <p>Inputs: {@code path} (required, workspace-relative), {@code offset} (optional,
 * 1-based start line), {@code limit} (optional, maximum line count). Reading the whole
 * file is the default; {@code offset}/{@code limit} select a line window for large files.
 *
 * <p><b>Workspace confinement.</b> The path is resolved against the injected workspace
 * root and must stay inside it; an attempt to escape the workspace (e.g. {@code ../})
 * is refused with a {@link ToolInvocationException}, consistent with the C9 invariant
 * that file tools operate within the workspace. Output disposal for large files
 * (NFR-OUTPUT-MAX-INLINE) is a later task (T-1.5); this tool returns the selected text
 * in full.
 */
public final class ReadFileTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadFileTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "read_file";

    private final WorkspacePaths paths;

    /**
     * Creates the tool rooted at the given workspace.
     *
     * @param workspaceRoot the workspace root all paths resolve against; must not be
     *                      {@code null}.
     * @throws NullPointerException if {@code workspaceRoot} is {@code null}.
     */
    public ReadFileTool(Path workspaceRoot) {
        this.paths = new WorkspacePaths(Objects.requireNonNull(workspaceRoot, "workspaceRoot"));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Read a workspace-relative file and return its text. "
                + "Optionally select a line window with offset (1-based start) and limit.";
    }

    @Override
    public Document inputSchema() {
        return ToolSchemas.readFile();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.READ;
    }

    /**
     * Reads the requested file (or line window) and returns its text.
     *
     * @param input the {@code toolUse.input}: requires {@code path}; honors optional
     *              {@code offset} and {@code limit}.
     * @return the file text as a {@link String}.
     * @throws ToolInvocationException if {@code path} is missing/blank, escapes the
     *                                 workspace, is not a readable regular file, or
     *                                 {@code offset}/{@code limit} is non-positive or the
     *                                 file cannot be read.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        Path file = paths.resolve(ToolInputs.requireString(input, "path"));
        if (!Files.isRegularFile(file)) {
            throw new ToolInvocationException("not a readable file: " + file);
        }
        Integer offset = ToolInputs.optionalPositiveInt(input, "offset");
        Integer limit = ToolInputs.optionalPositiveInt(input, "limit");

        LOGGER.info("Reading file {} (offset={}, limit={})", file, offset, limit);
        List<String> lines = readLines(file);
        if (offset == null && limit == null) {
            return String.join("\n", lines);
        }
        return String.join("\n", window(lines, offset, limit));
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            LOGGER.error("Failed to read file {}", file, e);
            throw new ToolInvocationException("failed to read file: " + file, e);
        } catch (UncheckedIOException e) {
            // readAllLines wraps a charset-decode failure (non-UTF-8 bytes) as unchecked.
            LOGGER.error("Failed to decode file {}", file, e);
            throw new ToolInvocationException("failed to decode file as UTF-8: " + file, e);
        }
    }

    private static List<String> window(List<String> lines, Integer offset, Integer limit) {
        int from = offset == null ? 0 : Math.min(offset - 1, lines.size());
        int to = limit == null ? lines.size() : Math.min(from + limit, lines.size());
        return lines.subList(from, to);
    }
}
