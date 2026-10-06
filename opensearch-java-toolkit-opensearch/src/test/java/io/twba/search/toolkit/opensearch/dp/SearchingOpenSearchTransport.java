package io.twba.search.toolkit.opensearch.dp;

import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.opensearch._types.ShardStatistics;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;
import org.opensearch.client.transport.Endpoint;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.TransportOptions;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A hand-written {@link OpenSearchTransport} that answers a {@code _search} with a canned
 * {@link SearchResponse}, so the read path can be driven over a <em>real</em>
 * {@link org.opensearch.client.opensearch.OpenSearchClient}.
 *
 * <p><b>Why a second fake rather than an extension of {@link RecordingOpenSearchTransport}.</b>
 * The two model opposite halves of the conversation and share nothing but the interface. The write
 * fake's whole substance is its scripted {@code BulkResponse} vocabulary — reject this item, refuse
 * the whole request, fail permanently — plus a store that proves idempotent re-sends; none of it is
 * reachable from a search, and a search needs none of it. What a read test needs instead is the
 * ability to hand back hits of an arbitrary document type, which is a different and slightly
 * uncomfortable piece of generics (see {@link SearchAnswer}). Folding both into one class would
 * produce a fixture whose two halves are never used together and whose type parameters exist only
 * for one of them. They are siblings, not parent and child.
 *
 * <p>Like its sibling it refuses to serialize: {@link #jsonpMapper()} raises. That is not laziness —
 * it is an assertion. {@code OpenSearchClient.search(fn, Class)} builds its response deserializer
 * from the class it is given and never touches the transport's mapper, so a fake that quietly
 * returned one would hide the day that stopped being true.
 *
 * <p>Requests are recorded in order and in full. Everything the executor is supposed to control —
 * the target index, the routing value, the query that was actually sent and whether the caller's
 * shaping was able to undo any of it — is read off the recorded {@link SearchRequest} rather than
 * inferred from a call count.
 */
final class SearchingOpenSearchTransport implements OpenSearchTransport {

    /**
     * What the cluster answers one search with.
     *
     * <p>Untyped on purpose. A {@code NORMAL} search deserializes hits as the application's document
     * and a {@code HIGH} search as a raw source map, so a single fake serving both cannot name one
     * element type. The client's own {@code performRequest} signature is erased in exactly the same
     * way, so nothing is being weakened here that the real transport does not already do.
     */
    @FunctionalInterface
    interface SearchAnswer {
        Object answer(SearchRequest request) throws IOException;
    }

    private static final int TOOK_MILLIS = 5;

    private final List<SearchRequest> searchRequests = new ArrayList<>();
    private final Deque<SearchAnswer> script = new ArrayDeque<>();
    private SearchAnswer afterScript = request -> noHits();

    // ---------------------------------------------------------------- scripting

    /** Answers the next searches in order; anything beyond the script comes back empty. */
    SearchingOpenSearchTransport answering(SearchAnswer... answers) {
        script.addAll(List.of(answers));
        return this;
    }

    /** Answers every search the same way, however many the code under test issues. */
    SearchingOpenSearchTransport alwaysAnswering(SearchAnswer answer) {
        this.afterScript = answer;
        return this;
    }

    /** The cluster is unreachable: every search raises, as a socket failure does. */
    SearchingOpenSearchTransport failingWith(IOException failure) {
        this.afterScript = request -> {
            throw failure;
        };
        return this;
    }

    // ---------------------------------------------------------------- canned answers

    /** A response with no hits and an exact total of zero. */
    static <T> SearchResponse<T> noHits() {
        return responseOf(List.of(), 0L, TotalHitsRelation.Eq, null);
    }

    /** A response carrying {@code hits}, with an exact total and a max score. */
    @SafeVarargs
    static <T> SearchResponse<T> hits(Hit<T>... hits) {
        List<Hit<T>> list = List.of(hits);
        Double maxScore = list.stream().map(Hit::score).filter(java.util.Objects::nonNull)
                .max(Double::compareTo).orElse(null);
        return responseOf(list, list.size(), TotalHitsRelation.Eq, maxScore);
    }

    static <T> SearchResponse<T> responseOf(List<Hit<T>> hits, long total,
                                            TotalHitsRelation relation, Double maxScore) {
        HitsMetadata<T> metadata = HitsMetadata.of(h -> h
                .total(t -> t.value(total).relation(relation))
                .hits(hits));
        return SearchResponse.searchResponseOf(r -> r
                .took(TOOK_MILLIS)
                .timedOut(false)
                .shards(ShardStatistics.of(s -> s.total(1).successful(1).skipped(0).failed(0)))
                .maxScore(maxScore)
                .hits(metadata));
    }

    /** One hit, with the branch names the cluster echoes back in {@code matched_queries}. */
    static <T> Hit<T> hit(String id, T source, double score, List<String> matchedQueries) {
        return Hit.of(h -> h
                .index("an-index")
                .id(id)
                .score(score)
                .source(source)
                .matchedQueries(matchedQueries));
    }

    /** One hit with highlight fragments, the shape the content branch produces. */
    static <T> Hit<T> highlightedHit(String id, T source, double score, List<String> matchedQueries,
                                     Map<String, List<String>> highlight) {
        return Hit.of(h -> h
                .index("an-index")
                .id(id)
                .score(score)
                .source(source)
                .matchedQueries(matchedQueries)
                .highlight(highlight));
    }

    /**
     * A hit the cluster returned with no {@code _source} — what a source filter that excluded the
     * envelope components produces, and the one case a {@code HIGH} read must refuse rather than
     * answer with blanks.
     */
    static <T> Hit<T> sourcelessHit(String id) {
        return Hit.of(h -> h.index("an-index").id(id).score(1.0));
    }

    // ---------------------------------------------------------------- what was sent

    List<SearchRequest> searchRequests() {
        return List.copyOf(searchRequests);
    }

    SearchRequest onlySearch() {
        if (searchRequests.size() != 1) {
            throw new AssertionError("expected exactly one search, got " + searchRequests.size());
        }
        return searchRequests.getFirst();
    }

    int searchCount() {
        return searchRequests.size();
    }

    // ---------------------------------------------------------------- transport

    @Override
    @SuppressWarnings("unchecked")
    public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options)
            throws IOException {

        if (request instanceof SearchRequest search) {
            searchRequests.add(search);
            return (ResponseT) (script.isEmpty() ? afterScript : script.poll()).answer(search);
        }
        throw new UnsupportedOperationException(
                "the read path sent a request this fake does not model: " + request.getClass().getName());
    }

    @Override
    public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options) {
        try {
            return CompletableFuture.completedFuture(performRequest(request, endpoint, options));
        } catch (IOException | RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    @Override
    public JsonpMapper jsonpMapper() {
        throw new UnsupportedOperationException(
                "a search builds its deserializer from the document class, not from the transport's mapper; "
                        + "this fake hands back response objects directly");
    }

    @Override
    public TransportOptions options() {
        return TransportOptions.builder().build();
    }

    @Override
    public void close() {
        // nothing to release
    }
}
