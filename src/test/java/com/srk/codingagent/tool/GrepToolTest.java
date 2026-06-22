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
 * Tests for the {@code grep} tool (C9, 04-apis § 3, AC-4.1/4.2). The tool is the SUT and
 * searches real files under a JUnit {@link TempDir}.
 *
 * <p>Oracles: 04-apis § 3 (grep is Class R; inputs {@code pattern} + optional {@code path}/
 * {@code glob}/flags → matches), AC-4.4 (grep non-gated — Class R), AC-4.2 (textual regex
 * search over lines, not an AST/LSP), AC-4.3 (a missing search path is reported), and the
 * C9 invariant (within the workspace). The match-row shape {@code path:line:text} (1-based
 * line numbers) is the stable contract the model parses, asserted concretely.
 */
class GrepToolTest {

    @Test
    @DisplayName("AC-4.4 / 04-apis § 3: grep is Class R (READ), never gated")
    void grepIsClassR(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 — grep Class = R; AC-4.4 — grep is non-gated in any mode.
        assertEquals(OperationClass.READ, new GrepTool(workspace).operationClass(),
                "grep is Class R — non-gated in any mode (04-apis § 3, AC-4.4)");
        assertEquals("grep", new GrepTool(workspace).name());
    }

    @Test
    @DisplayName("04-apis § 3 / AC-4.2: grep returns regex matches as path:line:text rows")
    void returnsMatchesAsPathLineTextRows(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — grep(pattern) returns matches; AC-4.2 — textual regex search
        // over file lines. Match-row shape (the model-facing contract): path:line:text, with
        // 1-based line numbers, in path then line order.
        Files.writeString(workspace.resolve("a.txt"), "alpha\nbeta\ngamma beta");

        Object result = new GrepTool(workspace).handle(Map.of("pattern", "beta"));

        assertEquals("a.txt:2:beta\na.txt:3:gamma beta", result,
                "matches are 'path:line:text' with 1-based line numbers (04-apis § 3, AC-4.2)");
    }

    @Test
    @DisplayName("04-apis § 3 / AC-4.2: the pattern is a regular expression, not a literal")
    void patternIsRegularExpression(@TempDir Path workspace) throws IOException {
        // Oracle: AC-4.2 — textual search via regex (java.util.regex). A regex metacharacter
        // pattern matches by regex semantics, confirming this is regex search, not substring.
        Files.writeString(workspace.resolve("a.txt"), "foo123\nfoobar\nbaz");

        Object result = new GrepTool(workspace).handle(Map.of("pattern", "foo[0-9]+"));

        assertEquals("a.txt:1:foo123", result, "the pattern is matched as a regex (AC-4.2)");
    }

    @Test
    @DisplayName("04-apis § 3: an optional glob limits which files are searched")
    void globLimitsSearchedFiles(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — grep accepts an optional glob filtering the files searched.
        Files.writeString(workspace.resolve("a.java"), "needle");
        Files.writeString(workspace.resolve("b.txt"), "needle");

        Object result = new GrepTool(workspace)
                .handle(Map.of("pattern", "needle", "glob", "*.java"));

        assertEquals("a.java:1:needle", result,
                "only files matching the glob are searched (04-apis § 3)");
    }

    @Test
    @DisplayName("04-apis § 3: an optional ignoreCase flag makes matching case-insensitive")
    void ignoreCaseFlag(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — grep accepts flags. The ignoreCase flag makes the regex
        // case-insensitive; without it the same query would not match a different case.
        Files.writeString(workspace.resolve("a.txt"), "Hello World");

        Object insensitive = new GrepTool(workspace)
                .handle(Map.of("pattern", "hello", "ignoreCase", true));
        Object sensitive = new GrepTool(workspace).handle(Map.of("pattern", "hello"));

        assertEquals("a.txt:1:Hello World", insensitive, "ignoreCase matches across case (04-apis § 3)");
        assertEquals("", sensitive, "case-sensitive (default) does not match a different case");
    }

    @Test
    @DisplayName("04-apis § 3: an optional path scopes the search to one file")
    void pathScopesToSingleFile(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — grep accepts an optional path scoping the search (a file or
        // directory). Scoping to one file searches only that file.
        Files.writeString(workspace.resolve("a.txt"), "match");
        Files.writeString(workspace.resolve("b.txt"), "match");

        Object result = new GrepTool(workspace)
                .handle(Map.of("pattern", "match", "path", "a.txt"));

        assertEquals("a.txt:1:match", result, "the search is scoped to the given file (04-apis § 3)");
    }

    @Test
    @DisplayName("04-apis § 3: grep with no match returns the empty string")
    void noMatchReturnsEmpty(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 — a search that matches nothing returns an empty result, not
        // an error.
        Files.writeString(workspace.resolve("a.txt"), "alpha\nbeta");

        Object result = new GrepTool(workspace).handle(Map.of("pattern", "zzz"));

        assertEquals("", result, "no match is the empty string, not an error");
    }

    @Test
    @DisplayName("AC-4.3: a missing search path is reported, not fabricated")
    void missingSearchPathIsReported(@TempDir Path workspace) {
        // Oracle: AC-4.3 — a referenced file/dir that does not exist is reported as a tool
        // error rather than fabricating matches.
        assertThrows(ToolInvocationException.class,
                () -> new GrepTool(workspace).handle(Map.of("pattern", "x", "path", "nope.txt")));
    }

    @Test
    @DisplayName("C9: a search path escaping the workspace is refused")
    void rejectsPathEscapingWorkspace(@TempDir Path workspace) {
        // Oracle: C9 invariant — file tools operate within the workspace.
        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> new GrepTool(workspace).handle(Map.of("pattern", "x", "path", "../../etc")));

        assertTrue(ex.getMessage().contains("escapes the workspace"), ex.getMessage());
    }

    @Test
    @DisplayName("04-apis § 3 Notes: a missing pattern or invalid regex is a tool error, not a crash")
    void invalidInputIsToolError(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 Notes — bad input (a missing required pattern, an invalid
        // regex) surfaces as an error result, not a crash.
        GrepTool tool = new GrepTool(workspace);

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of()));
        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("pattern", "[")));
    }

    @Test
    @DisplayName("04-apis § 3 Notes: wrong-typed optional flags/filters are tool errors, not crashes")
    void wrongTypedOptionalInputsAreToolError(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 Notes — a wrong-typed optional input (a non-boolean ignoreCase,
        // a non-string glob/path the model might emit) surfaces as a ToolInvocationException,
        // not a ClassCastException that crashes the loop.
        Files.writeString(workspace.resolve("a.txt"), "x");
        GrepTool tool = new GrepTool(workspace);

        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("pattern", "x", "ignoreCase", "yes")));
        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("pattern", "x", "glob", 7)));
    }
}
