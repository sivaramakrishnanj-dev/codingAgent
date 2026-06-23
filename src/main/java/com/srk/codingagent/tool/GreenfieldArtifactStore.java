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
     * @param relativePath the target-repo-relative artifact path; non-blank.
     * @param content      the full artifact content; must not be {@code null}.
     * @return the absolute path the artifact was written to.
     * @throws NullPointerException     if {@code content} is {@code null}.
     * @throws ToolInvocationException  if the path escapes the artifact directory or the write fails.
     */
    public Path write(String relativePath, String content) {
        Objects.requireNonNull(content, "content");
        Path file = resolveArtifact(relativePath);
        writeString(file, content);
        LOGGER.info("Wrote greenfield artifact {} ({} bytes)", file,
                content.getBytes(StandardCharsets.UTF_8).length);
        return file;
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
