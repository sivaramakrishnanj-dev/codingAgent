package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStatus;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.memory.MemoryTier;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link MemoryCommand} — the CLI orchestration behind the
 * {@code memory [list|show <slug>|edit <slug>|rm <slug>]} subcommand (04-apis § 1.2).
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>04-apis § 1.2 / AC-14.3</b>: {@code list} prints the two-tier memory index (one line
 *       per entry).</li>
 *   <li><b>04-apis § 1.2 / AC-14.1</b>: {@code show <slug>} prints the entry's markdown; memory
 *       is human-readable / hand-editable on disk.</li>
 *   <li><b>04-apis § 1.2 / AC-14.1</b>: {@code edit <slug>} resolves the entry's on-disk path so
 *       the developer can hand-edit it.</li>
 *   <li><b>04-apis § 1.2 / AC-14.1/14.3</b>: {@code rm <slug>} removes the entry and its index
 *       line (memory is curatable).</li>
 *   <li><b>AC-14.2 / INV-14</b>: an absent slug (e.g. hand-deleted) is reported cleanly, not a
 *       crash.</li>
 * </ul>
 *
 * <p>The SUT (a real {@link MemoryCommand}) drives a real {@link MemoryStore} over a
 * {@code @TempDir} — never mocked; the filesystem is the genuine collaborator. Only the output
 * {@link PrintStream} is captured to assert the user-facing report.
 */
class MemoryCommandTest {

