package com.srk.codingagent.tool;

/**
 * The swappable backend behind the {@code web_search}/{@code web_fetch} tools (C11, ADR-0008): it
 * performs a constrained {@link WebLookupRequest} and returns a {@link WebLookupResult}. The tool
 * surface (the two Class-X tools in the registry, 04-apis § 3) is a fixed seam; this interface is the
 * extension point ADR-0008 names, so a future direct-search-API backend swaps in by implementing this
 * one interface without touching the tool contract.
 *
 * <p>v1's implementation ({@link ClaudeCliWebLookupBackend}) shells out to a constrained headless
 * Claude CLI ({@code claude -p "<task>" --output-format text}) via the Command Executor's subprocess
 * machinery (ADR-0003), sandboxed to a scratch CWD with a hard timeout (NFR-NET-WEBLOOKUP-TIMEOUT).
 *
 * <p><b>Failure contract (AC-11.3).</b> An implementation must NOT throw for an unavailable, errored,
 * or timed-out delegate; it returns {@link WebLookupResult#failure} so the agent reports the failure
 * rather than fabricating an answer. It may throw only for a programming error (e.g. a {@code null}
 * request).
 */
public interface WebLookupBackend {

    /**
     * Performs the web lookup and returns its result.
     *
     * @param request the constrained search/fetch task; must not be {@code null}.
     * @return the summarized result on success, or a failure report when the delegate is
     *         unavailable, errors, or times out (AC-11.3); never {@code null}.
     * @throws NullPointerException if {@code request} is {@code null}.
     */
    WebLookupResult lookup(WebLookupRequest request);
}
