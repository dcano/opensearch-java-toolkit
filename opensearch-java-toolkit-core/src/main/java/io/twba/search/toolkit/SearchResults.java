package io.twba.search.toolkit;

import java.util.List;

/**
 * A page of search results: the hits with their metadata, plus the result-set level metadata a UI
 * needs (total count, whether that count is exact, how long it took).
 *
 * <p>This is the port's return type, so an application stays free of any search-engine library:
 * adapters map their native response into it.
 */
public record SearchResults<T>(
        List<SearchHit<T>> hits,
        long totalHits,
        TotalHitsRelation totalHitsRelation,
        Double maxScore,
        long tookMillis
) {

    /**
     * Whether {@link #totalHits} is exact, or only a lower bound because the engine stopped
     * counting early (OpenSearch does this past {@code track_total_hits}).
     */
    public enum TotalHitsRelation { EXACT, LOWER_BOUND }

    public SearchResults {
        hits = hits == null ? List.of() : List.copyOf(hits);
        totalHitsRelation = totalHitsRelation == null ? TotalHitsRelation.EXACT : totalHitsRelation;
    }

    public static <T> SearchResults<T> empty() {
        return new SearchResults<>(List.of(), 0L, TotalHitsRelation.EXACT, null, 0L);
    }

    /** Just the documents, dropping the per-hit metadata. */
    public List<T> documents() {
        return hits.stream().map(SearchHit::document).toList();
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    public int size() {
        return hits.size();
    }

    public boolean totalIsExact() {
        return totalHitsRelation == TotalHitsRelation.EXACT;
    }
}
