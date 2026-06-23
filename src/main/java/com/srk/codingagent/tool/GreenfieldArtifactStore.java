package com.srk.codingagent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists the greenfield design-doc artifacts (the requirements/design/tasks markdown) into the
 * <em>target</em> project repository and confines every artifact path to that repository's
 * workspace root (component C9, ADR-0012 greenfield side; RD-7).
 *
 * <p><b>The design-doc write path that is allowed in the pre-approval phases (AC-1.4 / ADR-0012).</b>
 * RD-7 / AC-1.2 / AC-2.1 require the agent to persist requirements, design, and task-breakdown
 * markdown in the target project, and ADR-0012 makes those design-markdown writes the one write the
 * agent is allowed in the pre-approval phases ("the agent writes only design markdown … until the
 * breakdown is approved"). But AC-1.4 forbids any Class-X operation <em>against source files</em>
 * while in that dialogue. This store is the seam that reconciles the two: it writes <em>only</em>
 * under the target repo's design-doc directory ({@code design/}) — a write that targets anything
 * outside that directory is refused — so a design-markdown artifact can be authored while a general
 * source write (which would go through {@link WriteFileTool}) cannot. The pre-approval registry
 * offers a {@link WriteArtifactTool} over this store and withholds {@link WriteFileTool}, so the
 * design-doc write path is distinct from the withheld source-write path.
 *
 * <p><b>Target-repo confinement (the G1 working-dir lesson).</b> The artifacts land in the target
 * project, identified by the injected {@code workspaceRoot} (AC-6.2 — the working directory; in the
 * live wiring {@code Path.of("").toAbsolutePath()}), which is distinct from codingAgent's own
 * {@code design/} tree. Every path is resolved against {@code workspaceRoot} and confined to it via
 * the shared {@link WorkspacePaths} (the same {@code resolve + startsWith} check {@link ReadFileTool}
 * and {@link WriteFileTool} use), and is additionally required to sit under the artifact directory,
 * so an artifact can never escape the target repo.
 *
 * <p>Not thread-safe: one store serves one greenfield session on a single thread.
 */
public final class GreenfieldArtifactStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(GreenfieldArtifactStore.class);

    /**
     * The target-repo-relative directory the greenfield design-doc artifacts live under. Mirrors
     * the shape of codingAgent's own {@code design/} tree (ADR-0012 reflexive consistency), but for
     * the target project. A write outside this directory is refused so the design-doc write path
     * cannot reach source files (AC-1.4).
     */
    public static final String ARTIFACT_DIR = "design";

    /**
     * The line prefix the greenfield approval gate stamps into a phase's artifact when the developer
     * confirms it (AC-1.5). On disk, an artifact carrying a line that starts with this marker is a
     * <em>prior finalized</em> deliverable: the gate appends the stamp only after the developer
     * approved the phase and the session advanced (ADR-0012, AC-1.5). This store treats that stamp as
     * the durable "this artifact was approved" signal and refuses to clobber such an artifact with a
     * truncating {@link #write} (D13 — per-session artifact isolation).
     *
     * <p><b>Why the store keeps its own copy of the marker.</b> The stamp itself is formatted by the
     * workflow-layer {@code ApprovalStamp}; the {@code workflow} package depends on this {@code tool}
     * package (the greenfield driver writes through this store), so a {@code tool}&rarr;{@code workflow}
     * back-dependency would be circular. The store therefore detects the stamp by this self-contained
     * marker; a unit test pins that the workflow's {@code ApprovalStamp.line(...)} produces a line this
     * marker recognizes, so the two cannot drift.
     */
    public static final String APPROVAL_STAMP_MARKER = "Approved:";

    private final WorkspacePaths paths;
    private final Path artifactRoot;

    /**
     * Creates a store rooted at the target repository's workspace root.
     *
     * @param workspaceRoot the target project's working directory the artifacts are written under
     *                      (AC-6.2); must not be {@code null}.
     * @throws NullPointerException if {@code workspaceRoot} is {@code null}.
     */
    public GreenfieldArtifactStore(Path workspaceRoot) {
        this.paths = new WorkspacePaths(Objects.requireNonNull(workspaceRoot, "workspaceRoot"));
        this.artifactRoot = paths.resolve(ARTIFACT_DIR);
    }

    /**
     * Resolves a target-repo-relative artifact path, confining it to the workspace root <em>and</em>
     * to the artifact directory ({@link #ARTIFACT_DIR}). A path that escapes the workspace, or that
     * resolves outside the artifact directory (e.g. a {@code src/} source file), is refused.
     *
     * @param relativePath the target-repo-relative artifact path (e.g. {@code design/00-requirements.md});
     *                     non-blank.
     * @return the absolute, normalized artifact path inside the target repo's artifact directory.
     * @throws ToolInvocationException if the path escapes the workspace or the artifact directory.
     */
    public Path resolveArtifact(String relativePath) {
        Path resolved = paths.resolve(relativePath);
        if (!resolved.startsWith(artifactRoot)) {
            throw new ToolInvocationException(
                    "not a design-doc artifact path: '" + relativePath + "' resolves outside the "
                            + ARTIFACT_DIR + "/ artifact directory (source writes are not allowed "
                            + "on the design-doc write path)");
        }
        return resolved;
    }

    /**
     * Writes {@code content} to the artifact at {@code relativePath}, creating or replacing it
     * (creating the artifact directory if absent), and returns the artifact's absolute path.
     *
     * <p><b>Refuses to clobber a prior-approved artifact (D13 — per-session artifact isolation).</b>
     * The write is a truncating overwrite — within one greenfield run it is how a phase refines its
     * current (unstamped) deliverable each round (DCR-2, ADR-0012), which is correct. But if the
     * target artifact already carries a greenfield approval stamp ({@link #APPROVAL_STAMP_MARKER},
     * AC-1.5), that artifact is a deliverable a <em>prior, finalized</em> run already approved (the
     * gate stamps only after approval + advance, and one run never re-truncates an artifact it
     * stamped). Truncating it would silently destroy approved work, so the write is refused with an
     * {@link ApprovedArtifactProtectedException}. The approval stamp itself and the implement-loop
     * completion mark are appended via {@link #appendLine}, not this method, so they are unaffected.
     *
     * @param relativePath the target-repo-relative artifact path; non-blank.
     * @param content      the full artifact content; must not be {@code null}.
     * @return the absolute path the artifact was written to.
     * @throws NullPointerException               if {@code content} is {@code null}.
     * @throws ApprovedArtifactProtectedException  if the target artifact already carries a prior
     *                                            greenfield approval stamp (AC-1.5; D13).
     * @throws ToolInvocationException            if the path escapes the artifact directory or the
     *                                            write fails.
     */
    public Path write(String relativePath, String content) {
        Objects.requireNonNull(content, "content");
        Path file = resolveArtifact(relativePath);
        refuseClobberOfApprovedArtifact(relativePath, file);
        writeString(file, content);
        LOGGER.info("Wrote greenfield artifact {} ({} bytes)", file,
                content.getBytes(StandardCharsets.UTF_8).length);
        return file;
    }

    /**
     * Refuses a truncating {@link #write} that would overwrite an artifact already carrying a prior
     * greenfield approval stamp (AC-1.5; D13). An artifact gains its stamp only after the developer
     * approved its phase and the session advanced, and a single greenfield run never re-truncates an
     * artifact it stamped — so an already-stamped artifact on disk belongs to a prior finalized run,
     * and overwriting it would silently destroy approved work.
     */
    private void refuseClobberOfApprovedArtifact(String relativePath, Path file) {
        if (carriesApprovalStamp(file)) {
            LOGGER.warn("Refusing to overwrite greenfield artifact {} ({}): it already carries a "
                    + "prior approval stamp (AC-1.5) — overwriting it would silently destroy an "
                    + "approved deliverable (D13)", relativePath, file);
            throw new ApprovedArtifactProtectedException(
                    "refusing to overwrite '" + relativePath + "': it already holds an approved "
                            + "greenfield deliverable (it carries an '" + APPROVAL_STAMP_MARKER
                            + "' approval stamp, AC-1.5). A new run must not silently overwrite a "
                            + "prior approved artifact; resume the existing session or write to a "
                            + "different target project (D13).");
        }
    }

    /**
     * Whether the artifact on disk carries a greenfield approval stamp — a line beginning with
     * {@link #APPROVAL_STAMP_MARKER} (AC-1.5). An absent artifact carries none.
     */
    private boolean carriesApprovalStamp(Path file) {
        return readIfPresent(file)
                .map(GreenfieldArtifactStore::hasApprovalStampLine)
                .orElse(false);
    }

    private static boolean hasApprovalStampLine(String content) {
        return content.lines().anyMatch(line -> line.stripLeading().startsWith(APPROVAL_STAMP_MARKER));
    }

    /**
     * Appends {@code line} as a new trailing line to the artifact at {@code relativePath}, creating
     * the artifact (and a single trailing newline before the line if the existing content does not
     * end with one) if it is absent or does not yet end with a newline. Used to stamp the
     * approval-timestamp line into a phase's artifact (AC-1.5) without rewriting the model-authored
     * body.
     *
     * @param relativePath the target-repo-relative artifact path; non-blank.
     * @param line         the line to append (without a trailing newline); must not be {@code null}.
     * @return the absolute path the line was appended to.
     * @throws NullPointerException    if {@code line} is {@code null}.
     * @throws ToolInvocationException if the path escapes the artifact directory or the append fails.
     */
    public Path appendLine(String relativePath, String line) {
        Objects.requireNonNull(line, "line");
        Path file = resolveArtifact(relativePath);
        String existing = readIfPresent(file).orElse("");
        StringBuilder next = new StringBuilder(existing);
        if (!existing.isEmpty() && !existing.endsWith("\n")) {
            next.append('\n');
        }
        next.append(line).append('\n');
        writeString(file, next.toString());
        LOGGER.info("Appended approval line to greenfield artifact {}", file);
        return file;
    }

    /**
     * Reads the artifact at {@code relativePath} if it exists.
     *
     * @param relativePath the target-repo-relative artifact path; non-blank.
     * @return the artifact content, or {@link Optional#empty()} if the artifact does not exist.
     * @throws ToolInvocationException if the path escapes the artifact directory or the read fails.
     */
    public Optional<String> read(String relativePath) {
        return readIfPresent(resolveArtifact(relativePath));
    }

    /**
     * Whether the artifact at {@code relativePath} carries a greenfield approval stamp on disk &mdash;
     * a line beginning with {@link #APPROVAL_STAMP_MARKER} (AC-1.5). An absent artifact carries none.
     *
     * <p><b>One durable on-disk fact, two readers (AC-1.5; DCR-3).</b> The AC-1.5 approval stamp is
     * the single durable signal that a phase's deliverable was approved. This accessor exposes the
     * <em>same</em> stamp-detection the D13 clobber guard ({@link #refuseClobberOfApprovedArtifact})
     * keys on, so the greenfield mid-flow resume re-derivation (which treats a stamped phase artifact
     * as approved and resumes at the first unstamped/absent phase, AC-7.6) and the clobber guard
     * share one detection rather than duplicating it.
     *
     * @param relativePath the target-repo-relative artifact path; non-blank.
     * @return {@code true} if the artifact exists and carries an approval stamp; {@code false} if it
     *         is unstamped or absent.
     * @throws ToolInvocationException if the path escapes the artifact directory or the read fails.
     */
    public boolean isApprovalStamped(String relativePath) {
        return carriesApprovalStamp(resolveArtifact(relativePath));
    }

    private Optional<String> readIfPresent(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.error("Failed to read greenfield artifact {}", file, e);
            throw new ToolInvocationException("failed to read artifact: " + file, e);
        }
    }

    private static void writeString(Path file, String content) {
        if (Files.isDirectory(file)) {
            throw new ToolInvocationException("cannot write artifact to a directory: " + file);
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to write greenfield artifact {}", file, e);
            throw new ToolInvocationException("failed to write artifact: " + file, e);
        }
    }
}
