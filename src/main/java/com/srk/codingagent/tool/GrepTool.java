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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code grep} tool (component C9, 04-apis § 3): textual regular-expression search over
 * the lines of workspace files, so the model can find code by content during exploration
 * (US-4, AC-4.1). It is Class R ({@link OperationClass#READ}) — auto-approved and never
 * gated (AC-4.4).
 *
 * <p><b>Textual search only (AC-4.2).</b> {@code grep} compiles the {@code pattern} as a
 * {@link Pattern} and tests each file line with {@link java.util.regex.Matcher#find()} — it
 * is a line/regex text search, never a parser; it does not understand syntax, build an AST,
 * or consult an LSP.
 *
 * <p>Inputs: {@code pattern} (required regex), {@code path} (optional, a workspace-relative
 * file or directory to scope the search; defaults to the whole workspace), {@code glob}
 * (optional, a filename glob limiting which files are searched, e.g. {@code *.java}), and
 * {@code ignoreCase} (optional boolean, default {@code false}).
 *
 * <p>The result is one line per match in the fixed shape
 * <code>relativePath:lineNumber:lineText</code> (line numbers 1-based), in path then
 * line-number order, joined by newlines; no match returns the empty string (a
 * found-nothing result, not an error). A {@code path} that does not exist is a
 * {@link ToolInvocationException} (AC-4.3 — report rather than fabricate). Output disposal
 * for very large match sets (NFR-OUTPUT-MAX-INLINE) is a later task (T-1.5); this tool
 * returns the matches in full. The search root and every emitted path are confined to the
 * workspace via {@link WorkspacePaths}.
 */
public final class GrepTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GrepTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "grep";

    private final Path root;
    private final WorkspacePaths paths;

    /**
     * Creates the tool rooted at the given workspace.
     *
     * @param workspaceRoot the workspace root the search is scoped to and paths resolve
     *                      against; must not be {@code null}.
     * @throws NullPointerException if {@code workspaceRoot} is {@code null}.
     */
    public GrepTool(Path workspaceRoot) {
        this.root = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
        this.paths = new WorkspacePaths(workspaceRoot);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search file lines for a regular expression. "
                + "Returns matches as 'path:line:text'. Textual search only.";
    }

    @Override
    public Document inputSchema() {
        return ToolSchemas.grep();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.READ;
    }

    /**
     * Searches the scoped files for the regex and returns the matching lines.
     *
     * @param input the {@code toolUse.input}: requires {@code pattern}; honors optional
     *              {@code path}, {@code glob}, and {@code ignoreCase}.
     * @return the matches as {@code path:line:text} rows, one per line; the empty string
     *         when nothing matches.
     * @throws ToolInvocationException if {@code pattern} is missing/blank or an invalid
     *                                 regex, {@code path} escapes the workspace or does not
     *                                 exist, {@code glob} is an invalid glob, or a file
     *                                 cannot be read.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        Pattern pattern = compile(
                ToolInputs.requireString(input, "pattern"),
                ToolInputs.optionalBoolean(input, "ignoreCase", false));
        Path searchRoot = searchRoot(input);
        PathMatcher globFilter = globFilter(input);

        LOGGER.info("Grepping '{}' under {}", pattern.pattern(), searchRoot);
        List<String> matches = new ArrayList<>();
        for (Path file : filesToSearch(searchRoot, globFilter)) {
            searchFile(file, pattern, matches);
        }
        return String.join("\n", matches);
    }

    private List<Path> filesToSearch(Path searchRoot, PathMatcher globFilter) {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(searchRoot)) {
            files.add(searchRoot);
            return files;
        }
        try (Stream<Path> walk = Files.walk(searchRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(file -> globFilter == null || globFilter.matches(root.relativize(file)))
                    .forEach(files::add);
        } catch (IOException | UncheckedIOException e) {
            LOGGER.error("Failed to walk {} for grep", searchRoot, e);
            throw new ToolInvocationException("failed to search: " + searchRoot, e);
        }
        files.sort(Path::compareTo);
        return files;
    }

    private void searchFile(Path file, Pattern pattern, List<String> matches) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            LOGGER.error("Failed to read file {} during grep", file, e);
            throw new ToolInvocationException("failed to read file: " + file, e);
        } catch (UncheckedIOException e) {
            // A non-UTF-8 file is skipped (a binary or non-text file is not a grep target),
            // not a tool failure — the model can still search the rest of the tree.
            LOGGER.debug("Skipping non-UTF-8 file {} during grep", file);
            return;
        }
        String relativePath = root.relativize(file).toString();
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                matches.add(relativePath + ":" + (i + 1) + ":" + lines.get(i));
            }
        }
    }

    private Path searchRoot(Map<String, Object> input) {
        String path = ToolInputs.optionalString(input, "path");
        if (path == null) {
            return root;
        }
        Path resolved = paths.resolve(path);
        if (!Files.exists(resolved)) {
            throw new ToolInvocationException("no such file or directory: " + resolved);
        }
        return resolved;
    }

    private static PathMatcher globFilter(Map<String, Object> input) {
        String glob = ToolInputs.optionalString(input, "glob");
        if (glob == null) {
            return null;
        }
        try {
            // An invalid glob throws IllegalArgumentException (PatternSyntaxException is a
            // subtype), surfaced to the model as a tool error rather than crashing the loop.
            return FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (IllegalArgumentException e) {
            throw new ToolInvocationException("invalid glob pattern: '" + glob + "'", e);
        }
    }

    private static Pattern compile(String pattern, boolean ignoreCase) {
        try {
            return Pattern.compile(pattern, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolInvocationException("invalid regular expression: '" + pattern + "'", e);
        }
    }
}
