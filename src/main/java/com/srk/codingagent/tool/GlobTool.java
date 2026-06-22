package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.OperationClass;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code glob} tool (component C9, 04-apis § 3): finds files whose workspace-relative
 * path matches a glob pattern, so the model can locate files by name/shape during
 * exploration (US-4). It is Class R ({@link OperationClass#READ}) — auto-approved and never
 * gated (AC-4.4).
 *
 * <p>Inputs: {@code pattern} (required, a path glob such as {@code **&#47;*.java} or
 * {@code src/*.txt}) and {@code path} (optional, a workspace-relative directory to root the
 * walk; defaults to the whole workspace). The glob is matched against each regular file's
 * <em>workspace-relative</em> path using a {@link PathMatcher} (textual path matching, not
 * an AST — AC-4.2). The result is the matching workspace-relative paths, sorted, one per
 * line; no match returns the empty string (a found-nothing result, not an error). A
 * {@code path} that does not exist or is not a directory is a
 * {@link ToolInvocationException} (AC-4.3). Both the search root and every emitted path are
 * confined to the workspace via {@link WorkspacePaths}.
 */
public final class GlobTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "glob";

    private final Path root;
    private final WorkspacePaths paths;

    /**
     * Creates the tool rooted at the given workspace.
     *
     * @param workspaceRoot the workspace root the walk is scoped to and paths resolve
     *                      against; must not be {@code null}.
     * @throws NullPointerException if {@code workspaceRoot} is {@code null}.
     */
    public GlobTool(Path workspaceRoot) {
        this.root = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
        this.paths = new WorkspacePaths(workspaceRoot);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Find files whose workspace-relative path matches a glob pattern "
                + "(e.g. '**/*.java'). Returns matching workspace-relative paths.";
    }

    @Override
    public Document inputSchema() {
        return ToolSchemas.glob();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.READ;
    }

    /**
     * Finds files matching the glob pattern.
     *
     * @param input the {@code toolUse.input}: requires {@code pattern}; honors optional
     *              {@code path} (search root).
     * @return the matching workspace-relative paths, sorted, one per line; the empty string
     *         when nothing matches.
     * @throws ToolInvocationException if {@code pattern} is missing/blank or syntactically
     *                                 invalid, {@code path} escapes the workspace or is not
     *                                 a directory, or the walk fails.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        String pattern = ToolInputs.requireString(input, "pattern");
        Path searchRoot = searchRoot(input);
        PathMatcher matcher = matcher(pattern);

        LOGGER.info("Globbing '{}' under {}", pattern, searchRoot);
        List<String> matches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(searchRoot)) {
            walk.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(matcher::matches)
                    .map(Path::toString)
                    .forEach(matches::add);
        } catch (IOException | UncheckedIOException e) {
            LOGGER.error("Failed to walk {} for glob '{}'", searchRoot, pattern, e);
            throw new ToolInvocationException("failed to search: " + searchRoot, e);
        }
        matches.sort(String::compareTo);
        return String.join("\n", matches);
    }

    private Path searchRoot(Map<String, Object> input) {
        String path = ToolInputs.optionalString(input, "path");
        if (path == null) {
            return root;
        }
        Path resolved = paths.resolve(path);
        if (!Files.isDirectory(resolved)) {
            throw new ToolInvocationException("not a directory: " + resolved);
        }
        return resolved;
    }

    private static PathMatcher matcher(String pattern) {
        try {
            // An invalid glob throws IllegalArgumentException (PatternSyntaxException is a
            // subtype), surfaced to the model as a tool error rather than crashing the loop.
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {
            throw new ToolInvocationException("invalid glob pattern: '" + pattern + "'", e);
        }
    }
}
