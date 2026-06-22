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
 * Tests for the {@code glob} tool (C9, 04-apis § 3). The tool is the SUT and walks real
 * files under a JUnit {@link TempDir}.
 *
 * <p>Oracles: 04-apis § 3 (glob is Class R; inputs {@code pattern} + optional {@code path}
 * → matching paths), AC-4.4 (glob non-gated — Class R), AC-4.2 (textual path matching, not
 * an AST), AC-4.3 (a missing search path is reported), and the C9 invariant (within the
 * workspace). The result — sorted workspace-relative paths, one per line — is the stable
 * contract the model consumes, asserted concretely.
 */
class GlobToolTest {

    @Test
    @DisplayName("AC-4.4 / 04-apis § 3: glob is Class R (READ), never gated")
    void globIsClassR(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 — glob Class = R; AC-4.4 — glob is non-gated in any mode.
        assertEquals(OperationClass.READ, new GlobTool(workspace).operationClass(),
                "glob is Class R — non-gated in any mode (04-apis § 3, AC-4.4)");
        assertEquals("glob", new GlobTool(workspace).name());
    }

    @Test
    @DisplayName("04-apis § 3 / AC-4.2: glob returns workspace-relative paths matching the pattern")
    void returnsMatchingRelativePaths(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — glob(pattern) returns matching paths; AC-4.2 — matching is
        // textual path matching (a PathMatcher over the path string), not an AST. The result
        // shape: workspace-relative paths, sorted, one per line.
        Files.createDirectories(workspace.resolve("src/main"));
        Files.writeString(workspace.resolve("src/main/A.java"), "x");
        Files.writeString(workspace.resolve("src/main/B.java"), "x");
        Files.writeString(workspace.resolve("src/main/notes.txt"), "x");

        Object result = new GlobTool(workspace).handle(Map.of("pattern", "**/*.java"));

        assertEquals("src/main/A.java\nsrc/main/B.java", result,
                "matching .java files are returned as sorted relative paths (04-apis § 3)");
    }

    @Test
    @DisplayName("04-apis § 3: an optional path scopes the glob to a sub-directory")
    void optionalPathScopesSearch(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — glob accepts an optional path. The walk roots there, but the
        // emitted paths stay workspace-relative.
        Files.createDirectories(workspace.resolve("a"));
        Files.createDirectories(workspace.resolve("b"));
        Files.writeString(workspace.resolve("a/keep.txt"), "x");
        Files.writeString(workspace.resolve("b/skip.txt"), "x");

        Object result = new GlobTool(workspace)
                .handle(Map.of("pattern", "**/*.txt", "path", "a"));

        assertEquals("a/keep.txt", result, "only files under the scoped path are matched (04-apis § 3)");
    }

    @Test
    @DisplayName("04-apis § 3: glob with no match returns the empty string")
    void noMatchReturnsEmpty(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — a search that matches nothing returns an empty result (a
        // found-nothing answer), not an error.
        Files.writeString(workspace.resolve("a.txt"), "x");

        Object result = new GlobTool(workspace).handle(Map.of("pattern", "**/*.md"));

        assertEquals("", result, "no match is the empty string, not an error");
    }

    @Test
    @DisplayName("AC-4.3: a missing or non-directory search path is reported")
    void missingSearchPathIsReported(@TempDir Path workspace) throws IOException {
        // Oracle: AC-4.3 — a referenced search directory that does not exist (or is a file)
        // is reported as a tool error rather than silently returning nothing.
        Files.writeString(workspace.resolve("a.txt"), "x");
        GlobTool tool = new GlobTool(workspace);

        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("pattern", "*", "path", "nope")));
        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("pattern", "*", "path", "a.txt")));
    }

    @Test
    @DisplayName("C9: a search path escaping the workspace is refused")
    void rejectsPathEscapingWorkspace(@TempDir Path workspace) {
        // Oracle: C9 invariant — file tools operate within the workspace.
        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> new GlobTool(workspace).handle(Map.of("pattern", "*", "path", "../..")));

        assertTrue(ex.getMessage().contains("escapes the workspace"), ex.getMessage());
    }

    @Test
    @DisplayName("04-apis § 3 Notes: a missing pattern or invalid glob is a tool error, not a crash")
    void invalidInputIsToolError(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 Notes — bad input (a missing required pattern, a syntactically
        // invalid glob) surfaces as an error result, not a crash.
        GlobTool tool = new GlobTool(workspace);

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of()));
        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("pattern", "[")));
    }
}
