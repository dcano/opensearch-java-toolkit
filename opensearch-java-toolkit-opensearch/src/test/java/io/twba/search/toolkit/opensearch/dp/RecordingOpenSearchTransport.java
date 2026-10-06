package io.twba.search.toolkit.opensearch.dp;

import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.Result;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.opensearch.client.opensearch.indices.PutIndicesSettingsRequest;
import org.opensearch.client.opensearch.indices.PutIndicesSettingsResponse;
import org.opensearch.client.opensearch.indices.RefreshRequest;
import org.opensearch.client.opensearch.indices.RefreshResponse;
import org.opensearch.client.transport.Endpoint;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.TransportOptions;
import org.opensearch.client.json.JsonpMapper;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A hand-written {@link OpenSearchTransport} the write-path tests drive a real
 * {@link org.opensearch.client.opensearch.OpenSearchClient} over.
 *
 * <p>Written rather than mocked for two reasons. First, the properties under test are about what was
 * <em>sent</em> — which indices were switched into bulk-load mode, which document instance was put
 * back on the wire after a retry — and those live in the recorded requests, not in a call count.
 * Second, the interesting behaviours are sequences: reject the whole request once, then accept it;
 * reject two items, then accept the rest. A script of answers reads like the cluster conversation it
 * stands in for.
 *
 * <p>It models just enough of the cluster to make the assertions honest:
 * <ul>
 *   <li>every request is recorded, in order, by kind;</li>
 *   <li>writes are stored under {@code index/_id}, so a re-send of the same document id overwrites
 *       rather than accumulating — which is the idempotency the {@code _id} choice buys;</li>
 *   <li>a single-document write reports {@code created} the first time and {@code updated} after,
 *       as the cluster does;</li>
 *   <li>bulk answers are scripted, and settings calls can be made to fail.</li>
 * </ul>
 *
 * <p>It deliberately does not serialize: {@link #jsonpMapper()} raises. Nothing on the write path
 * needs a mapper client-side, and a fake that quietly returned one would hide the day that changed.
 */
final class RecordingOpenSearchTransport implements OpenSearchTransport {

    /** What the cluster does with one bulk request. */
    @FunctionalInterface
    interface BulkAnswer {
        BulkResponse answer(BulkRequest request) throws IOException;
    }

    private static final int TOOK_MILLIS = 7;

    private final Deque<BulkAnswer> script = new ArrayDeque<>();
    private BulkAnswer afterScript = RecordingOpenSearchTransport::everyItemIndexed;

    private final List<BulkRequest> bulkRequests = new ArrayList<>();
    private final List<IndexRequest<?>> indexRequests = new ArrayList<>();
    private final List<PutIndicesSettingsRequest> putSettingsRequests = new ArrayList<>();
    private final List<RefreshRequest> refreshRequests = new ArrayList<>();
    private final Map<String, Object> stored = new LinkedHashMap<>();

    private Predicate<PutIndicesSettingsRequest> putSettingsFailure = request -> false;

    // ---------------------------------------------------------------- scripting

    /** Answers the next bulk requests in order; anything beyond the script is indexed cleanly. */
    RecordingOpenSearchTransport answeringBulk(BulkAnswer... answers) {
        script.addAll(List.of(answers));
        return this;
    }

    /** Answers every bulk request this way, for as long as the indexer keeps trying. */
    RecordingOpenSearchTransport alwaysAnsweringBulk(BulkAnswer answer) {
        this.afterScript = answer;
        return this;
    }

    /** Makes matching settings calls fail, e.g. only the restoration of bulk-load mode. */
    RecordingOpenSearchTransport failingPutSettings(Predicate<PutIndicesSettingsRequest> when) {
        this.putSettingsFailure = when;
        return this;
    }

    // ---------------------------------------------------------------- canned answers

    /** The happy path: every operation in the request lands. */
    static BulkResponse everyItemIndexed(BulkRequest request) {
        return BulkResponse.of(r -> r
                .errors(false)
                .took(TOOK_MILLIS)
                .items(indexedItems(request, Set.of(), Set.of())));
    }

    /** Per-item backpressure: the operations at {@code positions} come back as {@code 429}s. */
    static BulkAnswer rejectingItems(int... positions) {
        Set<Integer> rejected = intSet(positions);
        return request -> BulkResponse.of(r -> r
                .errors(true)
                .took(TOOK_MILLIS)
                .items(indexedItems(request, rejected, Set.of())));
    }

    /** Per-item backpressure for everything sent, whatever the chunk turns out to be. */
    static BulkAnswer rejectingEveryItem() {
        return request -> BulkResponse.of(r -> r
                .errors(true)
                .took(TOOK_MILLIS)
                .items(indexedItems(request, allPositions(request), Set.of())));
    }

    /** A non-retryable item error — the shape a malformed document comes back as. */
    static BulkAnswer failingItemsPermanently(int... positions) {
        Set<Integer> broken = intSet(positions);
        return request -> BulkResponse.of(r -> r
                .errors(true)
                .took(TOOK_MILLIS)
                .items(indexedItems(request, Set.of(), broken)));
    }

    /** The coordinating node refuses the whole request: nothing in it was indexed. */
    static BulkAnswer rejectingWholeRequest() {
        return request -> {
            throw openSearchException(429, "es_rejected_execution_exception", "bulk queue is full");
        };
    }

    /** A hard cluster-side failure, the kind that must not be retried. */
    static BulkAnswer failingWholeRequest() {
        return request -> {
            throw openSearchException(500, "illegal_state_exception", "the cluster is having a bad day");
        };
    }

    /** A transport-level failure wrapping a {@code 429}, which is retryable through its cause. */
    static BulkAnswer failingTransportWithNested429() {
        return request -> {
            throw new IOException("connection reset",
                    openSearchException(429, "es_rejected_execution_exception", "bulk queue is full"));
        };
    }

    static OpenSearchException openSearchException(int status, String type, String reason) {
        return new OpenSearchException(ErrorResponse.of(e -> e
                .status(status)
                .error(ErrorCause.of(c -> c.type(type).reason(reason)))));
    }

    // ---------------------------------------------------------------- what was sent

    List<BulkRequest> bulkRequests() {
        return List.copyOf(bulkRequests);
    }

    List<IndexRequest<?>> indexRequests() {
        return List.copyOf(indexRequests);
    }

    List<PutIndicesSettingsRequest> putSettingsRequests() {
        return List.copyOf(putSettingsRequests);
    }

    List<String> refreshedIndexes() {
        return refreshRequests.stream().flatMap(r -> r.index().stream()).toList();
    }

    /** {@code index/_id -> document} for everything the fake accepted, single or bulk. */
    Map<String, Object> stored() {
        return Map.copyOf(stored);
    }

    /** The number of documents in each bulk request received, in order — the chunking history. */
    List<Integer> bulkChunkSizes() {
        return bulkRequests.stream().map(r -> r.operations().size()).toList();
    }

    static List<String> idsOf(BulkRequest request) {
        return request.operations().stream().map(op -> op.index().id()).toList();
    }

    static List<Object> documentsOf(BulkRequest request) {
        return request.operations().stream().map(op -> (Object) op.index().document()).toList();
    }

    static List<String> routingsOf(BulkRequest request) {
        return request.operations().stream()
                .map(op -> String.valueOf(op.index().routing()))
                .toList();
    }

    static List<String> indexesOf(BulkRequest request) {
        return request.operations().stream().map(op -> op.index().index()).toList();
    }

    // ---------------------------------------------------------------- transport

    @Override
    @SuppressWarnings("unchecked")
    public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options)
            throws IOException {

        if (request instanceof BulkRequest bulk) {
            bulkRequests.add(bulk);
            BulkResponse response = (script.isEmpty() ? afterScript : script.poll()).answer(bulk);
            store(bulk, response);
            return (ResponseT) response;
        }
        if (request instanceof IndexRequest<?> index) {
            indexRequests.add(index);
            String key = index.index() + "/" + index.id();
            Result result = stored.containsKey(key) ? Result.Updated : Result.Created;
            stored.put(key, index.document());
            return (ResponseT) indexResponse(index.index(), index.id(), result);
        }
        if (request instanceof PutIndicesSettingsRequest putSettings) {
            putSettingsRequests.add(putSettings);
            if (putSettingsFailure.test(putSettings)) {
                throw openSearchException(503, "cluster_block_exception", "settings are not accepting writes");
            }
            return (ResponseT) PutIndicesSettingsResponse.of(r -> r.acknowledged(true));
        }
        if (request instanceof RefreshRequest refresh) {
            refreshRequests.add(refresh);
            return (ResponseT) RefreshResponse.of(r -> r.shards(oneHealthyShard()));
        }
        throw new UnsupportedOperationException(
                "the write path sent a request this fake does not model: " + request.getClass().getName());
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
                "the write path is not expected to serialize client-side; this fake records request objects");
    }

    @Override
    public TransportOptions options() {
        return TransportOptions.builder().build();
    }

    @Override
    public void close() {
        // nothing to release
    }

    // ---------------------------------------------------------------- internals

    private void store(BulkRequest request, BulkResponse response) {
        List<BulkOperation> operations = request.operations();
        for (int i = 0; i < operations.size(); i++) {
            if (response.items().get(i).error() != null) {
                continue;   // rejected or malformed: nothing was written
            }
            BulkOperation operation = operations.get(i);
            stored.put(operation.index().index() + "/" + operation.index().id(), operation.index().document());
        }
    }

    private static List<BulkResponseItem> indexedItems(
            BulkRequest request, Set<Integer> rejected, Set<Integer> broken) {
        List<BulkOperation> operations = request.operations();
        List<BulkResponseItem> items = new ArrayList<>(operations.size());
        for (int i = 0; i < operations.size(); i++) {
            BulkOperation operation = operations.get(i);
            String index = operation.index().index();
            String id = operation.index().id();
            if (rejected.contains(i)) {
                items.add(BulkResponseItem.of(item -> item
                        .operationType(OperationType.Index).index(index).id(id).status(429)
                        .error(e -> e.type("es_rejected_execution_exception").reason("bulk queue is full"))));
            } else if (broken.contains(i)) {
                items.add(BulkResponseItem.of(item -> item
                        .operationType(OperationType.Index).index(index).id(id).status(400)
                        .error(e -> e.type("mapper_parsing_exception").reason("failed to parse field"))));
            } else {
                items.add(BulkResponseItem.of(item -> item
                        .operationType(OperationType.Index).index(index).id(id).status(201).result("created")));
            }
        }
        return items;
    }

    private static IndexResponse indexResponse(String index, String id, Result result) {
        return IndexResponse.of(r -> r
                .index(index)
                .id(id)
                .result(result)
                .version(1L)
                .seqNo(0L)
                .primaryTerm(1L)
                .shards(oneHealthyShard()));
    }

    private static org.opensearch.client.opensearch._types.ShardStatistics oneHealthyShard() {
        return org.opensearch.client.opensearch._types.ShardStatistics.of(s -> s
                .total(1).successful(1).failed(0));
    }

    private static Set<Integer> allPositions(BulkRequest request) {
        Set<Integer> positions = new java.util.LinkedHashSet<>();
        for (int i = 0; i < request.operations().size(); i++) {
            positions.add(i);
        }
        return positions;
    }

    private static Set<Integer> intSet(int... values) {
        List<Integer> boxed = new ArrayList<>(values.length);
        for (int value : values) {
            boxed.add(value);
        }
        return boxed.stream().collect(Collectors.toUnmodifiableSet());
    }
}
