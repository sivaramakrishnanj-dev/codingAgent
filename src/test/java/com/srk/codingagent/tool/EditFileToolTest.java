package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.OperationClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the {@code edit_file} tool (C9, 04-apis § 3, US-5, AC-5.2/5.4). The tool is the
 * SUT and edits real files under a JUnit {@link TempDir}.
 *
 * <p>Oracles: 04-apis § 3 (edit_file is Class X; inputs {@code path}/{@code old}/{@code new}
 * → ok/diff summary), AC-5.2 (an edit is subject to the active permission mode — recorded
 * via the Class-X marker so the gate classifies it), AC-5.4 spirit (an ambiguous target is
 * an error the model reacts to, not a silent guess), AC-4.3 (a missing target is reported),
 * and the C9 invariant (within the workspace). The unique-substring matching contract and
 * the {@code ok:} summary / error results are the model-facing contract, asserted
 * concretely (the D2-class defense against silent no-ops).
 */
class EditFileToolTest {

    @Test
    @DisplayName("04-apis § 3 / AC-5.2: edit_file is Class X (SIDE_EFFECTING), gated by mode")
    void editFileIsClassX(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 — edit_file Class = X; AC-5.2 — an edit is subject to the
        // permission mode. The tool records OperationClass.SIDE_EFFECTING so the gate classifies
        // it as gated (the generic forTool path gates Class X by mode).
        assertEquals(OperationClass.SIDE_EFFECTING, new EditFileTool(workspace).operationClass(),
                "edit_file is Class X — gated by the permission mode (04-apis § 3, AC-5.2)");
        assertEquals("edit_file", new EditFileTool(workspace).name());
    }

    @Test
    @DisplayName("04-apis § 3: a unique old text is replaced and an ok summary is returned")
    void uniqueOldIsReplacedWithOkSummary(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — edit_file(path, old, new) applies the edit and returns an
        // ok/diff summary. A unique 'old' is replaced in place (targeted edit, not whole-file
        // replace); the summary starts with 'ok' and names the edited file.
        Files.writeString(workspace.resolve("a.txt"), "first\nold line\nlast");

        Object result = new EditFileTool(workspace)
                .handle(Map.of("path", "a.txt", "old", "old line", "new", "new line"));

        assertEquals("first\nnew line\nlast", Files.readString(workspace.resolve("a.txt")),
                "only the matched region is replaced; the rest of the file is preserved (04-apis § 3)");
        assertTrue(String.valueOf(result).startsWith("ok"), "the result is an ok summary: " + result);
        assertTrue(String.valueOf(result).contains("a.txt"), "the summary names the edited file: " + result);
    }

    @Test
    @DisplayName("04-apis § 3: an empty new text deletes the matched region")
    void emptyNewDeletesRegion(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — new is the replacement; an empty replacement deletes the
        // matched region (a valid targeted edit).
        Files.writeString(workspace.resolve("a.txt"), "keep REMOVE keep");

        Map<String, Object> input = new HashMap<>();
        input.put("path", "a.txt");
        input.put("old", " REMOVE");
        input.put("new", "");
        new EditFileTool(workspace).handle(input);

        assertEquals("keep keep", Files.readString(workspace.resolve("a.txt")),
                "an empty 'new' removes the matched region (04-apis § 3)");
    }

    @Test
    @DisplayName("AC-4.3: an old text that matches nothing is reported, not silently applied")
    void noMatchIsReported(@TempDir Path workspace) throws IOException {
        // Oracle: AC-4.3 / 04-apis § 3 Notes — an 'old' with no match is an error tool result
        // (the model must correct its target); the tool must NOT silently no-op (the D2-class
        // live defect: an edit that does nothing).
        Files.writeString(workspace.resolve("a.txt"), "unchanged");

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> new EditFileTool(workspace)
                        .handle(Map.of("path", "a.txt", "old", "absent", "new", "x")));

        assertTrue(ex.getMessage().contains("no match"), ex.getMessage());
        assertEquals("unchanged", Files.readString(workspace.resolve("a.txt")),
                "a non-matching edit leaves the file untouched (no silent no-op)");
    }

    @Test
    @DisplayName("AC-5.4 spirit: an old text matching more than once is ambiguous, not a guess")
    void ambiguousMatchIsReported(@TempDir Path workspace) throws IOException {
        // Oracle: AC-5.4 spirit — when the target is ambiguous (multiple plausible places)
        // the tool does not silently guess which to edit; it returns an error so the model
        // disambiguates with a longer, unique 'old'.
        Files.writeString(workspace.resolve("a.txt"), "dup\ndup");

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> new EditFileTool(workspace)
                        .handle(Map.of("path", "a.txt", "old", "dup", "new", "x")));

        assertTrue(ex.getMessage().contains("ambiguous"), ex.getMessage());
        assertEquals("dup\ndup", Files.readString(workspace.resolve("a.txt")),
                "an ambiguous edit leaves the file untouched (no silent guess)");
    }

    @Test
    @DisplayName("AC-4.3: editing a missing file is reported, not fabricated")
    void missingFileIsReported(@TempDir Path workspace) {
        // Oracle: AC-4.3 — a referenced file that does not exist is reported rather than
        // created/fabricated (edit_file edits existing files; write_file creates).
        assertThrows(ToolInvocationException.class,
                () -> new EditFileTool(workspace)
                        .handle(Map.of("path", "nope.txt", "old", "x", "new", "y")));
    }

    @Test
    @DisplayName("C9: an edit escaping the workspace is refused")
    void rejectsEditEscapingWorkspace(@TempDir Path workspace) {
        // Oracle: C9 invariant — file tools operate within the workspace.
        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> new EditFileTool(workspace)
                        .handle(Map.of("path", "../escape.txt", "old", "x", "new", "y")));

        assertTrue(ex.getMessage().contains("escapes the workspace"), ex.getMessage());
    }

    @Test
    @DisplayName("04-apis § 3 Notes: missing inputs are a tool error, not a crash")
    void missingInputsAreToolError(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 Notes — bad input surfaces as an error result, not a crash.
        // path, old, and new are all required.
        Files.writeString(workspace.resolve("a.txt"), "content");
        EditFileTool tool = new EditFileTool(workspace);

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("old", "x", "new", "y")));
        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", "a.txt", "new", "y")));
        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", "a.txt", "old", "content")));
    }
}
