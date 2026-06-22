package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.OperationClass;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code edit_file} tool (component C9, 04-apis § 3, US-5): applies a targeted edit to
 * an existing workspace file by replacing an exact {@code old} text region with {@code new}
 * text. It is the targeted-edit complement to {@code write_file} (whole-file replace) — it
 * does <em>not</em> rewrite the whole file. It is Class X
 * ({@link OperationClass#SIDE_EFFECTING}) — a mutating edit the permission mode gates
 * (AC-5.2). This task records the class so the gate (C8) classifies the tool; a wired agent
 * routes the call through the gate before invoking the handler.
 *
 * <p><b>Matching contract (deterministic — the model reacts to errors, it does not let the
 * tool guess).</b> The edit is a literal, unique-substring replace:
 * <ul>
 *   <li>{@code old} occurs <b>exactly once</b> → the single occurrence is replaced with
 *       {@code new}, the file is rewritten, and a one-line {@code ok} summary is returned
 *       (mirroring {@code write_file}'s summary style).</li>
 *   <li>{@code old} occurs <b>zero</b> times → a {@link ToolInvocationException} ("no match
 *       for old text") so the model can correct its target rather than the tool silently
 *       no-op'ing.</li>
 *   <li>{@code old} occurs <b>more than once</b> → a {@link ToolInvocationException}
 *       ("ambiguous"); the model must disambiguate (give a longer, unique {@code old}). The
 *       tool never silently guesses which occurrence to edit (AC-5.4 spirit).</li>
 *   <li>the target file does not exist / is not a regular file → a
 *       {@link ToolInvocationException} (AC-4.3 — report rather than fabricate).</li>
 * </ul>
 *
 * <p>Inputs: {@code path} (required, workspace-relative), {@code old} (required, the exact
 * existing text), {@code new} (required, the replacement; may be empty to delete the
 * region). The path is confined to the workspace via {@link WorkspacePaths}; an edit that
 * would escape the workspace is refused with a {@link ToolInvocationException}.
 */
public final class EditFileTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(EditFileTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "edit_file";

    private final WorkspacePaths paths;

    /**
     * Creates the tool rooted at the given workspace.
     *
     * @param workspaceRoot the workspace root all paths resolve against; must not be
     *                      {@code null}.
     * @throws NullPointerException if {@code workspaceRoot} is {@code null}.
     */
    public EditFileTool(Path workspaceRoot) {
        this.paths = new WorkspacePaths(Objects.requireNonNull(workspaceRoot, "workspaceRoot"));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Replace an exact, unique 'old' text region with 'new' in an existing "
                + "workspace file. Subject to the active permission mode.";
    }

    @Override
    public Document inputSchema() {
        return ToolSchemas.editFile();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.SIDE_EFFECTING;
    }

    /**
     * Applies the targeted edit and returns a one-line ok summary.
     *
     * @param input the {@code toolUse.input}: requires {@code path}, {@code old}, and
     *              {@code new}.
     * @return a summary string reporting the edited file and the replacement size.
     * @throws ToolInvocationException if {@code path}/{@code old} is missing/blank or
     *                                 {@code new} is missing, {@code path} escapes the
     *                                 workspace, the target is not an existing regular file,
     *                                 {@code old} matches zero or more than one place, or
     *                                 the read/write fails.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        Path file = paths.resolve(ToolInputs.requireString(input, "path"));
        String oldText = ToolInputs.requireString(input, "old");
        String newText = requireNew(input);
        if (!Files.isRegularFile(file)) {
            throw new ToolInvocationException("not an editable file: " + file);
        }

        String content = read(file);
        int first = content.indexOf(oldText);
        if (first < 0) {
            throw new ToolInvocationException("no match for old text in " + file);
        }
        if (content.indexOf(oldText, first + oldText.length()) >= 0) {
            throw new ToolInvocationException(
                    "ambiguous edit: old text occurs more than once in " + file
                            + "; provide a longer unique 'old'");
        }

        String edited = content.substring(0, first) + newText + content.substring(first + oldText.length());
        write(file, edited);
        int byteCount = edited.getBytes(StandardCharsets.UTF_8).length;
        LOGGER.info("Edited {} (replaced {} chars with {} chars, {} bytes total)",
                file, oldText.length(), newText.length(), byteCount);
        return "ok: edited " + file + " (replaced " + oldText.length() + " chars with "
                + newText.length() + " chars, " + byteCount + " bytes total)";
    }

    private static String requireNew(Map<String, Object> input) {
        Object value = input.get("new");
        if (value == null) {
            throw new ToolInvocationException("missing required input 'new'");
        }
        if (!(value instanceof String s)) {
            throw new ToolInvocationException(
                    "input 'new' must be a string but was " + value.getClass().getSimpleName());
        }
        return s;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to read file {} for edit", file, e);
            throw new ToolInvocationException("failed to read file: " + file, e);
        } catch (UncheckedIOException e) {
            LOGGER.error("Failed to decode file {} for edit", file, e);
            throw new ToolInvocationException("failed to decode file as UTF-8: " + file, e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to write file {} during edit", file, e);
            throw new ToolInvocationException("failed to write file: " + file, e);
        }
    }
}
