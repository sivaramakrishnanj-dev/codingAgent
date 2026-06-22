package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.OperationClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;

/**
 * Registry-level contract for the T-1.3 search/edit tools (C9, 04-apis § 3): grep, glob,
 * list, and edit_file coexist in a {@link ToolRegistry} alongside the existing read/write
 * tools, each renders to a Converse toolSpec the model sees, each reports the operation
 * class the gate keys on (AC-4.4 grep/glob/list = READ; AC-5.2 edit_file = SIDE_EFFECTING),
 * and a dispatched {@code toolUse} reaches the handler. This pins the wiring contract the
 * (JaCoCo-excluded) {@code AgentLoopFactory} depends on: the four names are unique and the
 * schemas render, so a live {@code codingagent} run exposes all four tools.
 *
 * <p>The registry is the SUT; the handlers are the real tools over a {@link TempDir}.
 */
class SearchToolsRegistrationTest {

    private static ToolRegistry registry(Path workspace) {
        return ToolRegistry.of(List.of(
                new ReadFileTool(workspace),
                new GrepTool(workspace),
                new GlobTool(workspace),
                new ListTool(workspace),
                new WriteFileTool(workspace),
                new EditFileTool(workspace)));
    }

    @Test
    @DisplayName("04-apis § 3: the four tools register under unique names and render toolSpecs")
    void fourToolsRegisterAndRender(@TempDir Path workspace) {
        // Oracle: 04-apis § 3 — grep/glob/list/edit_file are distinct tools; ADR-0001 — each
        // renders a toolSpec. The registry rejects duplicate names, so a clean build proves
        // the four names are unique alongside read_file/write_file.
        ToolRegistry registry = registry(workspace);

        assertTrue(registry.toolNames().containsAll(
                        List.of("read_file", "grep", "glob", "list", "write_file", "edit_file")),
                "all six tools register: " + registry.toolNames());
        ToolConfiguration config = registry.toToolConfiguration();
        assertEquals(6, config.tools().size(), "every registered tool renders a toolSpec (ADR-0001)");
        config.tools().forEach(tool ->
                assertNotNull(tool.toolSpec().inputSchema().json(), "each toolSpec carries a JSON inputSchema"));
    }

    @Test
    @DisplayName("AC-4.4 / AC-5.2: the registry reports each tool's gate class")
    void registryReportsOperationClasses(@TempDir Path workspace) {
        // Oracle: AC-4.4 — grep/glob/list are Class R (non-gated); AC-5.2 — edit_file is
        // Class X (gated). The registry is the single source the loop's gateRequestFor reads,
        // so a Class-R tool auto-approves and edit_file is gated for free by these classes.
        ToolRegistry registry = registry(workspace);

        assertEquals(java.util.Optional.of(OperationClass.READ), registry.operationClass("grep"));
        assertEquals(java.util.Optional.of(OperationClass.READ), registry.operationClass("glob"));
        assertEquals(java.util.Optional.of(OperationClass.READ), registry.operationClass("list"));
        assertEquals(java.util.Optional.of(OperationClass.SIDE_EFFECTING),
                registry.operationClass("edit_file"));
    }

    @Test
    @DisplayName("04-apis § 3: a dispatched grep toolUse reaches the handler with an ok result")
    void dispatchReachesSearchHandler(@TempDir Path workspace) throws IOException {
        // Oracle: 04-apis § 3 / ADR-0001 — the model's toolUse dispatches to the matching
        // handler; a normal return is an ok tool result carrying the handler's content.
        Files.writeString(workspace.resolve("a.txt"), "needle");
        ContentBlock.ToolResult result = registry(workspace).dispatch(
                ContentBlock.toolUse("tu_g", "grep", Map.of("pattern", "needle")));

        assertEquals("ok", result.status(), "a successful grep is an ok tool result");
        assertEquals("a.txt:1:needle", result.content(),
                "the dispatched result carries the grep match-row contract (04-apis § 3)");
    }
}
