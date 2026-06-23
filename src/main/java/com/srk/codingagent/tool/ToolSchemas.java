package com.srk.codingagent.tool;

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.document.Document;

/**
 * Authors the JSON-Schema {@code inputSchema} documents for the built-in tools as SDK
 * {@link Document}s, so each {@link ToolHandler} declares its input contract once and the
 * {@link ToolRegistry} renders it straight into a {@code toolSpec.inputSchema} (ADR-0001).
 *
 * <p>Building the schemas directly as {@link Document}s keeps the tool component (C7/C9/
 * C10) self-contained: it does not reach into the converse wire mapper's
 * package-private value↔document conversion, and it does not duplicate that conversion —
 * the schemas here are small fixed structures, not arbitrary runtime values.
 *
 * <p>Package-private utility; not part of the tool public API.
 */
final class ToolSchemas {

    private ToolSchemas() {
        // Non-instantiable.
    }

    /** JSON Schema {@code type: "object"} discriminator value. */
    private static final String TYPE_OBJECT = "object";

    /** JSON Schema {@code type: "string"} discriminator value. */
    private static final String TYPE_STRING = "string";

    /** JSON Schema {@code type: "integer"} discriminator value. */
    private static final String TYPE_INTEGER = "integer";

    /** JSON Schema {@code type: "boolean"} discriminator value. */
    private static final String TYPE_BOOLEAN = "boolean";

    /**
     * The {@code read_file} input schema: required {@code path}, optional {@code offset}
     * and {@code limit} (04-apis § 3).
     *
     * @return the input-schema document for {@code read_file}.
     */
    static Document readFile() {
        return objectSchema(
                Map.of(
                        "path", stringProperty("Workspace-relative path of the file to read."),
                        "offset", integerProperty("Optional 1-based line to start reading from."),
                        "limit", integerProperty("Optional maximum number of lines to read.")),
                List.of("path"));
    }

    /**
     * The {@code write_file} input schema: required {@code path} and {@code content}
     * (04-apis § 3).
     *
     * @return the input-schema document for {@code write_file}.
     */
    static Document writeFile() {
        return objectSchema(
                Map.of(
                        "path", stringProperty("Workspace-relative path of the file to write."),
                        "content", stringProperty("The full new contents of the file.")),
                List.of("path", "content"));
    }

    /**
     * The {@code write_artifact} input schema: required {@code path} and {@code content}, with
     * <em>design-doc-artifact</em> field descriptions (RD-7, AC-1.2, AC-2.1; ADR-0012).
     *
     * <p><b>Why this is distinct from {@link #writeFile()} (the T-3.2-RD-D9 root cause).</b> The
     * {@code write_artifact} tool deliberately keeps the same field <em>names</em> as
     * {@link #writeFile()} ({@code path}, {@code content}) — {@link WriteArtifactTool#handle} reads
     * those names and the greenfield prompt references them — but it must NOT reuse {@code writeFile()}
     * 's field <em>descriptions</em>. Those describe a generic source-file write ("Workspace-relative
     * path of the file to write", "The full new contents of the file"), which directly contradicts the
     * greenfield pre-approval prompt's emphatic "do not write source / you write design documents this
     * way, not source code". A model handed a tool whose machine-readable schema reads like a generic
     * file writer, while the prompt forbids writing files/source, declines to call it — so on the live
     * path {@code write_artifact} was never invoked and the design-doc content never persisted (only the
     * approval-gate stamp landed). These descriptions instead state the tool's real contract — a
     * design-doc markdown artifact under {@code design/}, with the concrete artifact-path example the
     * model fills {@code path} from — so the schema the model reads agrees with the tool's purpose and
     * the prompt.
     *
     * @return the input-schema document for {@code write_artifact}.
     */
    static Document writeArtifact() {
        return objectSchema(
                Map.of(
                        "path", stringProperty(
                                "The target-repo-relative path of the design-doc artifact to write, "
                                        + "under the design/ directory — e.g. design/00-requirements.md, "
                                        + "design/01-design.md, or design/02-tasks.md. This is a design "
                                        + "document, not a source file."),
                        "content", stringProperty(
                                "The full markdown content of this phase's deliverable (the complete "
                                        + "requirements, design, or task-breakdown document).")),
                List.of("path", "content"));
    }

