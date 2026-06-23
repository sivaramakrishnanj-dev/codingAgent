package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link GreenfieldArtifactStore}: the target-repo path handling and confinement for the
 * greenfield design-doc artifacts (RD-7, AC-1.2, AC-2.1) and the design-doc-vs-source-write
 * distinction the pre-approval phases depend on (AC-1.4).
 *
 * <p><b>Oracles trace to the spec, not the store's implementation:</b>
 * <ul>
 *   <li><b>RD-7/AC-1.2/AC-2.1:</b> artifacts land under the <em>target</em> repo's working
 *       directory (the {@link TempDir}), at the expected target-repo-relative path — the G1
 *       working-dir lesson, asserted by resolving the path explicitly.</li>
 *   <li><b>AC-1.4:</b> the design-doc write path refuses a path that escapes the artifact
 *       directory (a source file), so it cannot be used to write source.</li>
 * </ul>
 */
class GreenfieldArtifactStoreTest {

    // --- RD-7/AC-1.2/AC-2.1 : the artifact lands at the expected TARGET-repo-relative path --------

    @Test
    @DisplayName("RD-7/AC-1.2: a written artifact lands under the target repo's design/ directory at the expected path")
    void writesArtifactToTargetRepoRelativePath(@TempDir Path targetRepo) throws Exception {
        // Oracle: RD-7/AC-1.2/AC-2.1 — the agent persists the requirements/design/tasks markdown in
        // the TARGET project (the working dir, AC-6.2), NOT codingAgent's own design/ tree. Recall the
        // prior-milestone lesson: the read/write working-dir resolution was finicky, so assert the
        // resolved path EXPLICITLY. The expected path is targetRepo/design/<file>, derived from the
        // target repo root + the design-doc directory, not from store internals.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);

        Path written = store.write("design/00-requirements.md", "# Requirements\n");

        Path expected = targetRepo.resolve("design").resolve("00-requirements.md");
        assertEquals(expected.toAbsolutePath().normalize(), written,
                "RD-7/AC-1.2: the artifact is written to the target repo's design/ directory at the "
                        + "expected relative path");
        assertTrue(Files.exists(expected), "the artifact file exists on disk under the target repo");
        assertEquals("# Requirements\n",
                Files.readString(expected, StandardCharsets.UTF_8),
                "the artifact carries the authored content");
    }

    @Test
    @DisplayName("AC-2.1: a write creates the target repo's design/ directory when absent")
    void createsArtifactDirectoryWhenAbsent(@TempDir Path targetRepo) throws Exception {
        // Oracle: AC-2.1 — the agent produces the design and task-breakdown artifacts in the target
        // project. A brand-new target project has no design/ directory yet, so authoring an artifact
        // must create it. Assert the directory did not exist before and the artifact is created.
        Path designDir = targetRepo.resolve("design");
        assertFalse(Files.exists(designDir), "precondition: the target repo has no design/ dir yet");

        new GreenfieldArtifactStore(targetRepo).write("design/01-design.md", "# Design\n");

        assertTrue(Files.isDirectory(designDir), "AC-2.1: authoring an artifact creates design/");
    }

    // --- AC-1.4 : the design-doc write path cannot reach source files -----------------------------

    @Test
    @DisplayName("AC-1.4: a write outside the design/ artifact directory (a source file) is refused")
    void refusesWriteOutsideArtifactDirectory(@TempDir Path targetRepo) {
        // Oracle: AC-1.4 — while in the pre-approval dialogue the agent shall not execute any Class X
        // operation against SOURCE files. The design-doc write path (ADR-0012's allowed design-markdown
        // write) must be confined to the artifact directory so it CANNOT write a source file. A path
        // resolving outside design/ (e.g. a src/ source file) is refused.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);

        ToolInvocationException refused = assertThrows(ToolInvocationException.class,
                () -> store.write("src/main/java/App.java", "class App {}"));
        assertTrue(refused.getMessage().contains("design"),
                "AC-1.4: the refusal names the design-doc artifact directory; was: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("AC-1.4: a path escaping the workspace via traversal is refused")
    void refusesWorkspaceEscape(@TempDir Path targetRepo) {
        // Oracle: AC-1.4 + the C9 confinement invariant (WorkspacePaths) — a path that escapes the
        // target repo root via ../ traversal is refused, so an artifact can never be written outside
        // the target repo.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);

        assertThrows(ToolInvocationException.class,
                () -> store.write("design/../../escape.md", "x"));
    }

    // --- AC-1.5 : appendLine adds the approval line without clobbering the authored body ----------

    @Test
    @DisplayName("AC-1.5: appendLine adds a trailing line to an existing artifact, preserving the body")
    void appendLinePreservesExistingBody(@TempDir Path targetRepo) throws Exception {
        // Oracle: AC-1.5 — the approval record is added INTO the (already-authored) artifact. So
        // appending the approval line must keep the model-authored body and add the line after it,
        // separated by a newline. Assert the body is intact and the new line follows it.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write("design/00-requirements.md", "# Requirements\n\nThe agreed requirements.");

        store.appendLine("design/00-requirements.md", "Approved: at 2026-06-23T09:00:00Z.");

        String content = Files.readString(
                targetRepo.resolve("design").resolve("00-requirements.md"), StandardCharsets.UTF_8);
        assertTrue(content.startsWith("# Requirements\n\nThe agreed requirements."),
                "AC-1.5: the model-authored body is preserved");
        assertTrue(content.endsWith("Approved: at 2026-06-23T09:00:00Z.\n"),
                "AC-1.5: the approval line is appended after the body; was: " + content);
    }

    @Test
    @DisplayName("read returns the artifact content when present and empty when absent")
    void readReflectsPresence(@TempDir Path targetRepo) {
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        assertEquals(Optional.empty(), store.read("design/02-tasks.md"),
                "an unwritten artifact reads as absent");

        store.write("design/02-tasks.md", "# Tasks\n");
        assertEquals(Optional.of("# Tasks\n"), store.read("design/02-tasks.md"),
                "a written artifact reads back its content");
    }

    @Test
    @DisplayName("the store rejects a null workspace root and null content")
    void rejectsNulls(@TempDir Path targetRepo) {
        assertThrows(NullPointerException.class, () -> new GreenfieldArtifactStore(null));
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        assertThrows(NullPointerException.class, () -> store.write("design/x.md", null));
        assertThrows(NullPointerException.class, () -> store.appendLine("design/x.md", null));
    }
}
