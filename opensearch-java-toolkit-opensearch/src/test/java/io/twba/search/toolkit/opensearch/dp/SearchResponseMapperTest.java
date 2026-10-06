package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.MatchKind;
import io.twba.search.toolkit.SearchHit;
import io.twba.search.toolkit.SearchResults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire-to-toolkit translation, tested directly because the interesting cases are the ones a
 * cluster produces rarely and a test fixture never produces by accident: a capped total, a hit with
 * no matched query names, a highlight map whose entries are empty.
 *
 * <p>Each of these has a wrong answer that looks right. A capped total reported as exact tells a UI
 * to render "247 results" when the truth is "at least 247". A hit with no provenance read as
 * "matched nothing" would hide every result of a search that named no branches — which is most
 * searches.
 */
class SearchResponseMapperTest {

    private static final FakeDocument DOCUMENT = FakeDocument.of("clinic-a", "doc-1");

    @Nested
    @DisplayName("totals")
    class Totals {

        @Test
        @DisplayName("a capped total is reported as a lower bound, not as a count")
        void aCappedTotalIsALowerBound() {
            SearchResults<FakeDocument> results = SearchResponseMapper.toSearchResults(
                    response(List.of(hit("doc-1", List.of())), 10_000L, TotalHitsRelation.Gte));

            // track_total_hits caps the count: Gte means "at least this many". Reported as EXACT,
            // a pager would offer pages that do not exist.
            assertThat(results.totalHits()).isEqualTo(10_000L);
            assertThat(results.totalIsExact()).isFalse();
            assertThat(results.totalHitsRelation()).isEqualTo(SearchResults.TotalHitsRelation.LOWER_BOUND);
        }

        @Test
        @DisplayName("an exact total crosses over as exact")
        void anExactTotalStaysExact() {
            SearchResults<FakeDocument> results = SearchResponseMapper.toSearchResults(
                    response(List.of(hit("doc-1", List.of())), 1L, TotalHitsRelation.Eq));

            assertThat(results.totalIsExact()).isTrue();
            assertThat(results.totalHits()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a null response maps to empty results rather than to a null-pointer")
        void aNullResponseIsEmpty() {
            assertThat(SearchResponseMapper.<FakeDocument>toSearchResults(null).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("provenance")
    class Provenance {

        @Test
        @DisplayName("a hit that named no queries reports no provenance, which is not the same as no match")
        void noNamedQueriesMeansNoProvenance() {
            SearchHit<FakeDocument> mapped = onlyHitOf(hit("doc-1", List.of()));

            assertThat(mapped.matchedOn()).isEmpty();
            assertThat(mapped.matchedContent()).isFalse();
            assertThat(mapped.matchedIdentifier()).isFalse();
            assertThat(mapped.document()).isEqualTo(DOCUMENT);
        }

        @Test
        @DisplayName("a branch name the toolkit does not know is ignored, not rejected")
        void unknownBranchNamesAreIgnored() {
            SearchHit<FakeDocument> mapped =
                    onlyHitOf(hit("doc-1", List.of("something-the-application-named", BranchNames.CONTENT)));

            assertThat(mapped.matchedOn()).containsExactly(MatchKind.CONTENT);
        }

        @Test
        @DisplayName("both branch names map to both kinds")
        void bothBranchesAreMapped() {
            SearchHit<FakeDocument> mapped =
                    onlyHitOf(hit("doc-1", List.of(BranchNames.CONTENT, BranchNames.IDENTIFIER)));

            assertThat(mapped.matchedOn()).containsExactlyInAnyOrder(MatchKind.CONTENT, MatchKind.IDENTIFIER);
        }
    }

    @Nested
    @DisplayName("highlights")
    class Highlights {

        @Test
        @DisplayName("fragments cross over per field")
        void fragmentsCrossOver() {
            SearchHit<FakeDocument> mapped = onlyHitOf(Hit.of(h -> h
                    .index("an-index").id("doc-1").source(DOCUMENT)
                    .highlight(Map.of("note", List.of("a <em>hemolyzed</em> sample")))));

            assertThat(mapped.highlightsFor("note")).containsExactly("a <em>hemolyzed</em> sample");
            assertThat(mapped.hasHighlights()).isTrue();
        }

        @Test
        @DisplayName("a field with no fragments is dropped rather than carried as an empty list")
        void emptyFragmentListsAreDropped() {
            SearchHit<FakeDocument> mapped = onlyHitOf(Hit.of(h -> h
                    .index("an-index").id("doc-1").source(DOCUMENT)
                    .highlight(Map.of("note", List.of()))));

            // A UI that asks "were there highlights?" must get "no" here, not a field whose list
            // turns out to be empty one call later.
            assertThat(mapped.hasHighlights()).isFalse();
            assertThat(mapped.firstHighlight("note")).isNull();
        }
    }

    @Nested
    @DisplayName("the document mapper")
    class DocumentMapping {

        @Test
        @DisplayName("is handed the whole hit, so a mapping failure can name the document")
        void theMapperSeesTheHit() {
            SearchResults<String> results = SearchResponseMapper.toSearchResults(
                    response(List.of(hit("doc-7", List.of())), 1L, TotalHitsRelation.Eq),
                    hit -> "mapped:" + hit.id());

            // The id lives on the hit, not in the source — an envelope that fails to open has
            // nothing else to name.
            assertThat(results.documents()).containsExactly("mapped:doc-7");
            assertThat(results.hits().getFirst().documentId()).isEqualTo("doc-7");
        }
    }

    private static SearchHit<FakeDocument> onlyHitOf(Hit<FakeDocument> hit) {
        return SearchResponseMapper.toSearchResults(response(List.of(hit), 1L, TotalHitsRelation.Eq))
                .hits().getFirst();
    }

    private static Hit<FakeDocument> hit(String id, List<String> matchedQueries) {
        return Hit.of(h -> h.index("an-index").id(id).score(1.0).source(DOCUMENT)
                .matchedQueries(matchedQueries));
    }

    private static <T> SearchResponse<T> response(List<Hit<T>> hits, long total, TotalHitsRelation relation) {
        return SearchingOpenSearchTransport.responseOf(hits, total, relation, 1.0);
    }
}
