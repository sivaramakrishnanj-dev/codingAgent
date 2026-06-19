package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.OperationClass;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

/**
 * Tests for the Tool Registry (C7, ADR-0001): rendering registered tools to a Converse
 * {@link ToolConfiguration} (toolSpec name/description/inputSchema), dispatching a model
 * {@link ContentBlock.ToolUse} to the matching handler, and the unknown-tool / handler-
 * failure structured-error paths (04-apis § 3 Notes).
 *
 * <p>The registry is the SUT and runs real handlers; the handler used here is a tiny
 * fake collaborator (a stand-in for a real tool) so the registry's dispatch and rendering
 * are exercised against real behavior, not mocks of the SUT.
 */
class ToolRegistryTest {

    /** A minimal real handler, used as a controllable collaborator (not a mock of the SUT). */
    private static final class FakeTool implements ToolHandler {
        private final String name;
        private final OperationClass operationClass;
        private final java.util.function.Function<Map<String, Object>, Object> body;

        FakeTool(String name, OperationClass operationClass,
                 java.util.function.Function<Map<String, Object>, Object> body) {
            this.name = name;
            this.operationClass = operationClass;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + " description";
        }

        @Override
        public software.amazon.awssdk.core.document.Document inputSchema() {
            return ToolSchemas.runCommand();
        }

        @Override
        public OperationClass operationClass() {
            return operationClass;
        }

        @Override
        public Object handle(Map<String, Object> input) {
            return body.apply(input);
        }
    }

    @Test
    @DisplayName("ADR-0001: each registered tool renders to a toolSpec {name, description, inputSchema}")
    void rendersToolSpecsForEachTool() {
        // Oracle: ADR-0001 — each tool is {name, description, inputSchema} rendered to a
        // Converse toolSpec. A two-tool registry renders two toolSpecs with the names and
        // descriptions the handlers declare.
        ToolRegistry registry = ToolRegistry.of(List.of(
                new FakeTool("alpha", OperationClass.READ, in -> "a"),
                new FakeTool("beta", OperationClass.SIDE_EFFECTING, in -> "b")));

        ToolConfiguration config = registry.toToolConfiguration();

        assertEquals(2, config.tools().size(), "every registered tool renders a toolSpec");
        ToolSpecification first = config.tools().get(0).toolSpec();
        assertEquals("alpha", first.name(), "the toolSpec name is the handler name (ADR-0001)");
        assertEquals("alpha description", first.description(),
                "the toolSpec description is the handler description (ADR-0001)");
        assertNotNull(first.inputSchema(), "the toolSpec carries the handler's inputSchema (ADR-0001)");
        assertNotNull(first.inputSchema().json(), "inputSchema is rendered as a JSON document");
    }

    @Test
    @DisplayName("ADR-0001: dispatch routes a toolUse to the matching handler and returns an ok toolResult")
    void dispatchRoutesToMatchingHandler() {
        // Oracle: ADR-0001 — the model's toolUse.input dispatches to the handler. The
        // result is an ok-status ContentBlock.ToolResult correlated by toolUseId carrying
        // the handler's return value.
        ToolRegistry registry = ToolRegistry.of(List.of(
                new FakeTool("echo", OperationClass.READ, in -> "got:" + in.get("command"))));
        ContentBlock.ToolUse toolUse =
                ContentBlock.toolUse("tu_1", "echo", Map.of("command", "hi"));

        ContentBlock.ToolResult result = registry.dispatch(toolUse);

        assertEquals("tu_1", result.toolUseId(), "the result correlates to the toolUseId");
        assertEquals("ok", result.status(), "a normal return is an ok tool result");
        assertEquals("got:hi", result.content(), "the result content is the handler's return value");
    }

    @Test
    @DisplayName("C7 invariant: an unknown tool produces a structured error, not a crash (ADR-0001)")
    void unknownToolProducesStructuredError() {
        // Oracle: C7 component invariant — unknown tool -> structured error. The dispatch
        // returns an error-status tool result rather than throwing.
        ToolRegistry registry = ToolRegistry.of(List.of(
                new FakeTool("known", OperationClass.READ, in -> "ok")));
        ContentBlock.ToolUse toolUse =
                ContentBlock.toolUse("tu_2", "unregistered", Map.of());

        ContentBlock.ToolResult result = registry.dispatch(toolUse);

        assertEquals("error", result.status(), "an unknown tool yields an error tool result (C7)");
        assertEquals("tu_2", result.toolUseId(), "the error still correlates to the toolUseId");
        assertTrue(String.valueOf(result.content()).contains("unregistered"),
                "the error names the unknown tool");
    }

    @Test
    @DisplayName("04-apis § 3: a handler failure becomes an error toolResult so the model reacts")
    void handlerFailureBecomesErrorResult() {
        // Oracle: 04-apis § 3 Notes — tool errors return as toolResult {status: error} so
        // the model can react, not crash. A handler that throws ToolInvocationException is
        // turned into an error result carrying its message.
        ToolRegistry registry = ToolRegistry.of(List.of(
                new FakeTool("boom", OperationClass.SIDE_EFFECTING, in -> {
                    throw new ToolInvocationException("nope");
                })));
        ContentBlock.ToolUse toolUse = ContentBlock.toolUse("tu_3", "boom", Map.of());

        ContentBlock.ToolResult result = registry.dispatch(toolUse);

        assertEquals("error", result.status(), "a handler failure is an error tool result");
        assertEquals("nope", result.content(), "the error result carries the handler's message");
    }

    @Test
    @DisplayName("ADR-0001: an empty registry renders a tool configuration with no tools")
    void emptyRegistryRendersNoTools() {
        ToolRegistry registry = ToolRegistry.of(List.of());

        assertEquals(0, registry.toToolConfiguration().tools().size(),
                "an empty registry renders zero toolSpecs");
        assertTrue(registry.toolNames().isEmpty(), "an empty registry reports no tool names");
    }

    @Test
    @DisplayName("C7: registration order is preserved and duplicate names are rejected")
    void preservesOrderAndRejectsDuplicates() {
        // Oracle: C7 — the registry keys by name; a duplicate name would make dispatch
        // ambiguous, so it is rejected. Registration order is preserved for deterministic
        // toolConfig rendering.
        ToolRegistry registry = ToolRegistry.of(List.of(
                new FakeTool("first", OperationClass.READ, in -> 1),
                new FakeTool("second", OperationClass.READ, in -> 2)));

        assertEquals(List.of("first", "second"), registry.toolNames(),
                "tool names are listed in registration order");
        assertThrows(IllegalArgumentException.class, () -> ToolRegistry.of(List.of(
                new FakeTool("dup", OperationClass.READ, in -> 1),
                new FakeTool("dup", OperationClass.READ, in -> 2))));
    }

    @Test
    @DisplayName("Tool.fromToolSpec is the rendering target ConverseWireMapper consumes (reuse, T-0.5)")
    void renderedConfigIsTheConverseToolConfiguration() {
        // Oracle: dependency contract — ConverseWireMapper.toRequest takes the SDK
        // ToolConfiguration; the registry must render exactly that type so the model client
        // can send the tools. A non-null SDK ToolConfiguration with a toolSpec confirms it.
        ToolRegistry registry = ToolRegistry.of(List.of(
                new FakeTool("read_file", OperationClass.READ, in -> "x")));

        ToolConfiguration config = registry.toToolConfiguration();
        Tool tool = config.tools().get(0);

        assertNotNull(tool.toolSpec(), "the rendered Tool carries a toolSpec the mapper sends");
    }
}
