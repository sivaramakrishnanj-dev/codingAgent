package com.srk.codingagent.tool;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves a model-supplied, workspace-relative path against the injected workspace root
 * and confines it to that root, expressing the C9 invariant that file tools operate
 * <em>within the workspace</em> in one place (shared by {@link ReadFileTool} and
 * {@link WriteFileTool}).
 *
 * <p>The workspace root is an injected argument (ADR-0003: the executor adds nothing
 * implicitly; later wiring supplies the root). A resolved path that escapes the root —
 * via {@code ../} traversal or an absolute path outside it — is refused with a
 * {@link ToolInvocationException} so a tool cannot read or write outside the workspace.
 *
 * <p>Package-private; not part of the tool public API.
 */
final class WorkspacePaths {

    private final Path root;

    WorkspacePaths(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /**
     * Resolves {@code relativePath} against the workspace root and confirms the result
     * stays inside the root.
     *
     * @param relativePath the model-supplied path; non-blank.
     * @return the absolute, normalized path inside the workspace.
     * @throws ToolInvocationException if the resolved path escapes the workspace root.
     */
    Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new ToolInvocationException(
                    "path escapes the workspace: '" + relativePath + "' resolves outside " + root);
        }
        return resolved;
    }
}
