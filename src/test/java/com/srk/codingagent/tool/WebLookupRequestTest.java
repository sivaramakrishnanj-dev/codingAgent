package com.srk.codingagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WebLookupRequest} — the constrained search/fetch task the {@link WebLookupBackend}
 * serves (C11, ADR-0008, 04-apis § 3/§ 4).
 *
 * <p>Oracle: 04-apis § 3 — the two web tools are {@code web_search(query)} and {@code web_fetch(url)};
 * the request models exactly those two kinds with the model-supplied argument.
 */
class WebLookupRequestTest {

    @Test
    @DisplayName("04-apis § 3: a search request carries the query as a SEARCH kind")
    void searchRequestCarriesQuery() {
        // Oracle: 04-apis § 3 — web_search(query). The factory produces a SEARCH request for the query.
        WebLookupRequest request = WebLookupRequest.search("how many moons");

        assertEquals(WebLookupRequest.Kind.SEARCH, request.kind());
        assertEquals("how many moons", request.argument());
    }

    @Test
    @DisplayName("04-apis § 3: a fetch request carries the url as a FETCH kind")
    void fetchRequestCarriesUrl() {
        // Oracle: 04-apis § 3 — web_fetch(url). The factory produces a FETCH request for the url.
        WebLookupRequest request = WebLookupRequest.fetch("https://example.com");

        assertEquals(WebLookupRequest.Kind.FETCH, request.kind());
        assertEquals("https://example.com", request.argument());
    }

    @Test
    @DisplayName("a blank argument is rejected (an empty query/url is not a valid lookup)")
    void blankArgumentRejected() {
        // Oracle: the request must carry a non-blank argument — an empty query/url is not a lookup the
        // delegate can perform; surface it at construction rather than spawning an empty delegate.
        assertThrows(IllegalArgumentException.class, () -> WebLookupRequest.search("   "));
        assertThrows(IllegalArgumentException.class, () -> WebLookupRequest.fetch(""));
    }

    @Test
    @DisplayName("a null kind is rejected")
    void nullKindRejected() {
        assertThrows(NullPointerException.class, () -> new WebLookupRequest(null, "x"));
    }
}
