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
 * Tests for the {@code read_file} tool (C9, 04-apis § 3). The tool is the SUT and reads
 * real files under a JUnit {@link TempDir} (the filesystem is the genuine collaborator).
 *
 * <p>Oracles: 04-apis § 3 (read_file is Class R; inputs path + optional offset/limit →
 * file text) and the C9 invariant (file tools operate within the workspace).
 */
class ReadFileToolTest {

    @Test
    @DisplayName("04-apis § 3: read_file is Class R (READ), never gated")
    void readFileIsClassR(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 — read_file Class = R (read, auto-approved). The tool records
        // OperationClass.READ so the gate (T-0.7) classifies it as non-gated.
        assertEquals(OperationClass.READ, new ReadFileTool(workspace).operationClass(),
                "read_file is Class R (04-apis § 3)");
        assertEquals("read_file", new ReadFileTool(workspace).name());
    }

    @Test
    @DisplayName("04-apis § 3: read_file returns the full file text by default")
    void readsFullFile(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — read_file(path) returns the file text.
        Files.writeString(workspace.resolve("a.txt"), "line1\nline2\nline3");

        Object result = new ReadFileTool(workspace).handle(Map.of("path", "a.txt"));

        assertEquals("line1\nline2\nline3", result, "the full file text is returned (04-apis § 3)");
    }

    @Test
    @DisplayName("04-apis § 3: offset and limit select a line window")
    void offsetAndLimitSelectWindow(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — read_file accepts optional offset and limit. offset is
        // 1-based; limit caps the line count.
        Files.writeString(workspace.resolve("a.txt"), "l1\nl2\nl3\nl4\nl5");

        Object result = new ReadFileTool(workspace)
                .handle(Map.of("path", "a.txt", "offset", 2, "limit", 2));

        assertEquals("l2\nl3", result, "offset=2 limit=2 returns lines 2-3 (04-apis § 3)");
    }

    @Test
    @DisplayName("C9: a path escaping the workspace is refused")
    void rejectsPathEscapingWorkspace(@TempDir Path workspace) {
        // Oracle: C9 invariant — file tools operate within the workspace. A traversal out
        // of the root is refused as a ToolInvocationException (so it becomes an error tool
        // result, not an out-of-workspace read).
        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> new ReadFileTool(workspace).handle(Map.of("path", "../../etc/passwd")));

        assertTrue(ex.getMessage().contains("escapes the workspace"), ex.getMessage());
    }

    @Test
    @DisplayName("read_file surfaces a missing file and a missing path as tool errors")
    void surfacesMissingFileAndMissingPath(@TempDir Path workspace) {
        // Error path: a non-existent file and an absent required input both become
        // ToolInvocationExceptions (the registry turns them into error tool results).
        ReadFileTool tool = new ReadFileTool(workspace);

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("path", "nope.txt")));
        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of()));
    }

    @Test
    @DisplayName("read_file rejects a non-positive offset or limit")
    void rejectsNonPositiveWindow(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "l1\nl2");
        ReadFileTool tool = new ReadFileTool(workspace);

        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", "a.txt", "offset", 0)));
        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", "a.txt", "limit", -1)));
    }

    @Test
    @DisplayName("04-apis § 3: malformed toolUse.input is a tool error, not a crash")
    void rejectsWrongTypedInputs(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 Notes — a tool must surface bad input as an error result, not
        // crash the loop. A non-string path and a non-integer offset (shapes the model can
        // emit) are surfaced as ToolInvocationExceptions, not ClassCastExceptions.
        Files.writeString(workspace.resolve("a.txt"), "l1\nl2");
        ReadFileTool tool = new ReadFileTool(workspace);

        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", 42)));
        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", "a.txt", "offset", "two")));
    }

    @Test
    @DisplayName("read_file accepts a Long offset (DocumentConverter unwraps integers to Long)")
    void acceptsLongOffset(@TempDir Path workspace) throws IOException {
        // Oracle: dependency contract — DocumentConverter unwraps whole JSON numbers to
        // Long, so an offset arriving as Long must be honored identically to an int.
        Files.writeString(workspace.resolve("a.txt"), "l1\nl2\nl3");

        Object result = tool(workspace).handle(Map.of("path", "a.txt", "offset", 2L, "limit", 1L));

        assertEquals("l2", result, "a Long offset/limit selects the same window as an int");
    }

    private static ReadFileTool tool(Path workspace) {
        return new ReadFileTool(workspace);
    }
}
