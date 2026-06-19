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
 * Tests for the {@code write_file} tool (C9, 04-apis § 3, AC-5.2). The tool is the SUT and
 * writes real files under a JUnit {@link TempDir}.
 *
 * <p>Oracles: 04-apis § 3 (write_file is Class X; inputs path + content → ok/diff
 * summary), AC-5.2 (edits are subject to the active permission mode — recorded via the
 * Class-X marker so the gate can classify; the gate itself is T-0.7), and the C9 invariant
 * (writes operate within the workspace).
 */
class WriteFileToolTest {

    @Test
    @DisplayName("04-apis § 3 / AC-5.2: write_file is Class X (SIDE_EFFECTING), gated by mode")
    void writeFileIsClassX(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 — write_file Class = X; AC-5.2 — an edit is subject to the
        // permission mode. The tool records OperationClass.SIDE_EFFECTING so the gate
        // (T-0.7) classifies it as gated.
        assertEquals(OperationClass.SIDE_EFFECTING, new WriteFileTool(workspace).operationClass(),
                "write_file is Class X — gated by the permission mode (04-apis § 3, AC-5.2)");
        assertEquals("write_file", new WriteFileTool(workspace).name());
    }

    @Test
    @DisplayName("04-apis § 3: write_file creates a new file and returns an ok summary")
    void createsNewFile(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — write_file(path, content) writes the file and returns
        // ok/diff summary.
        Object result = new WriteFileTool(workspace)
                .handle(Map.of("path", "new.txt", "content", "hello\nworld"));

        assertEquals("hello\nworld", Files.readString(workspace.resolve("new.txt")),
                "the content is written to the file (04-apis § 3)");
        assertTrue(String.valueOf(result).startsWith("ok"), "the result is an ok summary: " + result);
        assertTrue(String.valueOf(result).contains("created"), "a new file reports created: " + result);
    }

    @Test
    @DisplayName("04-apis § 3: write_file replaces an existing file and reports modified")
    void replacesExistingFile(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "old");

        Object result = new WriteFileTool(workspace)
                .handle(Map.of("path", "a.txt", "content", "new"));

        assertEquals("new", Files.readString(workspace.resolve("a.txt")), "the file is replaced");
        assertTrue(String.valueOf(result).contains("modified"),
                "an existing file reports modified: " + result);
    }

    @Test
    @DisplayName("write_file creates missing parent directories under the workspace")
    void createsParentDirectories(@TempDir Path workspace) throws IOException {
        Object result = new WriteFileTool(workspace)
                .handle(Map.of("path", "sub/dir/file.txt", "content", "x"));

        assertEquals("x", Files.readString(workspace.resolve("sub/dir/file.txt")));
        assertTrue(String.valueOf(result).startsWith("ok"));
    }

    @Test
    @DisplayName("C9: a write escaping the workspace is refused")
    void rejectsWriteEscapingWorkspace(@TempDir Path workspace) {
        // Oracle: C9 invariant — file tools operate within the workspace.
        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> new WriteFileTool(workspace)
                        .handle(Map.of("path", "../escape.txt", "content", "x")));

        assertTrue(ex.getMessage().contains("escapes the workspace"), ex.getMessage());
    }

    @Test
    @DisplayName("write_file surfaces missing inputs and a directory target as tool errors")
    void surfacesInvalidInputs(@TempDir Path workspace) throws IOException {
        WriteFileTool tool = new WriteFileTool(workspace);
        Files.createDirectory(workspace.resolve("adir"));

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("content", "x")));
        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("path", "a.txt")));
        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", "adir", "content", "x")));
    }
}
