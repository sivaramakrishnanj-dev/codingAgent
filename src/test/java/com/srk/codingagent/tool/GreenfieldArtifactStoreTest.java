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

    // --- D13 (AC-1.2/AC-1.5) : a prior-APPROVED artifact is not silently overwritten by a new run --

    @Test
    @DisplayName("D13 (AC-1.2/AC-1.5): a truncating write is REFUSED over an artifact that already carries a prior approval stamp")
    void refusesToClobberAnApprovedArtifact(@TempDir Path targetRepo) throws Exception {
        // Oracle: AC-1.2 (the agreed requirements ARE persisted — a guaranteed deliverable) + AC-1.5
        // (an approval stamp is recorded INTO the artifact when the developer confirms it). Together
        // they mean an approved, stamped artifact is a finalized deliverable. The D13 contract: a
        // new/second greenfield run must NOT silently overwrite a prior APPROVED artifact. So a
        // truncating write over an artifact bearing the AC-1.5 approval stamp must be refused. The
        // expectation traces to AC-1.2/AC-1.5 + the D13 contract, not to the store's message text.
        GreenfieldArtifactStore priorRun = new GreenfieldArtifactStore(targetRepo);
        priorRun.write("design/00-requirements.md", "# Requirements\n\nThe APPROVED requirements.");
        // The gate appends the AC-1.5 approval stamp after the developer confirms the phase.
        priorRun.appendLine("design/00-requirements.md",
                "Approved: Requirements approved by the developer at 2026-06-23T09:00:00Z.");

        // A NEW run (a fresh store over the SAME target repo) attempts a truncating write of a brand-
        // new "what do you want to build?" draft — the live D13 clobber.
        GreenfieldArtifactStore newRun = new GreenfieldArtifactStore(targetRepo);
        assertThrows(ApprovedArtifactProtectedException.class,
                () -> newRun.write("design/00-requirements.md", "# New project\n\nWhat do you want?"),
                "D13: a new run must not be allowed to truncate a prior APPROVED (stamped) artifact");
    }

    @Test
    @DisplayName("D13 (AC-1.2): the prior approved deliverable survives a refused clobber unchanged on disk")
    void approvedDeliverableSurvivesARefusedClobber(@TempDir Path targetRepo) throws Exception {
        // Oracle: AC-1.2 — the persisted approved deliverable is the guaranteed artifact; the field
        // report's destructive defect is its silent LOSS. So after a refused new-run write, the
        // approved body + its AC-1.5 stamp must still be on disk byte-for-byte. Expectation traces to
        // AC-1.2 (the deliverable is preserved), asserted on the actual file content.
        GreenfieldArtifactStore priorRun = new GreenfieldArtifactStore(targetRepo);
        priorRun.write("design/00-requirements.md", "# Requirements\n\nThe APPROVED requirements.");
        priorRun.appendLine("design/00-requirements.md",
                "Approved: Requirements approved by the developer at 2026-06-23T09:00:00Z.");
        String approvedOnDisk = Files.readString(
                targetRepo.resolve("design").resolve("00-requirements.md"), StandardCharsets.UTF_8);

        GreenfieldArtifactStore newRun = new GreenfieldArtifactStore(targetRepo);
        assertThrows(ApprovedArtifactProtectedException.class,
                () -> newRun.write("design/00-requirements.md", "clobbered"));

        String afterRefusedWrite = Files.readString(
                targetRepo.resolve("design").resolve("00-requirements.md"), StandardCharsets.UTF_8);
        assertEquals(approvedOnDisk, afterRefusedWrite,
                "D13/AC-1.2: the prior approved deliverable is preserved unchanged after the refused "
                        + "clobber — no silent loss of approved work");
        assertTrue(afterRefusedWrite.contains("The APPROVED requirements."),
                "the approved body is intact");
    }

    @Test
    @DisplayName("D13 (DCR-2): the in-round refine write over an UNSTAMPED draft is still allowed (it is how a phase converges)")
    void inRoundRefineWriteOverUnstampedDraftIsAllowed(@TempDir Path targetRepo) throws Exception {
        // Oracle: ADR-0012/DCR-2 — within ONE phase dialogue the driver truncating-writes the latest
        // deliverable each round BEFORE the gate; a non-approve round leaves the artifact UNSTAMPED and
        // the next refining turn supersedes it (truncating write). That in-round refine is CORRECT and
        // must NOT be refused — only a PRIOR-APPROVED (stamped) artifact is protected (D13). So a
        // truncating write over an unstamped draft succeeds and replaces it. Expectation traces to
        // DCR-2's in-round-refine contract, not to store internals.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write("design/00-requirements.md", "draft 1 (questions)");

        // No approval stamp yet: the next refining round truncates and supersedes the draft.
        store.write("design/00-requirements.md", "draft 2 (converged)");

        assertEquals("draft 2 (converged)",
                store.read("design/00-requirements.md").orElse(""),
                "DCR-2: the in-round refine write over an unstamped draft supersedes it (not refused)");
    }

    @Test
    @DisplayName("D13: a first write to a brand-new (absent) artifact is allowed — nothing approved to protect")
    void firstWriteToAbsentArtifactIsAllowed(@TempDir Path targetRepo) {
        // Oracle: the D13 protection guards only a PRIOR-APPROVED artifact. A brand-new target project
        // has no artifact yet, so the opening write of a greenfield session must succeed (AC-1.2 — the
        // deliverable is persisted). Expectation: the first write to an absent artifact is not refused.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);

        store.write("design/00-requirements.md", "# Requirements\n");

        assertEquals(Optional.of("# Requirements\n"), store.read("design/00-requirements.md"),
                "D13: the first write to an absent artifact is allowed; nothing approved is at risk");
    }

    @Test
    @DisplayName("AC-3.3/D13: appendLine onto an APPROVED tasks artifact is allowed (the implement loop marks tasks complete)")
    void appendLineOntoApprovedArtifactIsAllowed(@TempDir Path targetRepo) throws Exception {
        // Oracle: AC-3.3 — after the tasks phase is approved (and stamped, AC-1.5), the greenfield
        // implement loop marks each verified task complete by APPENDING a completion line to the SAME
        // (now-stamped) tasks artifact (GreenfieldImplementLoop.markComplete uses appendLine). The D13
        // protection guards only the TRUNCATING write(), never appendLine — so a legitimate same-run
        // append onto a stamped artifact must still succeed. Expectation traces to AC-3.3 (mark-complete
        // appends, not truncates) + D13 scope (write() only).
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        store.write("design/02-tasks.md", "# Tasks\n- T-1 (AC-1.1)\n");
        store.appendLine("design/02-tasks.md", "Approved: Tasks approved by the developer at T.");

        // The implement loop's mark-complete append onto the stamped tasks artifact.
        store.appendLine("design/02-tasks.md", "- [x] T-1 Implemented and verified (verified on attempt 1)");

        String content = Files.readString(
                targetRepo.resolve("design").resolve("02-tasks.md"), StandardCharsets.UTF_8);
        assertTrue(content.contains("- [x] T-1 Implemented and verified"),
                "AC-3.3/D13: appendLine onto an approved artifact is allowed (only truncating writes "
                        + "are refused); was: " + content);
        assertTrue(content.contains("- T-1 (AC-1.1)"),
                "the approved breakdown body is preserved");
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

    // --- AC-7.6 / DCR-3 : isApprovalStamped is the resume re-derivation's stamp signal (shared with D13)

    @Test
    @DisplayName("AC-7.6/DCR-3: isApprovalStamped reports true only for an artifact carrying the AC-1.5 approval stamp, false when unstamped or absent")
    void isApprovalStampedReflectsTheStamp(@TempDir Path targetRepo) throws Exception {
        // Oracle: AC-7.6 — "A phase whose artifact bears the AC-1.5 approval stamp is treated as
        // approved" — the greenfield mid-flow resume re-derivation keys on the SAME durable on-disk
        // stamp the D13 clobber guard uses (AC-1.5, one durable on-disk fact). isApprovalStamped is
        // that shared stamp signal: true iff the artifact carries an "Approved:" stamp line. Assert
        // absent -> false, unstamped -> false, stamped -> true. Expectation traces to AC-7.6/AC-1.5
        // (the stamp is the approved signal), not to store internals.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String path = "design/00-requirements.md";

        assertFalse(store.isApprovalStamped(path),
                "AC-7.6: an absent artifact carries no approval stamp");

        store.write(path, "# Requirements\n\nThe agreed requirements.");
        assertFalse(store.isApprovalStamped(path),
                "AC-7.6: an unstamped (in-flight / interrupted) artifact is NOT approved — re-entered "
                        + "on resume (retry-in-place)");

        store.appendLine(path, "Approved: Requirements approved by the developer at 2026-06-23T09:00:00Z.");
        assertTrue(store.isApprovalStamped(path),
                "AC-7.6: an AC-1.5-stamped artifact is treated as approved on resume");
    }

    @Test
    @DisplayName("DCR-3: isApprovalStamped (resume signal) and write()'s clobber guard (D13) agree on the SAME stamped artifact — one durable on-disk fact")
    void resumeSignalAndClobberGuardAgreeOnTheStamp(@TempDir Path targetRepo) {
        // Oracle: AC-1.5 / ADR-0012 (DCR-3) — "The AC-1.5 stamp is ONE durable on-disk fact serving
        // BOTH roles": the resume marker (isApprovalStamped == true => this phase is approved, skip on
        // resume) AND the D13 clobber-protection signal (write() refuses to truncate it). This pins
        // that the two readers agree on the SAME artifact: an artifact isApprovalStamped() reports
        // true for is exactly the artifact write() refuses to clobber. Expectation traces to the
        // shared-fact contract, not to either reader's internals.
        GreenfieldArtifactStore store = new GreenfieldArtifactStore(targetRepo);
        String path = "design/00-requirements.md";
        store.write(path, "# Requirements\n\nApproved deliverable.");
        store.appendLine(path, "Approved: Requirements approved by the developer at 2026-06-23T09:00:00Z.");

        assertTrue(store.isApprovalStamped(path),
                "DCR-3: the resume re-derivation reads the artifact as approved (stamped)");
        assertThrows(ApprovedArtifactProtectedException.class,
                () -> store.write(path, "clobber"),
                "DCR-3: the D13 clobber guard refuses to truncate the SAME stamped artifact — one "
                        + "durable on-disk fact serves both the resume marker and the clobber guard");
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
