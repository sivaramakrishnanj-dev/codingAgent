package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.OperationClass;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code web_search} tool (C11, 04-apis § 3, ADR-0008): looks up current information from the web
 * for a query and returns a summarized result, so the agent is not limited to its training cut-off
 * (US-11). It is Class X ({@link OperationClass#SIDE_EFFECTING}) — the lookup spawns an external
 * subprocess with cost/network — so the permission gate gates it under the active mode and DENIES it
 * in {@code READ_ONLY} (AC-11.2, RD-6). Gating is structural: the loop routes this call's
 * {@link OperationClass} through the gate exactly as for {@code run_command}/{@code spawn_subagent}.
 *
 * <p>Input: {@code query} (required, the free-text search query). The handler delegates to the
 * swappable {@link WebLookupBackend} (v1 = a constrained headless Claude, ADR-0008) and returns the
 * backend's text as a plain {@link String} — the summarized result on success, or the failure report
 * when the delegate is unavailable/errors/times out (AC-11.3: report, do not fabricate). As a plain
 * string the result routes through the Converse {@code text} member, never {@code json} (D2-safe).
 *
 * <p><b>Logged as events (AC-11.4).</b> The invocation and its summarized result are recorded as the
 * loop's {@code TOOL_USE}/{@code TOOL_RESULT} events — the same auditable path every registered tool
 * uses; this tool adds no separate logging path of its own.
 */
public final class WebSearchTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSearchTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "web_search";

    private final WebLookupBackend backend;

    /**
     * Creates the tool over the swappable web-lookup backend.
     *
     * @param backend the backend that performs the lookup (v1 = constrained headless Claude,
     *                ADR-0008); must not be {@code null}.
     * @throws NullPointerException if {@code backend} is {@code null}.
     */
    public WebSearchTool(WebLookupBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search the web for current information about a query and return a summarized result. "
                + "Use this when your own knowledge is insufficient or may be out of date.";
    }

    @Override
    public Document inputSchema() {
        return ToolSchemas.webSearch();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.SIDE_EFFECTING;
    }

    /**
     * Runs the web search for the {@code query} input and returns the summarized result.
     *
     * @param input the {@code toolUse.input}: requires {@code query}.
     * @return the summarized result text on success, or a failure report when the delegate is
     *         unavailable/errors/times out (AC-11.3); a plain {@code String} (D2-safe).
     * @throws ToolInvocationException if {@code query} is missing or blank.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        String query = ToolInputs.requireString(input, "query");
        WebLookupResult result = backend.lookup(WebLookupRequest.search(query));
        LOGGER.info("web_search completed: success={}", result.success());
        return result.requireText();
    }
}
