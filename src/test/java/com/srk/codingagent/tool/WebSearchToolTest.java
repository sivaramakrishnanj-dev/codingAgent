package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.persistence.OperationClass;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code web_search} tool (C11, 04-apis § 3, ADR-0008). The SUT is a real
 * {@link WebSearchTool}; the only collaborator is a controllable {@link WebLookupBackend} stub
 * standing in for the constrained-headless-Claude delegate (ADR-0008's swappable backend seam), so no
 * test depends on a real {@code claude} binary or a live network call.
 *
 * <p><b>Oracles.</b> Expected values trace to the spec, never to the tool's incidental behavior:
 * <ul>
 *   <li><b>04-apis § 3 / RD-6 / AC-11.2:</b> {@code web_search} is Class X
 *       ({@link OperationClass#SIDE_EFFECTING}) so the gate denies it in {@code READ_ONLY}.</li>
 *   <li><b>AC-11.1:</b> a lookup returns the delegate's summarized result.</li>
 *   <li><b>AC-11.3:</b> a backend failure result is surfaced to the model as text (report, not
 *       fabricate), not collapsed into a crash.</li>
 *   <li><b>04-apis § 3 Notes:</b> a missing/blank {@code query} is a tool error, not a crash.</li>
 * </ul>
 */
class WebSearchToolTest {

    /** A backend stub returning a fixed result and capturing the request it received. */
    private static final class StubBackend implements WebLookupBackend {
        private final WebLookupResult answer;
        private final AtomicReference<WebLookupRequest> seen = new AtomicReference<>();

        StubBackend(WebLookupResult answer) {
            this.answer = answer;
        }

        @Override
        public WebLookupResult lookup(WebLookupRequest request) {
            seen.set(request);
            return answer;
        }
    }

    @Test
    @DisplayName("04-apis § 3 / RD-6: web_search is Class X (SIDE_EFFECTING) so READ_ONLY denies it")
    void webSearchIsClassX() {
        // Oracle: 04-apis § 3 (web_search Class = X) + RD-6/AC-11.2 (Class X → denied in READ_ONLY).
        // The class is what the gate keys on; a wrong class would let a web lookup run read-only.
        WebSearchTool tool = new WebSearchTool(
                new StubBackend(WebLookupResult.success("summary")));

        assertEquals(OperationClass.SIDE_EFFECTING, tool.operationClass(),
                "web_search is Class X (04-apis § 3, RD-6)");
        assertEquals("web_search", tool.name(), "the tool name is web_search");
    }

    @Test
    @DisplayName("AC-11.1: a successful lookup returns the delegate's summarized result as text")
    void returnsSummarizedResult() {
        // Oracle: AC-11.1 — "invoke a web-lookup tool that returns a summarized result". The tool
        // returns the backend's summary; the expected text traces to the stubbed delegate summary +
        // the AC, not to the tool's internals. A plain String routes to the Converse text member.
        WebSearchTool tool = new WebSearchTool(
                new StubBackend(WebLookupResult.success("Mars has two moons: Phobos and Deimos.")));

        Object result = tool.handle(Map.of("query", "how many moons does Mars have"));

        assertInstanceOf(String.class, result,
                "AC-11.1: the summarized result is returned as plain text (Converse text member)");
        assertEquals("Mars has two moons: Phobos and Deimos.", result,
                "AC-11.1: the tool returns the delegate's summarized result");
    }

    @Test
    @DisplayName("AC-11.1: the query input is forwarded to the backend as a SEARCH request")
    void forwardsQueryAsSearchRequest() {
        // Oracle: 04-apis § 3 — web_search(query). The model-supplied query must reach the backend as
        // a SEARCH lookup for exactly that query, so a swapped backend receives the same contract.
        StubBackend backend = new StubBackend(WebLookupResult.success("ok"));
        WebSearchTool tool = new WebSearchTool(backend);

        tool.handle(Map.of("query", "latest Java LTS release"));

        assertEquals(WebLookupRequest.Kind.SEARCH, backend.seen.get().kind(),
                "04-apis § 3: web_search forwards a SEARCH request");
        assertEquals("latest Java LTS release", backend.seen.get().argument(),
                "04-apis § 3: the model's query reaches the backend verbatim");
    }

    @Test
    @DisplayName("AC-11.3: a backend failure result is surfaced as text (report, not fabricate)")
    void surfacesFailureReport() {
        // Oracle: AC-11.3 — "if the delegate is unavailable or fails, report the failure rather than
        // fabricating an answer". The tool must pass the backend's failure report through as the
        // model-facing text, not swallow it or invent a result. Expected text traces to the failure
        // report the backend produced + the AC.
        WebSearchTool tool = new WebSearchTool(new StubBackend(
                WebLookupResult.failure("web lookup unavailable: the headless claude CLI was not found on PATH")));

        Object result = tool.handle(Map.of("query", "anything"));

        assertInstanceOf(String.class, result, "AC-11.3: the failure is reported as text, not a crash");
        assertTrue(((String) result).contains("unavailable"),
                "AC-11.3: the tool reports the delegate failure rather than fabricating an answer");
    }

    @Test
    @DisplayName("04-apis § 3: a missing query is a tool error (ToolInvocationException), not a crash")
    void missingQueryIsToolError() {
        // Oracle: 04-apis § 3 Notes — invalid input surfaces as a tool error the registry turns into
        // an error tool result, not an exception that crashes the loop.
        WebSearchTool tool = new WebSearchTool(
                new StubBackend(WebLookupResult.success("ok")));

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of()),
                "a missing required 'query' is a tool error");
    }

    @Test
    @DisplayName("04-apis § 3: the input schema requires query")
    void inputSchemaRequiresQuery() {
        // Oracle: 04-apis § 3 — web_search input is { query }. Assert the rendered schema marks query
        // required (the registry renders this into the toolSpec the model sees).
        WebSearchTool tool = new WebSearchTool(
                new StubBackend(WebLookupResult.success("ok")));

        var required = tool.inputSchema().asMap().get("required").asList();
        assertTrue(required.stream().anyMatch(d -> "query".equals(d.asString())),
                "04-apis § 3: the input schema marks 'query' as required");
    }
}
