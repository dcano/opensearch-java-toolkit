package io.twba.search.toolkit.opensearch.dp;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.twba.search.toolkit.BulkStats;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.obs.OpenSearchObservations;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bulk indexer with backpressure handling: on {@code 429} back off exponentially <em>and</em> shrink
 * the batch — the cluster telling you to slow down is the system working.
 *
 * <p>Two shapes of {@code 429} are handled:
 * <ul>
 *   <li><b>Whole request rejected</b> — {@link OpenSearchException} with status 429: every document
 *       in that chunk goes back on the pending list.</li>
 *   <li><b>Per-item rejection</b> — {@code errors() == true} with items carrying status 429 or
 *       {@code es_rejected_execution_exception}: only those documents are retried. Non-retryable
 *       item errors ({@code mapper_parsing_exception} and friends) are counted as failed and logged
 *       for a dead-letter store — retrying them would only fail again.</li>
 * </ul>
 *
 * <p>Retrying is safe because the {@code _id} is {@link TenantDocument#documentId()}: re-sending a
 * document that already landed overwrites it with the same content.
 */
public class BulkDocumentIndexer<T extends TenantDocument> {

    private static final Logger log = LoggerFactory.getLogger(BulkDocumentIndexer.class);

    private static final int TOO_MANY_REQUESTS = 429;
    private static final String REJECTED_EXECUTION = "es_rejected_execution_exception";

    /** Backoff defaults: 0.5s, 1s, 2s, 4s … capped at 30s, ±50% jitter so parallel indexers do not resynchronise. */
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final double BACKOFF_JITTER = 0.5;
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);
    /** Halving the chunk on every retry stops here — below this the round trips cost more than they save. */
    private static final int MIN_CHUNK_SIZE = 50;

    private final OpenSearchClient client;
    private final SearchDomain domain;
    private final TenantIndexResolver resolver;
    private final WriteDocuments<T> documents;
    private final RetryConfig retryConfig;
    private final OpenSearchObservations observations;

    /**
     * Keyed on the whole {@link TenantRef} because that is what {@link TenantIndexResolver} takes:
     * the key is handed straight back to it to name the index to restore, so keeping the reference
     * avoids rebuilding — and re-validating — it on the way out.
     *
     * <p>It is <em>not</em> what keeps two domains apart. One indexer serves one domain, bound at
     * construction, so every key here carries the same domain and a map keyed on the bare tenant id
     * would behave identically. Two domains means two indexers and two maps.
     */
    private final Map<TenantRef, Boolean> bulkLoadMode = new ConcurrentHashMap<>();

    /** {@code NORMAL}-only: {@code HIGH} tenants are refused rather than written in plaintext. */
    public BulkDocumentIndexer(OpenSearchClient client, SearchDomain domain, TenantIndexResolver resolver) {
        this(client, domain, resolver, OpenSearchObservations.noop());
    }

    /** {@code NORMAL}-only: {@code HIGH} tenants are refused rather than written in plaintext. */
    public BulkDocumentIndexer(OpenSearchClient client, SearchDomain domain, TenantIndexResolver resolver,
                               OpenSearchObservations observations) {
        this(client, domain, resolver, WriteDocuments.plaintextOnly(resolver), defaultRetryConfig(), observations);
    }

    public BulkDocumentIndexer(OpenSearchClient client, SearchDomain domain, TenantIndexResolver resolver,
                               WriteDocuments<T> documents, OpenSearchObservations observations) {
        this(client, domain, resolver, documents, defaultRetryConfig(), observations);
    }

    public BulkDocumentIndexer(OpenSearchClient client, SearchDomain domain, TenantIndexResolver resolver,
                               WriteDocuments<T> documents, RetryConfig retryConfig,
                               OpenSearchObservations observations) {
        this.client = Objects.requireNonNull(client, "client");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.retryConfig = Objects.requireNonNull(retryConfig, "retryConfig");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    /** Exponential backoff with jitter, retried while documents remain rejected with 429. */
    public static RetryConfig defaultRetryConfig() {
        return RetryConfig.<List<?>>custom()
                .maxAttempts(DEFAULT_MAX_ATTEMPTS)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        DEFAULT_INITIAL_BACKOFF, BACKOFF_MULTIPLIER, BACKOFF_JITTER, DEFAULT_MAX_BACKOFF))
                // A retryable outcome is "documents still pending", not an exception: a bulk response
                // with rejected items is a perfectly successful HTTP call.
                .retryOnResult(pending -> !pending.isEmpty())
                // …but a 429 that escapes as an exception is retryable too.
                .retryOnException(BulkDocumentIndexer::isTooManyRequests)
                // Give back the last outcome instead of throwing: leftovers are reported as failures
                // in BulkStats so the caller can dead-letter them.
                .failAfterMaxAttempts(false)
                .build();
    }

    public BulkStats bulkIndex(List<T> batch) throws IOException {
        if (batch.isEmpty()) {
            return new BulkStats(0, 0, 0L);
        }
        // Shape every document before the first cluster call. Two properties come out of doing it
        // here rather than per chunk: a batch holding one HIGH document whose tenant has no key
        // material writes nothing at all — it fails before bulk-load mode is even switched on — and
        // a retried chunk re-sends the sealed bytes it already produced instead of re-encrypting
        // under a fresh data key on every attempt.
        Map<T, Prepared> prepared = new IdentityHashMap<>();
        for (T document : batch) {
            TenantRef tenant = TenantRef.of(domain, document.tenantId());
            prepared.put(document, new Prepared(tenant, documents.forWrite(tenant, document)));
        }
        try {
            return observations.bulk(domain, batch.size(), () -> {
                for (T document : batch) {
                    preBulkLoadMode(prepared.get(document).tenant());
                }
                return indexWithBackpressure(batch, prepared);
            });
        } finally {
            restoreBulkLoadModes();
        }
    }

    /** One document's addressing and payload, settled before the first attempt and reused by every retry. */
    private record Prepared(TenantRef tenant, Object payload) {
    }

    private BulkStats indexWithBackpressure(List<T> batch, Map<T, Prepared> prepared) throws IOException {
        BackpressureRun run = new BackpressureRun(batch, prepared);
        Retry retry = Retry.of("bulk-index", retryConfig);
        retry.getEventPublisher().onRetry(event -> {
            run.shrinkChunk();
            observations.bulkRetry(domain, event.getNumberOfRetryAttempts(), run.chunkSize());
            log.warn("OpenSearch backpressure in domain {}: attempt {} rejected {} doc(s); backing off {} ms, chunk size now {}",
                    domain.name(), event.getNumberOfRetryAttempts(), run.pendingCount(),
                    event.getWaitInterval().toMillis(), run.chunkSize());
        });

        try {
            Retry.decorateCheckedSupplier(retry, run::attempt).get();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Unexpected failure while bulk indexing", t);
        }
        return run.stats();
    }

    /** One pass over everything still pending; carries the state the retries share. */
    private final class BackpressureRun {

        private final int total;
        private final Map<T, Prepared> prepared;
        private List<T> pending;
        private int chunkSize;
        private int permanentFailures;
        private long tookMillis;

        private BackpressureRun(List<T> batch, Map<T, Prepared> prepared) {
            this.prepared = prepared;
            this.total = batch.size();
            this.pending = List.copyOf(batch);
            this.chunkSize = batch.size();
        }

        /** @return the documents still rejected — non-empty means resilience4j backs off and calls us again. */
        private List<T> attempt() throws IOException {
            List<List<T>> chunks = chunks(pending, chunkSize);
            List<T> stillPending = new ArrayList<>();
            int sent = 0;
            try {
                for (; sent < chunks.size(); sent++) {
                    stillPending.addAll(sendChunk(chunks.get(sent)));
                }
            } finally {
                // Whatever an aborted attempt never reached stays pending, including the chunk that
                // blew up — re-sending is idempotent (_id = documentId).
                for (int i = sent; i < chunks.size(); i++) {
                    stillPending.addAll(chunks.get(i));
                }
                pending = List.copyOf(stillPending);
            }
            return pending;
        }

        private List<T> sendChunk(List<T> chunk) throws IOException {
            BulkRequest.Builder br = new BulkRequest.Builder();
            for (T document : chunk) {
                Prepared p = prepared.get(document);
                br.operations(op -> op.index(idx -> idx
                        .index(resolver.writeIndex(p.tenant()))
                        .id(document.documentId())
                        .routing(resolver.routing(p.tenant()).orElse(null))
                        .document(p.payload())));
            }

            BulkResponse resp;
            try {
                resp = client.bulk(br.build());
            } catch (OpenSearchException ex) {
                if (isTooManyRequests(ex)) {
                    // The coordinating node refused the whole request: nothing was indexed.
                    observations.bulkRejection(domain, "request", chunk.size());
                    log.warn("Bulk request rejected with 429 ({} docs) in domain {}", chunk.size(), domain.name());
                    return chunk;
                }
                throw ex;
            }

            tookMillis += resp.took();
            if (!resp.errors()) {
                return List.of();
            }

            List<T> rejected = new ArrayList<>();
            List<BulkResponseItem> items = resp.items();
            for (int i = 0; i < items.size(); i++) {
                BulkResponseItem item = items.get(i);
                if (item.error() == null) {
                    continue;
                }
                if (isRetryable(item)) {
                    rejected.add(chunk.get(i)); // items come back in request order
                    observations.bulkRejection(domain, "item", 1);
                } else {
                    // Not retryable (mapper_parsing_exception etc.) — dead-letter territory. The
                    // document id is safe to log; the document is not, and is not logged.
                    permanentFailures++;
                    log.error("FAILED domain={} id={} status={} type={} reason={}",
                            domain.name(), item.id(), item.status(), item.error().type(), item.error().reason());
                }
            }
            return rejected;
        }

        private void shrinkChunk() {
            chunkSize = Math.max(MIN_CHUNK_SIZE, chunkSize / 2);
        }

        private int pendingCount() {
            return pending.size();
        }

        private int chunkSize() {
            return chunkSize;
        }

        private BulkStats stats() {
            if (!pending.isEmpty()) {
                log.error("{} doc(s) in domain {} still rejected with 429 after {} attempts — hand them to a dead-letter/replay queue",
                        pending.size(), domain.name(), retryConfig.getMaxAttempts());
            }
            int failed = permanentFailures + pending.size();
            return new BulkStats(total - failed, failed, tookMillis);
        }
    }

    private static <T> List<List<T>> chunks(List<T> documents, int chunkSize) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < documents.size(); i += chunkSize) {
            chunks.add(documents.subList(i, Math.min(documents.size(), i + chunkSize)));
        }
        return chunks;
    }

    private static boolean isRetryable(BulkResponseItem item) {
        return item.status() == TOO_MANY_REQUESTS
                || (item.error() != null && REJECTED_EXECUTION.equals(item.error().type()));
    }

    private static boolean isTooManyRequests(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenSearchException ose && ose.status() == TOO_MANY_REQUESTS) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    private void preBulkLoadMode(TenantRef tenant) throws IOException {
        if (!bulkLoadMode.containsKey(tenant)) {
            client.indices().putSettings(s -> s.index(resolver.writeIndex(tenant)).settings(st -> st
                    .refreshInterval(r -> r.time("-1"))
                    .numberOfReplicas(0)));
            bulkLoadMode.put(tenant, true);
        }
    }

    /**
     * Always restore refresh and replicas, even when the load blew up — leaving an index at
     * {@code refresh_interval: -1, replicas: 0} is worse than the failure itself.
     */
    private void restoreBulkLoadModes() {
        for (TenantRef tenant : bulkLoadMode.keySet()) {
            try {
                postBulkLoadMode(tenant);
            } catch (IOException | RuntimeException ex) {
                log.error("Could not restore bulk-load settings for tenant {}", tenant, ex);
            }
        }
    }

    private void postBulkLoadMode(TenantRef tenant) throws IOException {
        client.indices().putSettings(s -> s.index(resolver.writeIndex(tenant)).settings(st -> st
                .refreshInterval(r -> r.time("1s"))
                .numberOfReplicas(1)));
        client.indices().refresh(r -> r.index(resolver.writeIndex(tenant)));
        bulkLoadMode.remove(tenant);
    }
}
