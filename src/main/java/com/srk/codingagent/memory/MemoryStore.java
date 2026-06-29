package com.srk.codingagent.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two-tier curated-memory store (component C16, ADR-0007, RD-9): on-disk markdown
 * entries plus a per-tier {@code INDEX.md}, under {@code ~/.codingagent/}. Each learning is
 * one human-editable markdown file {@code <slug>.md}; there is no database (AC-12.2,
 * AC-14.1).
 *
 * <p><b>Two tiers (AC-12.3).</b> GLOBAL memory lives under {@code <store>/memory/}
 * (cross-project); PROJECT memory under {@code <store>/projects/<repo-key>/memory/}
 * (repository-specific) — the same store-root + {@code projects/<repo-key>} layout
 * {@code SessionStore} uses, so the two stores sit side by side. The store root defaults to
 * {@code ~/.codingagent} ({@link #forUserHome()}) but is injectable so the store is
 * unit-testable against a temporary directory.
 *
 * <p><b>Re-read-fresh, no masking cache (INV-14, AC-14.2).</b> Every {@link #loadIndexes}
 * and {@link #readEntry} reads from disk; the store holds no in-memory copy of any entry or
 * index. A hand-edited or hand-deleted file is therefore honoured on the very next load,
 * and a write is immediately visible to a subsequent read (CT-INV-12).
 *
 * <p><b>Curated writes only (INV-13).</b> The store's sole mutating method is
 * {@link #write} — an entry is persisted only when a caller invokes it explicitly (T-2.4's
 * "remember X" path) or after the developer approves a proposal (T-2.5's propose-and-approve
 * path calls this same {@code write} after approval). There is no auto-extraction path:
 * nothing in this class writes an entry without an explicit {@link #write} call (CT-INV-11,
 * AC-21.4).
 *
 * <p><b>Provenance / audit (AC-12.4) — caller's seam.</b> {@code write} owns the file + index
 * side of a write and returns the persisted {@link MemoryEntry}; logging the
 * {@code MEMORY_WRITE} event is the <em>caller's</em> responsibility (the explicit
 * {@code write_memory} tool logs it for the developer's "remember X"; T-2.5's approval flow
 * logs it after approval). Keeping the event-log dependency out of the store keeps the
 * propose-and-approve seam clean: T-2.5 reuses {@code write} unchanged and adds only its
 * approval gate + its own event append.
 */
public final class MemoryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryStore.class);

    private static final String STORE_DIR_NAME = ".codingagent";
    private static final String PROJECTS_DIR_NAME = "projects";
    private static final String MEMORY_DIR_NAME = "memory";
    private static final String INDEX_FILE_NAME = "INDEX.md";
    private static final String ENTRY_SUFFIX = ".md";

    private final Path storeRoot;

    /**
     * Creates a store rooted at the given store directory.
     *
     * @param storeRoot the store root ({@code ~/.codingagent} in production); must not be
     *                  {@code null}.
     * @throws NullPointerException if {@code storeRoot} is {@code null}.
     */
    public MemoryStore(Path storeRoot) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot");
    }

    /**
     * Creates a store rooted at the default store directory ({@code ~/.codingagent}),
     * derived from the {@code user.home} system property — the same root
     * {@code SessionStore.forUserHome()} uses.
     *
     * @return a {@code MemoryStore} for the current user's home directory.
     */
    public static MemoryStore forUserHome() {
        return new MemoryStore(Path.of(System.getProperty("user.home"), STORE_DIR_NAME));
    }

    /**
     * Writes a curated memory entry: serializes it to {@code <tier-dir>/<slug>.md} and adds
     * (or replaces) its line in the tier's {@code INDEX.md} (AC-12.1, AC-12.3, AC-14.3). The
     * entry's own {@code tier} selects the destination directory.
     *
     * <p>This is the one persistence path (INV-13): an entry exists on disk only after this
     * explicit call. The caller logs the {@code MEMORY_WRITE} provenance event (AC-12.4); the
     * store does not.
     *
     * @param entry   the entry to persist; must not be {@code null}.
     * @param repoKey the repository key for the PROJECT tier; non-blank (used only when the
     *                entry's tier is {@link MemoryTier#PROJECT}, but always required so the
     *                caller cannot forget it).
     * @return the persisted {@code entry} (unchanged), so the caller can build its event.
     * @throws NullPointerException     if {@code entry} is {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} is blank.
     * @throws MemoryStoreException     if the entry or its index line cannot be written.
     */
    public MemoryEntry write(MemoryEntry entry, String repoKey) {
        Objects.requireNonNull(entry, "entry");
        requireNonBlank(repoKey, "repoKey");
        Path dir = tierDir(entry.tier(), repoKey);
        createDir(dir);
        Path file = dir.resolve(entry.slug() + ENTRY_SUFFIX);
        writeString(file, MemoryMarkdown.render(entry));
        upsertIndexLine(dir, entry);
        LOGGER.info("wrote memory entry {} into {} tier ({})", entry.slug(), entry.tier(), file);
        return entry;
    }

    /**
     * Removes a curated memory entry by slug: deletes its {@code <slug>.md} file and drops its
     * line from the tier's {@code INDEX.md}, in whichever tier(s) hold it (AC-14.1/14.3 — memory
     * is curatable; the {@code memory rm <slug>} subcommand and a hand-delete are equivalent). A
     * delete is honoured on the very next load (INV-14, AC-14.2): the store keeps no masking
     * cache.
     *
     * <p>This is the curating counterpart to {@link #write}: it touches only the on-disk file and
     * index (the caller logs any provenance event, as with {@code write}). Removing a slug that
     * does not exist is a no-op that returns {@code false} rather than an error, so the subcommand
     * can report "no such entry" cleanly.
     *
     * @param slug    the entry's slug; non-blank.
     * @param repoKey the repository key for the PROJECT tier; non-blank.
     * @return {@code true} if an entry file was removed from at least one tier, {@code false} when
     *         no {@code <slug>.md} existed in either tier.
     * @throws IllegalArgumentException if {@code slug} or {@code repoKey} is blank.
     * @throws MemoryStoreException     if a matching file or index cannot be removed/rewritten.
     */
    public boolean delete(String slug, String repoKey) {
        requireNonBlank(slug, "slug");
        requireNonBlank(repoKey, "repoKey");
        boolean removedGlobal = deleteFromTier(MemoryTier.GLOBAL, slug, repoKey);
        boolean removedProject = deleteFromTier(MemoryTier.PROJECT, slug, repoKey);
        boolean removed = removedGlobal || removedProject;
        if (removed) {
            LOGGER.info("removed memory entry {} (global={}, project={})", slug, removedGlobal, removedProject);
        }
        return removed;
    }

    /**
     * Reads a full memory entry by slug, searching the GLOBAL tier then the PROJECT tier,
     * re-reading from disk (INV-14). This backs the {@code read_memory(slug)} tool, which
     * pulls a full entry on demand (ADR-0007).
     *
     * @param slug    the entry's slug; non-blank.
     * @param repoKey the repository key for the PROJECT tier; non-blank.
     * @return the entry if a {@code <slug>.md} exists in either tier (GLOBAL preferred), or
     *         {@link Optional#empty()} when no such entry exists (e.g. it was hand-deleted —
     *         honoured on this fresh read, AC-14.2).
     * @throws IllegalArgumentException if {@code slug} or {@code repoKey} is blank.
     * @throws MemoryStoreException     if a matching file exists but cannot be read or parsed.
     */
    public Optional<MemoryEntry> readEntry(String slug, String repoKey) {
        requireNonBlank(slug, "slug");
        requireNonBlank(repoKey, "repoKey");
        Optional<MemoryEntry> global = readEntry(MemoryTier.GLOBAL, slug, repoKey);
        return global.isPresent() ? global : readEntry(MemoryTier.PROJECT, slug, repoKey);
    }

    /**
     * Reads a full memory entry from a specific tier, re-reading from disk (INV-14).
     *
     * @param tier    the tier to read from; must not be {@code null}.
     * @param slug    the entry's slug; non-blank.
     * @param repoKey the repository key for the PROJECT tier; non-blank.
     * @return the entry, or {@link Optional#empty()} when no {@code <slug>.md} exists in the
     *         tier.
     * @throws NullPointerException     if {@code tier} is {@code null}.
     * @throws IllegalArgumentException if {@code slug} or {@code repoKey} is blank.
     * @throws MemoryStoreException     if the file exists but cannot be read or parsed.
     */
    public Optional<MemoryEntry> readEntry(MemoryTier tier, String slug, String repoKey) {
        Objects.requireNonNull(tier, "tier");
        requireNonBlank(slug, "slug");
        requireNonBlank(repoKey, "repoKey");
        Path file = tierDir(tier, repoKey).resolve(slug + ENTRY_SUFFIX);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        MemoryEntry parsed = MemoryMarkdown.parse(readString(file));
        LOGGER.debug("read memory entry {} from {} tier", slug, tier);
        return Optional.of(parsed);
    }

    /**
     * Resolves the on-disk path of an existing entry's {@code <slug>.md} file, searching the
     * GLOBAL tier then the PROJECT tier (INV-14, re-checked against disk). This backs the
     * {@code memory edit <slug>} subcommand, which points the developer at the markdown file to
     * hand-edit (AC-14.1 — memory is "also hand-editable on disk"); the store does not open an
     * editor itself.
     *
     * @param slug    the entry's slug; non-blank.
     * @param repoKey the repository key for the PROJECT tier; non-blank.
     * @return the path to the existing {@code <slug>.md} (GLOBAL preferred), or
     *         {@link Optional#empty()} when no such entry exists in either tier.
     * @throws IllegalArgumentException if {@code slug} or {@code repoKey} is blank.
     */
    public Optional<Path> entryPath(String slug, String repoKey) {
        requireNonBlank(slug, "slug");
        requireNonBlank(repoKey, "repoKey");
        Path global = tierDir(MemoryTier.GLOBAL, repoKey).resolve(slug + ENTRY_SUFFIX);
        if (Files.isRegularFile(global)) {
            return Optional.of(global);
        }
        Path project = tierDir(MemoryTier.PROJECT, repoKey).resolve(slug + ENTRY_SUFFIX);
        return Files.isRegularFile(project) ? Optional.of(project) : Optional.empty();
    }

    /**
     * Loads both tiers' indexes — the always-loaded awareness surface (ADR-0007, AC-14.3) —
     * re-reading each {@code INDEX.md} from disk (INV-14, AC-14.2). GLOBAL lines precede
     * PROJECT lines; within a tier the lines are returned in file order.
     *
     * @param repoKey the repository key for the PROJECT tier; non-blank.
     * @return the index lines across both tiers; empty when neither tier has an index yet.
     *         Never {@code null}.
     * @throws IllegalArgumentException if {@code repoKey} is blank.
     * @throws MemoryStoreException     if a tier's {@code INDEX.md} exists but cannot be read.
     */
    public List<MemoryIndexLine> loadIndexes(String repoKey) {
        requireNonBlank(repoKey, "repoKey");
        List<MemoryIndexLine> lines = new ArrayList<>();
        lines.addAll(readIndex(MemoryTier.GLOBAL, repoKey));
        lines.addAll(readIndex(MemoryTier.PROJECT, repoKey));
        LOGGER.debug("loaded {} memory index line(s) across both tiers", lines.size());
        return lines;
    }

    /**
     * The directory that holds a tier's entries and its index. GLOBAL =
     * {@code <store>/memory/}; PROJECT = {@code <store>/projects/<repo-key>/memory/}.
     */
    Path tierDir(MemoryTier tier, String repoKey) {
        return switch (tier) {
            case GLOBAL -> storeRoot.resolve(MEMORY_DIR_NAME);
            case PROJECT -> storeRoot.resolve(PROJECTS_DIR_NAME).resolve(repoKey).resolve(MEMORY_DIR_NAME);
        };
    }

    private List<MemoryIndexLine> readIndex(MemoryTier tier, String repoKey) {
        Path index = tierDir(tier, repoKey).resolve(INDEX_FILE_NAME);
        if (!Files.isRegularFile(index)) {
            return List.of();
        }
        List<MemoryIndexLine> lines = new ArrayList<>();
        for (String line : readString(index).lines().toList()) {
            MemoryIndex.parseLine(tier, line).ifPresent(lines::add);
        }
        return lines;
    }

    private void upsertIndexLine(Path dir, MemoryEntry entry) {
        Path index = dir.resolve(INDEX_FILE_NAME);
        List<String> kept = new ArrayList<>();
        if (Files.isRegularFile(index)) {
            for (String line : readString(index).lines().toList()) {
                if (!MemoryIndex.isLineFor(line, entry.slug())) {
                    kept.add(line);
                }
            }
        }
        kept.add(MemoryIndex.renderLine(entry));
        writeString(index, String.join("\n", kept) + "\n");
    }

    /**
     * Deletes a slug's entry file from one tier and drops its index line. Returns whether the
     * entry file existed (and was removed); the index is rewritten regardless so a stale line a
     * hand-edit may have left is dropped too.
     */
    private boolean deleteFromTier(MemoryTier tier, String slug, String repoKey) {
        Path dir = tierDir(tier, repoKey);
        Path file = dir.resolve(slug + ENTRY_SUFFIX);
        boolean existed = Files.isRegularFile(file);
        if (existed) {
            deleteFile(file);
        }
        removeIndexLine(dir, slug);
        return existed;
    }

    private void removeIndexLine(Path dir, String slug) {
        Path index = dir.resolve(INDEX_FILE_NAME);
        if (!Files.isRegularFile(index)) {
            return;
        }
        List<String> kept = new ArrayList<>();
        for (String line : readString(index).lines().toList()) {
            if (!MemoryIndex.isLineFor(line, slug)) {
                kept.add(line);
            }
        }
        writeString(index, kept.isEmpty() ? "" : String.join("\n", kept) + "\n");
    }

    private static void deleteFile(Path file) {
        try {
            Files.delete(file);
        } catch (IOException e) {
            LOGGER.error("failed to delete memory file {}", file, e);
            throw new MemoryStoreException("failed to delete memory file: " + file, e);
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }

    private static void createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new MemoryStoreException("failed to create memory directory: " + dir, e);
        }
    }

    private static void writeString(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("failed to write memory file {}", file, e);
            throw new MemoryStoreException("failed to write memory file: " + file, e);
        }
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("failed to read memory file {}", file, e);
            throw new MemoryStoreException("failed to read memory file: " + file, e);
        }
    }
}
