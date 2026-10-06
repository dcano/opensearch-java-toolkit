package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.MatchKind;
import io.twba.search.toolkit.SearchHit;
import io.twba.search.toolkit.SearchResults;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.TotalHits;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Translates an OpenSearch {@link SearchResponse} into the toolkit's {@link SearchResults}.
 *
 * <p>This is the seam that keeps the OpenSearch client out of the core module: the executor returns
 * toolkit types, and this class is the only place that knows how a hit, its score, its highlight
 * fragments and its matched query names are shaped on the wire.
 *
 * <p>It also carries the document mapping, because for a {@code HIGH} tenant the type that came off
 * the wire is not the type the application's port returns — a map of sealed components in, the
 * application's document out, one set of envelopes opened per hit.
 */
final class SearchResponseMapper {

    private SearchResponseMapper() {
    }

    /** The documents come back as they were deserialized — the {@code NORMAL} path. */
    static <T> SearchResults<T> toSearchResults(SearchResponse<T> response) {
        return toSearchResults(response, Hit::source);
    }

    /**
     * @param documentMapper turns one wire document into the application's document. It is handed
     *                       the whole {@link Hit} rather than just the source because an envelope
     *                       that fails to open names the document id, and the id lives on the hit.
     */
    static <S, T> SearchResults<T> toSearchResults(SearchResponse<S> response,
                                                   Function<Hit<S>, T> documentMapper) {
        if (response == null || response.hits() == null) {
            return SearchResults.empty();
        }

        List<SearchHit<T>> hits = response.hits().hits().stream()
                .map(hit -> toSearchHit(hit, documentMapper))
                .toList();

        TotalHits total = response.hits().total();
        long totalHits = total != null ? total.value() : hits.size();

        return new SearchResults<>(
                hits,
                totalHits,
                toRelation(total),
                response.maxScore(),
                response.took());
    }

    private static <S, T> SearchHit<T> toSearchHit(Hit<S> hit, Function<Hit<S>, T> documentMapper) {
        return new SearchHit<>(
                hit.id(),
                hit.score(),
                documentMapper.apply(hit),
                toHighlights(hit.highlight()),
                toMatchKinds(hit.matchedQueries()));
    }

    /**
     * Provenance, recovered from the {@code _name} the query builders put on each branch.
     *
     * <p>Unknown names are ignored rather than rejected: a search that names no queries — most of
     * them — yields an empty set, which {@link SearchHit} reads as "provenance was not recorded",
     * not as "matched nothing".
     */
    private static Set<MatchKind> toMatchKinds(List<String> matchedQueries) {
        if (matchedQueries == null || matchedQueries.isEmpty()) {
            return Set.of();
        }
        Set<MatchKind> kinds = EnumSet.noneOf(MatchKind.class);
        if (matchedQueries.contains(BranchNames.CONTENT)) {
            kinds.add(MatchKind.CONTENT);
        }
        if (matchedQueries.contains(BranchNames.IDENTIFIER)) {
            kinds.add(MatchKind.IDENTIFIER);
        }
        return kinds;
    }

    private static Map<String, List<String>> toHighlights(Map<String, List<String>> highlight) {
        if (highlight == null || highlight.isEmpty()) {
            return Map.of();
        }
        return highlight.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    private static SearchResults.TotalHitsRelation toRelation(TotalHits total) {
        // track_total_hits caps the count: Gte means "at least this many", not an exact number.
        if (total != null && TotalHitsRelation.Gte.equals(total.relation())) {
            return SearchResults.TotalHitsRelation.LOWER_BOUND;
        }
        return SearchResults.TotalHitsRelation.EXACT;
    }
}
