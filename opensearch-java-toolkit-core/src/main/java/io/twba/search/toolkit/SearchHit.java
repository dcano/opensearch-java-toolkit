package io.twba.search.toolkit;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One document returned by a search, with the per-hit metadata the engine produced for it.
 *
 * <p>Search-engine agnostic on purpose: an application never sees an OpenSearch type.
 * {@code highlights} maps a field name to its highlighted fragments, already wrapped in whatever
 * markers the adapter asked for (e.g. {@code <mark>…</mark>}).
 */
public record SearchHit<T>(
        String documentId,
        Double score,
        T document,
        Map<String, List<String>> highlights,
        Set<MatchKind> matchedOn
) {

    public SearchHit {
        highlights = highlights == null ? Map.of() : Map.copyOf(highlights);
        matchedOn = matchedOn == null ? Set.of() : Set.copyOf(matchedOn);
    }

    /** A hit whose provenance was not recorded — the adapter names no queries for this search. */
    public SearchHit(String documentId, Double score, T document, Map<String, List<String>> highlights) {
        this(documentId, score, document, highlights, Set.of());
    }

    public static <T> SearchHit<T> of(String documentId, Double score, T document) {
        return new SearchHit<>(documentId, score, document, Map.of());
    }

    /** True if the document's free text matched. */
    public boolean matchedContent() {
        return matchedOn.contains(MatchKind.CONTENT);
    }

    /**
     * True if a sensitive identifier matched. Worth badging in a UI: such a hit may carry no
     * highlight at all, and for a {@code HIGH} tenant it never does.
     */
    public boolean matchedIdentifier() {
        return matchedOn.contains(MatchKind.IDENTIFIER);
    }

    /** Highlighted fragments for {@code field}, or an empty list when the field did not match. */
    public List<String> highlightsFor(String field) {
        return highlights.getOrDefault(field, List.of());
    }

    /** The first fragment for {@code field}, or {@code null} when there is none — handy for UI snippets. */
    public String firstHighlight(String field) {
        List<String> fragments = highlightsFor(field);
        return fragments.isEmpty() ? null : fragments.getFirst();
    }

    public boolean hasHighlights() {
        return !highlights.isEmpty();
    }
}
