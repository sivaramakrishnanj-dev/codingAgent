package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.OperationClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link WriteArtifactTool}: the design-doc write path the model uses in the greenfield
 * pre-approval phases to persist the requirements/design/tasks markdown into the target repo
 * (RD-7, AC-1.2, AC-2.1), confined so it cannot write source (AC-1.4).
 *
 * <p><b>Oracles trace to the spec:</b>
 * <ul>
 *   <li><b>AC-1.2/AC-2.1:</b> a design-markdown write through the tool persists the artifact into
 *       the target repo's design-doc directory.</li>
 *   <li><b>AC-1.4:</b> a write targeting a source file (outside design/) is refused as a tool
 *       error, so the design-doc write path cannot reach source.</li>
 * </ul>
 */
class WriteArtifactToolTest {

    // --- AC-1.2/AC-2.1 : a design-markdown write persists the artifact into the target repo -------

    @Test
    @DisplayName("AC-1.2/AC-2.1: a design-markdown write through the tool persists the artifact in the target repo")
    void designMarkdownWriteSucceeds(@TempDir Path targetRepo) throws Exception {
        // Oracle: AC-1.2/AC-2.1 — the agent persists the requirements/design/tasks markdown in the
        // target project. Calling the tool with a design/ path and content must write that artifact.
        // Assert the file exists with the content under the target repo (not store internals).
        WriteArtifactTool tool = new WriteArtifactTool(new GreenfieldArtifactStore(targetRepo));

        Object result = tool.handle(Map.of(
                "path", "design/00-requirements.md", "content", "# Requirements\n\nUS-1: ...\n"));

        Path artifact = targetRepo.resolve("design").resolve("00-requirements.md");
        assertTrue(Files.exists(artifact), "AC-1.2: the design-markdown artifact is written");
        assertEquals("# Requirements\n\nUS-1: ...\n",
                Files.readString(artifact, StandardCharsets.UTF_8), "the artifact carries the content");
        assertTrue(result.toString().startsWith("ok:"), "the tool returns an ok summary; was: " + result);
    }

    // --- AC-1.4 : the design-doc write path cannot write a source file ----------------------------