    /**
     * The {@code run_command} input schema: required {@code command} string
     * (04-apis § 3, ADR-0003).
     *
     * @return the input-schema document for {@code run_command}.
     */
    static Document runCommand() {
        return objectSchema(
                Map.of("command", stringProperty("The command line to run in the workspace.")),
                List.of("command"));
    }

    /**
     * The {@code grep} input schema: required {@code pattern} (a regular expression),
     * optional {@code path} (a workspace-relative directory or file to scope the search;
     * defaults to the whole workspace), optional {@code glob} (a path-glob filter for the
     * files searched), and optional {@code ignoreCase} flag (04-apis § 3).
     *
     * @return the input-schema document for {@code grep}.
     */
    static Document grep() {
        return objectSchema(
                Map.of(
                        "pattern", stringProperty("A regular expression to match against each file line."),
                        "path", stringProperty(
                                "Optional workspace-relative file or directory to search (default: the whole workspace)."),
                        "glob", stringProperty(
                                "Optional glob filtering which files are searched, e.g. '*.java'."),
                        "ignoreCase", booleanProperty("Optional case-insensitive matching (default false).")),
                List.of("pattern"));
    }

    /**
     * The {@code glob} input schema: required {@code pattern} (a path glob) and optional
     * {@code path} (a workspace-relative directory to root the walk; defaults to the whole
     * workspace) (04-apis § 3).
     *
     * @return the input-schema document for {@code glob}.
     */
    static Document glob() {
        return objectSchema(
                Map.of(
                        "pattern", stringProperty("A path glob to match, e.g. '**/*.java' or 'src/*.txt'."),
                        "path", stringProperty(
                                "Optional workspace-relative directory to search under (default: the whole workspace).")),
                List.of("pattern"));
    }

    /**
     * The {@code list} input schema: required {@code path} (a workspace-relative directory
     * whose entries are listed) (04-apis § 3).
     *
     * @return the input-schema document for {@code list}.
     */
    static Document list() {
        return objectSchema(
                Map.of("path", stringProperty("Workspace-relative directory whose entries to list.")),
                List.of("path"));
    }

    /**
     * The {@code edit_file} input schema: required {@code path}, {@code old} (the exact
     * existing text to replace), and {@code new} (the replacement) (04-apis § 3).
     *
     * @return the input-schema document for {@code edit_file}.
     */
    static Document editFile() {
        return objectSchema(
                Map.of(
                        "path", stringProperty("Workspace-relative path of the file to edit."),
                        "old", stringProperty(
                                "The exact existing text to replace; must occur exactly once in the file."),
                        "new", stringProperty("The replacement text.")),
                List.of("path", "old", "new"));
    }

    /**
     * The {@code spawn_subagent} input schema: required {@code prompt} (the scoped task the
     * child performs), optional {@code model} (a cheaper/different model the child runs;
     * defaults to the parent's), and optional {@code budgetSeconds} (the child's wall-clock
     * cap; defaults to NFR-SUBAGENT-BUDGET) (ADR-0010, AC-17.1/AC-17.2/AC-17.6).
     *
     * @return the input-schema document for {@code spawn_subagent}.
     */
    static Document spawnSubagent() {
        return objectSchema(
                Map.of(
                        "prompt", stringProperty(
                                "The scoped, self-contained task for the sub-agent to perform and summarize."),
                        "model", stringProperty(
                                "Optional model id the sub-agent should run (default: the parent's model)."),
                        "budgetSeconds", integerProperty(
                                "Optional wall-clock budget in seconds before the sub-agent is stopped "
                                        + "(default: 600).")),
                List.of("prompt"));
    }

    private static Document objectSchema(Map<String, Document> properties, List<String> required) {
        return Document.mapBuilder()
                .putString("type", TYPE_OBJECT)
                .putDocument("properties", Document.fromMap(properties))
                .putDocument("required", Document.fromList(
                        required.stream().map(Document::fromString).toList()))
                .build();
    }

    private static Document stringProperty(String description) {
        return Document.mapBuilder()
                .putString("type", TYPE_STRING)
                .putString("description", description)
                .build();
    }

    private static Document integerProperty(String description) {
        return Document.mapBuilder()
                .putString("type", TYPE_INTEGER)
                .putString("description", description)
                .build();
    }

    private static Document booleanProperty(String description) {
        return Document.mapBuilder()
                .putString("type", TYPE_BOOLEAN)
                .putString("description", description)
                .build();
    }
}
