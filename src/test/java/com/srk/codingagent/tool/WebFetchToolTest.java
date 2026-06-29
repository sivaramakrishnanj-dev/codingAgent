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
 * Tests for the {@code web_fetch} tool (C11, 04-apis § 3, ADR-0008). The SUT is a real
 * {@link WebFetchTool}; the only collaborator is a controllable {@link WebLookupBackend} stub
 * standing in for the constrained-headless-Claude delegate, so no test depends on a real
 * {@code claude} binary or a live network call.
 *
 * <p><b>Oracles.</b> Expected values trace to the spec, never to the tool's incidental behavior:
 * <ul>
 *   <li><b>04-apis § 3 / RD-6 / AC-11.2:</b> {@code web_fetch} is Class X
 *       ({@link OperationClass#SIDE_EFFECTING}).</li>
 *   <li><b>AC-11.1:</b> a lookup returns the delegate's summarized result.</li>
 *   <li><b>AC-11.3:</b> a backend failure result is surfaced as text (report, not fabricate).</li>
 *   <li><b>04-apis § 3 Notes:</b> a missing/blank {@code url} is a tool error, not a crash.</li>
 * </ul>
 */
class WebFetchToolTest {

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
    @DisplayName("04-apis § 3 / RD-6: web_fetch is Class X (SIDE_EFFECTING) so READ_ONLY denies it")
    void webFetchIsClassX() {
        // Oracle: 04-apis § 3 (web_fetch Class = X) + RD-6/AC-11.2 (Class X → denied in READ_ONLY).
        WebFetchTool tool = new WebFetchTool(
                new StubBackend(WebLookupResult.success("summary")));

        assertEquals(OperationClass.SIDE_EFFECTING, tool.operationClass(),
                "web_fetch is Class X (04-apis § 3, RD-6)");
        assertEquals("web_fetch", tool.name(), "the tool name is web_fetch");
    }

    @Test
    @DisplayName("AC-11.1: a successful lookup returns the delegate's summarized result as text")
    void returnsSummarizedResult() {
        // Oracle: AC-11.1 — "a web-lookup tool that returns a summarized result". Expected text
        // traces to the stubbed delegate summary + the AC, not the tool's internals.
        WebFetchTool tool = new WebFetchTool(
                new StubBackend(WebLookupResult.success("The page describes the OkHttp 5 release notes.")));

        Object result = tool.handle(Map.of("url", "https://example.com/okhttp"));

        assertInstanceOf(String.class, result,
                "AC-11.1: the summarized result is returned as plain text (Converse text member)");
        assertEquals("The page describes the OkHttp 5 release notes.", result,
                "AC-11.1: the tool returns the delegate's summarized result");
    }

    @Test
    @DisplayName("AC-11.1: the url input is forwarded to the backend as a FETCH request")
    void forwardsUrlAsFetchRequest() {
        // Oracle: 04-apis § 3 — web_fetch(url). The model-supplied url must reach the backend as a
        // FETCH lookup for exactly that url.
        StubBackend backend = new StubBackend(WebLookupResult.success("ok"));
        WebFetchTool tool = new WebFetchTool(backend);

        tool.handle(Map.of("url", "https://example.com/doc"));

        assertEquals(WebLookupRequest.Kind.FETCH, backend.seen.get().kind(),
                "04-apis § 3: web_fetch forwards a FETCH request");
        assertEquals("https://example.com/doc", backend.seen.get().argument(),
                "04-apis § 3: the model's url reaches the backend verbatim");
    }

    @Test
    @DisplayName("AC-11.3: a backend failure result is surfaced as text (report, not fabricate)")
    void surfacesFailureReport() {
        // Oracle: AC-11.3 — report the failure rather than fabricating an answer. The tool passes the
        // backend's failure report through as model-facing text. Expected text traces to the report
        // + the AC.
        WebFetchTool tool = new WebFetchTool(new StubBackend(
                WebLookupResult.failure("web lookup failed: the delegate timed out after 120s "
                        + "(NFR-NET-WEBLOOKUP-TIMEOUT)")));

        Object result = tool.handle(Map.of("url", "https://example.com"));

        assertInstanceOf(String.class, result, "AC-11.3: the failure is reported as text, not a crash");
        assertTrue(((String) result).contains("timed out"),
                "AC-11.3: the tool reports the delegate failure rather than fabricating an answer");
    }

    @Test
    @DisplayName("04-apis § 3: a missing url is a tool error (ToolInvocationException), not a crash")
    void missingUrlIsToolError() {
        // Oracle: 04-apis § 3 Notes — invalid input surfaces as a tool error, not a crash.
        WebFetchTool tool = new WebFetchTool(
                new StubBackend(WebLookupResult.success("ok")));

        assertThrows(ToolInvocationException.class, () -> tool.handle(Map.of()),
                "a missing required 'url' is a tool error");
    }

    @Test
    @DisplayName("04-apis § 3: the input schema requires url")
    void inputSchemaRequiresUrl() {
        // Oracle: 04-apis § 3 — web_fetch input is { url }. Assert the rendered schema marks url
        // required (the registry renders this into the toolSpec).
        WebFetchTool tool = new WebFetchTool(
                new StubBackend(WebLookupResult.success("ok")));

        var required = tool.inputSchema().asMap().get("required").asList();
        assertTrue(required.stream().anyMatch(d -> "url".equals(d.asString())),
                "04-apis § 3: the input schema marks 'url' as required");
    }
}
