package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.OperationClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code write_artifact} tool (component C9, ADR-0012 greenfield side): writes a greenfield
 * design-doc artifact (the requirements/design/tasks markdown) into the <em>target</em> project,
 * confined to the target repo's design-doc directory by a {@link GreenfieldArtifactStore} (RD-7,
 * AC-1.2, AC-2.1).
 *
 * <p><b>The design-doc write that is allowed in the pre-approval phases — distinct from the
 * withheld source-write tool (AC-1.4).</b> ADR-0012 makes design-markdown writes the one write the
 * agent is allowed before the breakdown is approved ("the agent writes only design markdown … until
 * the breakdown is approved"), while AC-1.4 forbids any Class-X operation against <em>source</em>
 * files in that dialogue. This tool resolves that tension: it is offered to the model in the
 * pre-approval phases (requirements/design/tasks), but the {@link GreenfieldArtifactStore} confines
 * every write to the target repo's {@code design/} artifact directory, so the model can persist the
 * requirements/design/tasks markdown (AC-1.2/AC-2.1) yet <em>cannot</em> reach a source file. The
 * general source-write tool ({@link WriteFileTool}, which can write anywhere in the workspace) stays
 * withheld in those phases — so a design-markdown write succeeds while a source write does not.
 *
 * <p>It is Class X ({@link OperationClass#SIDE_EFFECTING}) — a mutating write the permission mode
 * gates (AC-5.2) — but path-scoped to the artifact directory, which is what makes it safe to offer
 * pre-approval where the unscoped {@link WriteFileTool} is not.
 *
 * <p>Inputs: {@code path} (required, target-repo-relative, must resolve under {@code design/}) and
 * {@code content} (required). The result is a short ok-summary string reporting the artifact path
 * and size, so the model has a concise confirmation without echoing the whole document back.
 *
 * <p><b>The input schema is the design-doc-artifact schema, not the generic write-file schema
 * (T-3.2-RD-D9).</b> {@link #inputSchema()} returns {@link ToolSchemas#writeArtifact()} — the same
 * field <em>names</em> ({@code path}, {@code content}) the generic source-write tool uses, but with
 * field <em>descriptions</em> that state this tool's design-doc contract (a {@code design/}-relative
 * artifact path, with the concrete {@code design/00-requirements.md} example, and the full markdown
 * deliverable). Reusing the generic write-file schema's "file to write" descriptions read like a
 * source-file writer to the model and — against the greenfield prompt's no-source-write rule — made
 * the live model never call the tool, so the design-doc content was never persisted.
 */
public final class WriteArtifactTool implements ToolHandler {

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "write_artifact";

    private final GreenfieldArtifactStore store;

    /**
     * Creates the tool over a target-repo-scoped artifact store.
     *
     * @param store the artifact store the writes are confined through; must not be {@code null}.
     * @throws NullPointerException if {@code store} is {@code null}.
     */
    public WriteArtifactTool(GreenfieldArtifactStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Write a design-doc artifact (requirements/design/tasks markdown) into the target "
                + "project's design/ directory. Use this in the requirements, design, and tasks "
                + "phases to persist the agreed artifacts; it cannot write source files.";
    }

    @Override
    public Document inputSchema() {
        // The DEDICATED design-doc-artifact schema (T-3.2-RD-D9), not the generic write_file schema.
        // Its field descriptions state the design-doc contract (a path under design/, with the
        // concrete artifact-path example, and the markdown deliverable content) so the schema the
        // model fills its arguments from agrees with this tool's purpose and the greenfield prompt's
        // no-source-write rule — the prior reuse of the write_file schema's "file to write"
        // descriptions read like a generic source-file writer and steered the live model away from
        // calling the tool at all (RD-7, AC-1.2, AC-2.1; ADR-0012).
        return ToolSchemas.writeArtifact();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.SIDE_EFFECTING;
    }

    /**
     * Writes the artifact content and returns a one-line ok summary.
     *
     * @param input the {@code toolUse.input}: requires {@code path} (under {@code design/}) and
     *              {@code content}.
     * @return a summary string reporting the artifact path and new size.
     * @throws ToolInvocationException if {@code path} is missing/blank or resolves outside the
     *                                 target repo's design-doc directory, {@code content} is
     *                                 missing, or the write fails.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        String path = ToolInputs.requireString(input, "path");
        String content = requireContent(input);
        Path file = store.write(path, content);
        int byteCount = content.getBytes(StandardCharsets.UTF_8).length;
        long lineCount = content.isEmpty() ? 0 : content.lines().count();
        return "ok: wrote artifact " + file + " (" + byteCount + " bytes, " + lineCount + " lines)";
    }

    private static String requireContent(Map<String, Object> input) {
        Object value = input.get("content");
        if (value == null) {
            throw new ToolInvocationException("missing required input 'content'");
        }
        if (!(value instanceof String s)) {
            throw new ToolInvocationException(
                    "input 'content' must be a string but was " + value.getClass().getSimpleName());
        }
        return s;
    }
}