    @Test
    @DisplayName("AC-1.4: a source-file write through the design-doc tool is refused (a source write does NOT succeed)")
    void sourceWriteIsRefused(@TempDir Path targetRepo) {
        // Oracle: AC-1.4 — no Class X operation against source files in the pre-approval dialogue. The
        // task asks to pin that a design-markdown write SUCCEEDS while a general source write does NOT.
        // The design-doc tool, when handed a source path (outside design/), must refuse with a tool
        // error rather than write the source file.
        WriteArtifactTool tool = new WriteArtifactTool(new GreenfieldArtifactStore(targetRepo));

        ToolInvocationException refused = assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", "src/App.java", "content", "class App {}")));
        assertTrue(refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("design")
                        || refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("artifact"),
                "AC-1.4: the refusal explains the source path is not a design-doc artifact; was: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("CT-GF-4 (AC-1.4/DCR-6): a write_artifact for a source path UNDER design/ (design/impl/pom.xml) is REFUSED")
    void sourcePathUnderDesignImplIsRefused(@TempDir Path targetRepo) {
        // Oracle: CT-GF-4 — the tightened store/TOOL rejects any path under design/ that is not one of
        // the three known artifacts: design/impl/pom.xml is REJECTED. AC-1.4 forbids a Class-X write
        // against a source/build file in the pre-approval dialogue; the tool delegates confinement to
        // the store, so the model's write_artifact for design/impl/pom.xml must surface as a tool error
        // rather than write the file. Expected outcome (REFUSED) traces to CT-GF-4/AC-1.4, not the impl.
        WriteArtifactTool tool = new WriteArtifactTool(new GreenfieldArtifactStore(targetRepo));

        ToolInvocationException refused = assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", "design/impl/pom.xml", "content", "<project/>")),
                "CT-GF-4: a source/build file under design/impl is not a design-doc artifact");
        assertTrue(refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("design")
                        || refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("artifact"),
                "CT-GF-4: the refusal explains the path is not a design-doc artifact; was: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("the tool reports its identity and operation class (Class X)")
    void toolIdentityAndClass(@TempDir Path targetRepo) {
        // Oracle: 04-apis § 3 / AC-5.2 — a write is Class X (side-effecting), gated by the permission
        // mode. The tool name is the stable wire name the model and registry use.
        WriteArtifactTool tool = new WriteArtifactTool(new GreenfieldArtifactStore(targetRepo));
        assertEquals("write_artifact", tool.name(), "the wire tool name");
        assertEquals(WriteArtifactTool.NAME, tool.name(), "the public name constant matches");
        assertEquals(OperationClass.SIDE_EFFECTING, tool.operationClass(),
                "AC-5.2: a write is a Class-X side-effecting operation");
    }

    @Test
    @DisplayName("a missing path or content is a tool error, not a crash")
    void missingInputsAreToolErrors(@TempDir Path targetRepo) {
        WriteArtifactTool tool = new WriteArtifactTool(new GreenfieldArtifactStore(targetRepo));
        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of("content", "x")));
        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", "design/x.md")));
        assertThrows(NullPointerException.class, () -> new WriteArtifactTool(null));
    }

    @Test
    @DisplayName("a non-string content is a tool error, not a crash (04-apis § 3)")
    void nonStringContentIsToolError(@TempDir Path targetRepo) {
        // Oracle: 04-apis § 3 Notes — invalid tool input is surfaced as a tool error the model can
        // react to, not a ClassCastException that crashes the loop.
        WriteArtifactTool tool = new WriteArtifactTool(new GreenfieldArtifactStore(targetRepo));
        assertThrows(ToolInvocationException.class,
                () -> tool.handle(Map.of("path", "design/x.md", "content", 42)));
    }

    @Test
    @DisplayName("the tool renders a description and input schema for the toolSpec (ADR-0001)")
    void rendersToolSpec(@TempDir Path targetRepo) {
        // Oracle: ADR-0001 — a tool declares its description and JSON-Schema inputSchema so the
        // registry renders it to a Converse toolSpec the model sees. The registry rendering exercises
        // these; assert they are present and non-empty.
        WriteArtifactTool tool = new WriteArtifactTool(new GreenfieldArtifactStore(targetRepo));
        assertTrue(tool.description() != null && !tool.description().isBlank(),
                "the tool declares a description for the toolSpec");
        org.junit.jupiter.api.Assertions.assertNotNull(tool.inputSchema(),
                "the tool declares an input schema for the toolSpec");
        ToolRegistry.of(java.util.List.of(tool)).toToolConfiguration();
    }

    @Test
    @DisplayName("RD-7/AC-1.2/AC-2.1 (T-3.2-RD-D9): the inputSchema describes a design/ design-doc artifact, not a generic source file")
    void inputSchemaDescribesADesignDocArtifactNotASourceFile(@TempDir Path targetRepo) {
        // Oracle: RD-7 — greenfield persists requirements/design/tasks "as markdown in the target
        // project"; AC-1.2/AC-2.1 — those deliverables are markdown ARTIFACTS; C7 — a tool's schema and
        // its handler agree. The schema the model fills its arguments from must describe the design-doc
        // artifact this tool writes (a design/ path), NOT a generic source-file write — the D9 root
        // cause was the schema reusing the generic write_file field descriptions ("...path of the file
        // to write" / "The full new contents of the file"), which read as a source-file writer and,
        // against the greenfield no-source-write prompt, steered the live model away from calling the
        // tool, so the design-doc content never persisted. The expected tokens (design/, the
        // ARTIFACT_DIR) trace to GreenfieldArtifactStore.ARTIFACT_DIR + AC-1.2/RD-7 ("markdown
        // artifact"), not to the schema's prose. The schema is rendered into the toolSpec the model
        // sees, so assert against that rendered JSON.
        WriteArtifactTool tool = new WriteArtifactTool(new GreenfieldArtifactStore(targetRepo));
        String schemaJson = software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema
                .fromJson(tool.inputSchema()).json().toString().toLowerCase(java.util.Locale.ROOT);

        assertTrue(schemaJson.contains(GreenfieldArtifactStore.ARTIFACT_DIR + "/"),
                "RD-7/AC-1.2: the schema steers the model to a " + GreenfieldArtifactStore.ARTIFACT_DIR
                        + "/ artifact path; was: " + schemaJson);
        assertTrue(schemaJson.contains("design document") || schemaJson.contains("design-doc")
                        || schemaJson.contains("artifact"),
                "RD-7 (C7): the schema describes a design document/artifact, not a generic source-file "
                        + "write; was: " + schemaJson);
        assertTrue(schemaJson.contains("\"path\"") && schemaJson.contains("\"content\""),
                "C7: the schema still requires the path + content fields the handler reads; was: "
                        + schemaJson);
    }
}
