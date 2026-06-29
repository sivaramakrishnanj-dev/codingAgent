package com.srk.codingagent.cli;

import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryIndexLine;
import com.srk.codingagent.memory.MemoryStore;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The testable orchestration behind the {@code memory [list|show <slug>|edit <slug>|rm <slug>]}
 * subcommand (04-apis § 1.2, US-14): it inspects and curates the two-tier curated memory the
 * developer can also hand-edit on disk (AC-14.1/14.3). It is the seam {@link Main} delegates the
 * {@code memory} subcommand to — pure on-disk persistence over an injected {@link MemoryStore}
 * (no config resolution or model call) — so the inspect/curate logic is unit-tested against a
 * real store over a temp directory (never mocked).
 *
 * <p><b>Actions.</b> {@link #list()} prints the two-tier index one-liners (the always-loaded
 * awareness surface, AC-14.3); {@link #show(String)} prints an entry's full markdown (AC-14.1);
 * {@link #edit(String)} resolves and prints the entry's on-disk path so the developer can
 * hand-edit it (AC-14.1 — memory is "also hand-editable on disk"; the command points at the file
 * rather than spawning an editor); {@link #remove(String)} deletes an entry and its index line
 * (AC-14.1/14.3). Inspecting an empty store, showing/editing/removing a missing slug are each
 * reported cleanly (a clear message, exit {@link ExitCode#OK}) rather than treated as a crash;
 * only a bad invocation upstream (an unknown action) is the {@link ExitCode#USAGE_CONFIG} path.
 *
 * <p><b>Library/CLI split (NFR-LOG, 04-apis § 1.6).</b> This CLI-layer command owns its
 * user-facing output (it writes to the injected {@link PrintStream}); the {@link MemoryStore}
 * library it drives never writes to stdout/stderr.
 */
public final class MemoryCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryCommand.class);

    private final MemoryStore store;
    private final String repoKey;
    private final PrintStream out;

    /**
     * Creates a command over the memory store for one repository.
     *
     * @param store   the curated-memory store to inspect / curate; must not be {@code null}.
     * @param repoKey the repository key to scope the PROJECT tier to; non-blank.
     * @param out     the stream the command output is written to; must not be {@code null}.
     * @throws NullPointerException     if {@code store} or {@code out} is {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} is blank.
     */
    public MemoryCommand(MemoryStore store, String repoKey, PrintStream out) {
        this.store = Objects.requireNonNull(store, "store");
        if (Objects.requireNonNull(repoKey, "repoKey").isBlank()) {
            throw new IllegalArgumentException("repoKey must be non-blank");
        }
        this.repoKey = repoKey;
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * Dispatches a parsed {@code memory} invocation to its action.
     *
     * @param action the parsed action (04-apis § 1.2); must not be {@code null}.
     * @param slug   the entry slug for {@code show}/{@code edit}/{@code rm}; {@code null} (or
     *               absent) for {@code list}.
     * @return the process exit code the action returns.
     * @throws NullPointerException if {@code action} is {@code null}.
     */
    public int run(CliArguments.MemoryAction action, String slug) {
        Objects.requireNonNull(action, "action");
        return switch (action) {
            case LIST -> list();
            case SHOW -> show(slug);
            case EDIT -> edit(slug);
            case RM -> remove(slug);
        };
    }

    /**
     * Lists the two-tier memory index, one line per entry (AC-14.3): the always-loaded awareness
     * surface. An empty store is not an error — it prints a clear "no memory" line and exits
     * {@link ExitCode#OK} ({@code 0}).
     *
     * @return {@link ExitCode#OK} ({@code 0}) — listing always succeeds.
     */
    public int list() {
        List<MemoryIndexLine> lines = store.loadIndexes(repoKey);
        if (lines.isEmpty()) {
            out.println("No memory entries.");
            LOGGER.info("listed 0 memory entries for repoKey={}", repoKey);
            return ExitCode.OK.code();
        }
        out.println("Memory entries:");
        for (MemoryIndexLine line : lines) {
            out.println(formatIndexLine(line));
        }
        LOGGER.info("listed {} memory entry/entries for repoKey={}", lines.size(), repoKey);
        return ExitCode.OK.code();
    }

    /**
     * Prints a memory entry's full markdown (AC-14.1): the human-readable entry the developer can
     * read and hand-edit. A slug that names no entry is reported cleanly (a "no such entry" line,
     * exit {@link ExitCode#OK}) rather than treated as a crash — an absent entry may simply have
     * been hand-deleted (AC-14.2).
     *
     * @param slug the entry's slug; non-blank.
     * @return {@link ExitCode#OK} ({@code 0}) whether the entry was found and printed or reported
     *         absent.
     * @throws IllegalArgumentException if {@code slug} is blank.
     */
    public int show(String slug) {
        requireSlug(slug);
        Optional<MemoryEntry> entry = store.readEntry(slug, repoKey);
        if (entry.isEmpty()) {
            out.println("No memory entry with slug '" + slug + "'.");
            LOGGER.info("memory show: no entry with slug {} (repoKey={})", slug, repoKey);
            return ExitCode.OK.code();
        }
        out.println(render(entry.get()));
        LOGGER.info("memory show: printed entry {} (repoKey={})", slug, repoKey);
        return ExitCode.OK.code();
    }

    /**
     * Resolves and prints the on-disk path of an entry's markdown file so the developer can
     * hand-edit it (AC-14.1 — memory is "also hand-editable on disk"). The command points at the
     * file rather than spawning an editor (the file is plain markdown, RD-9). A slug that names no
     * entry is reported cleanly (exit {@link ExitCode#OK}).
     *
     * @param slug the entry's slug; non-blank.
     * @return {@link ExitCode#OK} ({@code 0}) whether the path was resolved or the entry was
     *         reported absent.
     * @throws IllegalArgumentException if {@code slug} is blank.
     */
    public int edit(String slug) {
        requireSlug(slug);
        Optional<Path> path = store.entryPath(slug, repoKey);
        if (path.isEmpty()) {
            out.println("No memory entry with slug '" + slug + "'.");
            LOGGER.info("memory edit: no entry with slug {} (repoKey={})", slug, repoKey);
            return ExitCode.OK.code();
        }
        out.println("Edit this memory entry on disk:");
        out.println("  " + path.get());
        LOGGER.info("memory edit: resolved on-disk path for entry {} (repoKey={})", slug, repoKey);
        return ExitCode.OK.code();
    }

    /**
     * Removes a memory entry and its index line (AC-14.1/14.3 — memory is curatable). A slug that
     * names no entry is reported cleanly (a "no such entry" line, exit {@link ExitCode#OK})
     * rather than treated as an error — removing nothing is a no-op, not a failure.
     *
     * @param slug the entry's slug; non-blank.
     * @return {@link ExitCode#OK} ({@code 0}) whether an entry was removed or reported absent.
     * @throws IllegalArgumentException if {@code slug} is blank.
     */
    public int remove(String slug) {
        requireSlug(slug);
        boolean removed = store.delete(slug, repoKey);
        if (removed) {
            out.println("Removed memory entry '" + slug + "'.");
            LOGGER.info("memory rm: removed entry {} (repoKey={})", slug, repoKey);
        } else {
            out.println("No memory entry with slug '" + slug + "'.");
            LOGGER.info("memory rm: no entry with slug {} (repoKey={})", slug, repoKey);
        }
        return ExitCode.OK.code();
    }

    private void requireSlug(String slug) {
        if (Objects.requireNonNull(slug, "slug").isBlank()) {
            throw new IllegalArgumentException("slug must be non-blank");
        }
    }

    private static String formatIndexLine(MemoryIndexLine line) {
        return "  [" + line.tier() + "] " + line.slug()
                + (line.description().isBlank() ? "" : "  " + line.description());
    }

    private static String render(MemoryEntry entry) {
        StringBuilder out = new StringBuilder()
                .append("slug: ").append(entry.slug()).append('\n')
                .append("tier: ").append(entry.tier()).append('\n')
                .append("created: ").append(entry.created()).append('\n')
                .append("originSession: ").append(entry.originSession()).append('\n')
                .append("why: ").append(entry.why()).append('\n')
                .append("status: ").append(entry.status().wireValue()).append('\n');
        if (!entry.body().isBlank()) {
            out.append('\n').append(entry.body().strip());
        }
        return out.toString();
    }
}
