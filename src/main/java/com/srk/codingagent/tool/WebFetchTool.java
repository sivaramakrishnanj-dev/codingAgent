package com.srk.codingagent.tool;

import com.srk.codingagent.persistence.OperationClass;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.document.Document;

/**
 * The {@code web_fetch} tool (C11, 04-apis § 3, ADR-0008): fetches a specific URL and returns a
 * summarized result, so the agent can read current web content beyond its training cut-off (US-11).
 * It is Class X ({@link OperationClass#SIDE_EFFECTING}) — the fetch spawns an external subprocess with
 * cost/network — so the permission gate gates it under the active mode and DENIES it in
 * {@code READ_ONLY} (AC-11.2, RD-6). Gating is structural: the loop routes this call's
 * {@link OperationClass} through the gate exactly as for {@code run_command}/{@code spawn_subagent}.
 *
 * <p>Input: {@code url} (required, the URL to fetch). The handler delegates to the swappable
 * {@link WebLookupBackend} (v1 = a constrained headless Claude, ADR-0008) and returns the backend's
 * text as a plain {@link String} — the summarized result on success, or the failure report when the
 * delegate is unavailable/errors/times out (AC-11.3: report, do not fabricate). As a plain string the
 * result routes through the Converse {@code text} member, never {@code json} (D2-safe).
 *
 * <p><b>Logged as events (AC-11.4).</b> The invocation and its summarized result are recorded as the
 * loop's {@code TOOL_USE}/{@code TOOL_RESULT} events — the same auditable path every registered tool
 * uses; this tool adds no separate logging path of its own.
 */
public final class WebFetchTool implements ToolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebFetchTool.class);

    /** The tool name as it appears in the toolSpec and the model's {@code toolUse.name}. */
    public static final String NAME = "web_fetch";

    private final WebLookupBackend backend;

    /**
     * Creates the tool over the swappable web-lookup backend.
     *
     * @param backend the backend that performs the lookup (v1 = constrained headless Claude,
     *                ADR-0008); must not be {@code null}.
     * @throws NullPointerException if {@code backend} is {@code null}.
     */
    public WebFetchTool(WebLookupBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Fetch a specific URL from the web and return a summarized result. "
                + "Use this to read current web content beyond your training knowledge.";
    }

    @Override
    public Document inputSchema() {
        return ToolSchemas.webFetch();
    }

    @Override
    public OperationClass operationClass() {
        return OperationClass.SIDE_EFFECTING;
    }

    /**
     * Runs the web fetch for the {@code url} input and returns the summarized result.
     *
     * @param input the {@code toolUse.input}: requires {@code url}.
     * @return the summarized result text on success, or a failure report when the delegate is
     *         unavailable/errors/times out (AC-11.3); a plain {@code String} (D2-safe).
     * @throws ToolInvocationException if {@code url} is missing or blank.
     */
    @Override
    public Object handle(Map<String, Object> input) {
        Objects.requireNonNull(input, "input");
        String url = ToolInputs.requireString(input, "url");
        WebLookupResult result = backend.lookup(WebLookupRequest.fetch(url));
        LOGGER.info("web_fetch completed: success={}", result.success());
        return result.requireText();
    }
}
