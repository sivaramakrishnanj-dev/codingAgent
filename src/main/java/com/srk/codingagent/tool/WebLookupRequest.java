package com.srk.codingagent.tool;

import java.util.Objects;

/**
 * A constrained web-lookup task the {@link WebLookupBackend} performs (C11, ADR-0008): either a
 * {@link Kind#SEARCH search} for a query or a {@link Kind#FETCH fetch} of a URL. It carries just the
 * model-supplied argument and the kind; the backend turns it into the constrained delegate prompt
 * (e.g. {@code claude -p "<task>" --output-format text}) and hands back a {@link WebLookupResult}.
 *
 * <p>The kind/argument split keeps the tool surface (the {@code web_search}/{@code web_fetch} tools,
 * 04-apis § 3) decoupled from the backend: the same backend serves both tools, and swapping the
 * backend later (a direct search API, ADR-0008 Consequences) touches one class, not this request
 * type or the tools.
 *
 * @param kind     whether this is a search-by-query or a fetch-by-url; must not be {@code null}.
 * @param argument the search query (for {@link Kind#SEARCH}) or the URL to fetch (for
 *                 {@link Kind#FETCH}); non-blank.
 */
public record WebLookupRequest(Kind kind, String argument) {

    /** The two web-lookup operations the backend serves (04-apis § 3: {@code web_search}/{@code web_fetch}). */
    public enum Kind {

        /** A web search for a free-text query, returning a summarized result. */
        SEARCH,

        /** A fetch of a specific URL, returning a summarized result. */
        FETCH
    }

    /**
     * Validates the request.
     *
     * @throws NullPointerException     if {@code kind} is {@code null}.
     * @throws IllegalArgumentException if {@code argument} is blank.
     */
    public WebLookupRequest {
        Objects.requireNonNull(kind, "kind");
        if (argument == null || argument.isBlank()) {
            throw new IllegalArgumentException("argument must be non-blank");
        }
    }

    /**
     * A search request for the given query.
     *
     * @param query the free-text query to look up; non-blank.
     * @return a {@link Kind#SEARCH} request.
     */
    public static WebLookupRequest search(String query) {
        return new WebLookupRequest(Kind.SEARCH, query);
    }

    /**
     * A fetch request for the given URL.
     *
     * @param url the URL to fetch and summarize; non-blank.
     * @return a {@link Kind#FETCH} request.
     */
    public static WebLookupRequest fetch(String url) {
        return new WebLookupRequest(Kind.FETCH, url);
    }
}