    private static final String REPO_KEY = "github.com_srk_codingagent";

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);

    private MemoryCommand command(MemoryStore store) {
        return new MemoryCommand(store, REPO_KEY, out);
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8);
    }

    private static MemoryEntry entry(String slug, MemoryTier tier, String why, String body) {
        return new MemoryEntry(slug, tier, "2026-06-22T10:00:00Z",
                "2026-06-22T09-00-00-sess", why, MemoryStatus.ACTIVE, body);
    }

    @Test
    @DisplayName("04-apis § 1.2 / AC-14.3: memory list prints the two-tier index, one line per entry")
    void list_printsIndexEntries(@TempDir Path root) {
        // Oracle: 04-apis § 1.2 "memory [list ...] inspect/curate memory" + AC-14.3 "a memory
        // index lists entries". A GLOBAL and a PROJECT entry must both appear in the listing, and
        // the command exits 0.
        MemoryStore store = new MemoryStore(root);
        store.write(entry("global-rule", MemoryTier.GLOBAL, "a global learning", "g body"), REPO_KEY);
        store.write(entry("project-rule", MemoryTier.PROJECT, "a project learning", "p body"), REPO_KEY);

        int code = command(store).list();

        assertEquals(0, code, "listing memory succeeds (exit 0)");
        String report = output();
        assertTrue(report.contains("global-rule"), "AC-14.3: the GLOBAL entry is listed;\n" + report);
        assertTrue(report.contains("project-rule"), "AC-14.3: the PROJECT entry is listed;\n" + report);
    }

    @Test
    @DisplayName("04-apis § 1.2 / AC-14.3: memory list on an empty store reports cleanly and exits 0")
    void list_emptyStore_cleanZero(@TempDir Path root) {
        // Oracle: 04-apis § 1.2 — listing memory with nothing stored is "list nothing cleanly":
        // a clear message and exit 0, not an error.
        int code = command(new MemoryStore(root)).list();

        assertEquals(0, code, "listing an empty memory store is a clean exit 0");
        assertTrue(output().contains("No memory"),
                "the empty list prints a clear no-memory message; was: " + output());
    }

    @Test
    @DisplayName("04-apis § 1.2 / AC-14.1: memory show <slug> prints the entry's markdown content")
    void show_printsEntryContent(@TempDir Path root) {
        // Oracle: 04-apis § 1.2 "memory ... show <slug>" + AC-14.1 "memory is inspectable
        // (human-readable markdown)". Showing an entry must print its provenance (why) and its
        // learning body so the developer can read it.
        MemoryStore store = new MemoryStore(root);
        store.write(entry("the-slug", MemoryTier.GLOBAL, "the reason it was kept",
                "the learning body text"), REPO_KEY);

        int code = command(store).show("the-slug");

        assertEquals(0, code, "showing an existing entry succeeds (exit 0)");
        String report = output();
        assertTrue(report.contains("the-slug"), "AC-14.1: the shown entry names its slug;\n" + report);
        assertTrue(report.contains("the reason it was kept"),
                "AC-14.1: the entry's provenance (why) is shown;\n" + report);
        assertTrue(report.contains("the learning body text"),
                "AC-14.1: the entry's learning body is shown;\n" + report);
    }

    @Test
    @DisplayName("AC-14.2 / INV-14: memory show of an absent slug is reported cleanly (no crash)")
    void show_absentSlug_reportedCleanly(@TempDir Path root) {
        // Oracle: AC-14.2 / INV-14 — an absent entry (e.g. hand-deleted) is honoured on read; the
        // command reports "no such entry" cleanly (exit 0) rather than crashing.
        int code = command(new MemoryStore(root)).show("no-such-slug");

        assertEquals(0, code, "showing an absent slug is reported cleanly (exit 0), not a crash");
        assertTrue(output().contains("no-such-slug"),
                "the message names the absent slug; was: " + output());
    }

    @Test
    @DisplayName("04-apis § 1.2 / AC-14.1: memory edit <slug> points at the entry's on-disk path")
    void edit_pointsAtOnDiskPath(@TempDir Path root) {
        // Oracle: 04-apis § 1.2 "memory ... edit <slug>" + AC-14.1 "also hand-editable on disk".
        // Editing must resolve the entry's actual on-disk markdown file path so the developer can
        // hand-edit it; the printed path must be the real file.
        MemoryStore store = new MemoryStore(root);
        store.write(entry("edit-me", MemoryTier.GLOBAL, "why", "body"), REPO_KEY);

        int code = command(store).edit("edit-me");

        assertEquals(0, code, "edit succeeds (exit 0)");
        String report = output();
        assertTrue(report.contains("edit-me.md"),
                "AC-14.1: edit points at the on-disk markdown file to hand-edit;\n" + report);
        assertTrue(Files.isRegularFile(root.resolve("memory/edit-me.md")),
                "the path edit points at is the real on-disk entry file");
    }

    @Test
    @DisplayName("AC-14.2 / INV-14: memory edit of an absent slug is reported cleanly (no crash)")
    void edit_absentSlug_reportedCleanly(@TempDir Path root) {
        // Oracle: AC-14.2 / INV-14 — editing a slug that names no entry reports cleanly (exit 0)
        // rather than fabricating a path or crashing.
        int code = command(new MemoryStore(root)).edit("ghost");

        assertEquals(0, code, "editing an absent slug is reported cleanly (exit 0)");
        assertTrue(output().contains("ghost"), "the message names the absent slug; was: " + output());
    }

    @Test
    @DisplayName("04-apis § 1.2 / AC-14.1/14.3: memory rm <slug> removes the entry and its index line")
    void rm_removesEntryAndIndexLine(@TempDir Path root) throws Exception {
        // Oracle: 04-apis § 1.2 "memory ... rm <slug>" + AC-14.1/14.3 "curate memory". Removing
        // must delete the on-disk entry file AND drop its line from the index, so a subsequent
        // list no longer shows it.
        MemoryStore store = new MemoryStore(root);
        store.write(entry("doomed", MemoryTier.GLOBAL, "to be removed", "body"), REPO_KEY);
        assertTrue(Files.isRegularFile(root.resolve("memory/doomed.md")), "present before rm");

        int code = command(store).remove("doomed");

        assertEquals(0, code, "rm succeeds (exit 0)");
        assertFalse(Files.exists(root.resolve("memory/doomed.md")),
                "AC-14.1: rm deletes the on-disk entry file");
        String index = Files.readString(root.resolve("memory/INDEX.md"), StandardCharsets.UTF_8);
        assertFalse(index.contains("doomed"),
                "AC-14.3: rm drops the entry's index line; index was:\n" + index);
        assertTrue(output().contains("Removed"), "the report confirms the removal; was: " + output());
    }

    @Test
    @DisplayName("AC-14.1: memory rm of an absent slug is a clean no-op (exit 0)")
    void rm_absentSlug_cleanNoOp(@TempDir Path root) {
        // Oracle: AC-14.1 — curating is forgiving: removing a slug that does not exist reports
        // cleanly (exit 0) rather than failing.
        int code = command(new MemoryStore(root)).remove("never-existed");

        assertEquals(0, code, "rm of an absent slug is a clean no-op (exit 0)");
        assertTrue(output().contains("never-existed"),
                "the message names the absent slug; was: " + output());
    }

    @Test
    @DisplayName("run() dispatches each parsed action to the matching operation")
    void run_dispatchesByAction(@TempDir Path root) {
        // Oracle: 04-apis § 1.2 — the four actions (list/show/edit/rm) are the memory subcommand
        // surface. run() routes each parsed action; LIST ignores the slug, the others use it.
        MemoryStore store = new MemoryStore(root);
        store.write(entry("dispatch-slug", MemoryTier.GLOBAL, "reason", "content"), REPO_KEY);

        assertEquals(0, command(store).run(CliArguments.MemoryAction.LIST, null),
                "LIST dispatches without a slug");
        assertTrue(output().contains("dispatch-slug"), "LIST routed to the listing");

        assertEquals(0, command(store).run(CliArguments.MemoryAction.SHOW, "dispatch-slug"),
                "SHOW dispatches with the slug");
        assertEquals(0, command(store).run(CliArguments.MemoryAction.EDIT, "dispatch-slug"),
                "EDIT dispatches with the slug");
        assertEquals(0, command(store).run(CliArguments.MemoryAction.RM, "dispatch-slug"),
                "RM dispatches with the slug");
    }

    @Test
    @DisplayName("the command rejects a blank repo key and a blank slug")
    void rejectsBadInput(@TempDir Path root) {
        MemoryStore store = new MemoryStore(root);
        assertThrows(IllegalArgumentException.class, () -> new MemoryCommand(store, "  ", out),
                "a blank repo key is rejected");
        assertThrows(IllegalArgumentException.class, () -> command(store).show("  "),
                "a blank slug is rejected");
    }
}
