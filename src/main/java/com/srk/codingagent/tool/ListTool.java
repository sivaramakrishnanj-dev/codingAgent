package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.OperationClass;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code list} tool (component C9, 04-apis § 3): lists the entries of a
 * workspace-relative directory so the model can explore the repository structure (US-4).
 * It is Class R ({@link OperationClass#READ}) — listing is auto-approved and never gated
 * (AC-4.4).
 *
 * <p>Input: {@code path} (required, a workspace-relative directory). The result is the
 * directory's immediate entries (not recursive — {@code glob} does recursive walks),
 * one per line, sorted, with a trailing {@code /} on directory entries so the model can
 * tell files from sub-directories. An empty directory returns the empty string. A path
 * that does not exist or is not a directory is a {@link ToolInvocationException} (AC-4.3 —
 * report rather than fabricate). The path is confined to the workspace via
 * {@link WorkspacePaths}.
 */
public final class ListTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ListTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "list";

    private final WorkspacePaths paths;

    /**
     * Creates the tool rooted at the given workspace.
     *
     * @param workspaceRoot the workspace root all paths resolve against; must not be
     *                      {@code null}.
     * @throws NullPointerException if {@code workspaceRoot} is {@code null}.
     */
    public ListTool(Path workspaceRoot) {
        this.paths = new WorkspacePaths(Objects.requireNonNull(workspaceRoot, "workspaceRoot"));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "List the entries of a workspace-relative directory. "
                + "Directory entries are suffixed with '/'.";
    }

    @Override
    public Document inputSchema() {
        return ToolSchemas.list();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.READ;
    }

    /**
     * Lists the directory's immediate entries.
     *
     * @param input the {@code toolUse.input}: requires {@code path}.
     * @return the sorted entry names (directories suffixed with {@code /}), one per line;
     *         the empty string for an empty directory.
     * @throws ToolInvocationException if {@code path} is missing/blank, escapes the
     *                                 workspace, does not exist, is not a directory, or the
     *                                 directory cannot be read.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        Path dir = paths.resolve(ToolInputs.requireString(input, "path"));
        if (!Files.isDirectory(dir)) {
            throw new ToolInvocationException("not a directory: " + dir);
        }
        LOGGER.info("Listing directory {}", dir);
        return String.join("\n", entries(dir));
    }

    private static List<String> entries(Path dir) {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                names.add(Files.isDirectory(entry) ? name + "/" : name);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to list directory {}", dir, e);
            throw new ToolInvocationException("failed to list directory: " + dir, e);
        }
        names.sort(String::compareTo);
        return names;
    }
}
