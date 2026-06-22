package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.OperationClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the {@code list} tool (C9, 04-apis § 3). The tool is the SUT and lists real
 * directories under a JUnit {@link TempDir} (the filesystem is the genuine collaborator).
 *
 * <p>Oracles: 04-apis § 3 (list is Class R; input {@code path} → dir entries), AC-4.4
 * (read/grep/glob/list non-gated — Class R), AC-4.3 (a missing/non-directory path is
 * reported, not fabricated), and the C9 invariant (file tools operate within the
 * workspace). The {@code name} / {@code name/} entry shape is the stable contract the model
 * consumes, asserted concretely.
 */
class ListToolTest {

    @Test
    @DisplayName("AC-4.4 / 04-apis § 3: list is Class R (READ), never gated")
    void listIsClassR(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 — list Class = R; AC-4.4 — read/grep/glob/list are non-gated
        // in any mode. The tool records OperationClass.READ so the gate auto-approves it.
        assertEquals(OperationClass.READ, new ListTool(workspace).operationClass(),
                "list is Class R — non-gated in any mode (04-apis § 3, AC-4.4)");
        assertEquals("list", new ListTool(workspace).name());
    }

    @Test
    @DisplayName("04-apis § 3: list returns the directory's entries, directories suffixed with '/'")
    void listsEntriesWithDirectoryMarker(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — list(path) returns dir entries. Entry shape (the model-facing
        // contract): files as 'name', directories as 'name/', sorted, one per line.
        Files.writeString(workspace.resolve("b.txt"), "x");
        Files.writeString(workspace.resolve("a.txt"), "x");
        Files.createDirectory(workspace.resolve("sub"));

        Object result = new ListTool(workspace).handle(Map.of("path", "."));

        assertEquals("a.txt\nb.txt\nsub/", result,
                "entries are sorted; directories carry a trailing '/' (04-apis § 3)");
    }

    @Test
    @DisplayName("04-apis § 3: list of an empty directory returns the empty string")
    void emptyDirectoryReturnsEmpty(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — an empty directory has no entries; an empty result is a
        // valid found-nothing answer, not an error.
        Files.createDirectory(workspace.resolve("empty"));

        Object result = new ListTool(workspace).handle(Map.of("path", "empty"));

        assertEquals("", result, "an empty directory lists to the empty string");
    }

    @Test
    @DisplayName("AC-4.3: a missing directory or a file target is reported, not fabricated")
    void missingOrNonDirectoryIsReported(@TempDir Path workspace) throws IOException {
        // Oracle: AC-4.3 — if a referenced directory does not exist (or is a file, not a
        // directory) the tool reports it via a ToolInvocationException (an error tool result)
        // rather than fabricating entries.
        Files.writeString(workspace.resolve("a.txt"), "x");
        ListTool tool = new ListTool(workspace);

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("path", "nope")));
        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("path", "a.txt")));
    }

    @Test
    @DisplayName("C9: a path escaping the workspace is refused")
    void rejectsPathEscapingWorkspace(@TempDir Path workspace) {
        // Oracle: C9 invariant — file tools operate within the workspace.
        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> new ListTool(workspace).handle(Map.of("path", "../..")));

        assertTrue(ex.getMessage().contains("escapes the workspace"), ex.getMessage());
    }

    @Test
    @DisplayName("04-apis § 3 Notes: a missing or wrong-typed path is a tool error, not a crash")
    void missingOrWrongTypedPathIsToolError(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 Notes — bad input surfaces as an error result, not a crash.
        ListTool tool = new ListTool(workspace);

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of()));
        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("path", 42)));
    }
}
