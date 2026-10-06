package io.twba.search.toolkit.opensearch.dp;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.twba.search.toolkit.BulkStats;
import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.KeyUnavailableException;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.cp.InMemoryTenantCatalog;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.MigrationState;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Placement;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Tier;
import io.twba.search.toolkit.opensearch.obs.OpenSearchObservations;
import io.twba.search.toolkit.opensearch.obs.RecordingTelemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.Time;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.indices.PutIndicesSettingsRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The backpressure machinery, driven over a fake transport so every property can be read off the
 * requests that were actually sent.
 *
 * <p>The kata had no unit test for this class at all — its behaviour was only brushed by
 * Testcontainers tests, which cannot provoke a {@code 429} on demand and cannot see which payload
 * instance went back on the wire. That is the gap these tests close, and the properties are the ones
 * that are invisible in a happy-path integration run:
 *
 * <ul>
 *   <li><b>Everything is shaped before the first cluster call.</b> A batch holding one document that
 *       cannot be sealed must write nothing and must not have switched a single index into bulk-load
 *       mode. Shape per chunk instead and the failure arrives after some documents have landed and
 *       after indices have had their refresh disabled.</li>
 *   <li><b>A retry re-sends what was already sealed.</b> Re-sealing per attempt produces a different
 *       document on every attempt, under a fresh data key, for no gain.</li>
 *   <li><b>Rejections and failures are told apart.</b> A {@code 429} is retried; a
 *       {@code mapper_parsing_exception} is counted and reported, because retrying it just fails
 *       again, forever.</li>
 *   <li><b>Refresh interval and replicas come back.</b> Even when the load throws, and even when the
 *       restoration itself fails — an index left at {@code refresh_interval: -1, replicas: 0} is
 *       worse than the failure that caused it.</li>
 * </ul>
 *
 * <p>Retry intervals here are 10&nbsp;ms (resilience4j's floor). The production defaults start at
 * 500&nbsp;ms and would make this suite sleep for seconds; the intervals are injected precisely so
 * they do not have to be waited out.
 */
class BulkDocumentIndexerTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");
    private static final SearchDomains DOMAINS = SearchDomains.of(LAB_RESULTS, ORDERS);
    private static final int POOLS = 4;

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String HIGH_TENANT = "tenant-high";
    private static final String KEYLESS_TENANT = "tenant-keyless";
    private static final String SHARED_ID = "tenant-shared";

    private static final String POOL_A = "lab-results-pool-1-write";
    private static final String POOL_B = "lab-results-pool-2-write";

    private final RecordingOpenSearchTransport transport = new RecordingOpenSearchTransport();
    private final OpenSearchClient client = new OpenSearchClient(transport);
    private final TenantIndexResolver resolver = new CatalogTenantIndexResolver(catalog(), DOMAINS);
    private final CountingSecureMapper secureMapper = new CountingSecureMapper(Set.of(KEYLESS_TENANT));

    @Nested
    @DisplayName("shaping every payload before the first cluster call")
    class PreShaping {

        @Test
        @DisplayName("one unshapeable HIGH document in the batch means nothing is written and no index is switched")
        void oneKeylessDocumentStopsTheWholeBatch() {
            List<FakeDocument> batch = List.of(
                    FakeDocument.of(TENANT_A, "doc-1"),
                    FakeDocument.of(KEYLESS_TENANT, "doc-2"),
                    FakeDocument.of(TENANT_A, "doc-3"));

            assertThatExceptionOfType(KeyUnavailableException.class)
                    .isThrownBy(() -> indexer().bulkIndex(batch))
                    .withMessageContaining(KEYLESS_TENANT);

            assertThat(transport.bulkRequests()).isEmpty();
            assertThat(transport.stored()).isEmpty();
            // The one that is easy to lose: bulk-load mode is switched per tenant inside the
            // observed call, which must not be entered at all if any payload cannot be produced.
            assertThat(transport.putSettingsRequests()).isEmpty();
        }

        @Test
        @DisplayName("a HIGH tenant in a deployment with no secure mapper stops the batch the same way")
        void aMissingMapperStopsTheWholeBatch() {
            BulkDocumentIndexer<FakeDocument> plaintextOnly = new BulkDocumentIndexer<>(
                    client, LAB_RESULTS, resolver, WriteDocuments.plaintextOnly(resolver),
                    fastRetries(2), OpenSearchObservations.noop());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> plaintextOnly.bulkIndex(List.of(
                            FakeDocument.of(TENANT_A, "doc-1"),
                            FakeDocument.of(HIGH_TENANT, "doc-2"))))
                    .withMessageContaining(HIGH_TENANT);

            assertThat(transport.bulkRequests()).isEmpty();
            assertThat(transport.putSettingsRequests()).isEmpty();
        }

        @Test
        @DisplayName("a retried chunk re-sends the payloads sealed before the first attempt")
        void retriedChunkIsNotResealed() throws IOException {
            transport.answeringBulk(RecordingOpenSearchTransport.rejectingWholeRequest());
            List<FakeDocument> batch = List.of(
                    FakeDocument.of(HIGH_TENANT, "doc-1"),
                    FakeDocument.of(HIGH_TENANT, "doc-2"));

            BulkStats stats = indexer().bulkIndex(batch);

            assertThat(stats.succeeded()).isEqualTo(2);
            // Sealed once per document, however many attempts it took.
            assertThat(secureMapper.sealedDocumentIds()).containsExactly("doc-1", "doc-2");

            List<BulkRequest> sent = transport.bulkRequests();
            assertThat(sent).hasSize(2);
            List<Object> first = RecordingOpenSearchTransport.documentsOf(sent.get(0));
            List<Object> second = RecordingOpenSearchTransport.documentsOf(sent.get(1));
            assertThat(second.get(0)).isSameAs(first.get(0));
            assertThat(second.get(1)).isSameAs(first.get(1));
        }
    }

    @Nested
    @DisplayName("the requests it builds")
    class TheRequests {

        @Test
        @DisplayName("every operation carries the resolver's index, the document's id and the tenant's routing")
        void operationsCarryTheResolvedAddressing() throws IOException {
            indexer().bulkIndex(List.of(FakeDocument.of(TENANT_A, "doc-1"), FakeDocument.of(TENANT_B, "doc-2")));

            BulkRequest sent = transport.bulkRequests().getFirst();
            assertThat(RecordingOpenSearchTransport.indexesOf(sent)).containsExactly(POOL_A, POOL_B);
            assertThat(RecordingOpenSearchTransport.idsOf(sent)).containsExactly("doc-1", "doc-2");
            assertThat(RecordingOpenSearchTransport.routingsOf(sent)).containsExactly(TENANT_A, TENANT_B);
        }

        @Test
        @DisplayName("an empty batch reaches no cluster at all")
        void emptyBatchTouchesNothing() throws IOException {
            assertThat(indexer().bulkIndex(List.of())).isEqualTo(new BulkStats(0, 0, 0L));

            assertThat(transport.bulkRequests()).isEmpty();
            assertThat(transport.putSettingsRequests()).isEmpty();
        }
    }

    @Nested
    @DisplayName("telling rejection from failure")
    class Classification {

        @Test
        @DisplayName("a per-item 429 retries only the rejected document")
        void perItemRejectionRetriesOnlyThatDocument() throws IOException {
            transport.answeringBulk(RecordingOpenSearchTransport.rejectingItems(1));

            BulkStats stats = indexer().bulkIndex(List.of(
                    FakeDocument.of(TENANT_A, "doc-1"),
                    FakeDocument.of(TENANT_A, "doc-2"),
                    FakeDocument.of(TENANT_A, "doc-3")));

            assertThat(transport.bulkRequests()).hasSize(2);
            // The two that landed are not sent again; resending them would be wasted work, and
            // resending all three is what a "retry the chunk" shortcut would do.
            assertThat(RecordingOpenSearchTransport.idsOf(transport.bulkRequests().get(1)))
                    .containsExactly("doc-2");
            // 14 ms = both round trips: the reported time is what the cluster spent on this load in
            // total, so a retry adds to it rather than replacing what the first attempt cost.
            assertThat(stats).isEqualTo(new BulkStats(3, 0, 14L));
        }

        @Test
        @DisplayName("a whole-request 429 puts the entire chunk back")
        void wholeRequestRejectionRetriesEverything() throws IOException {
            transport.answeringBulk(RecordingOpenSearchTransport.rejectingWholeRequest());

            BulkStats stats = indexer().bulkIndex(List.of(
                    FakeDocument.of(TENANT_A, "doc-1"),
                    FakeDocument.of(TENANT_A, "doc-2"),
                    FakeDocument.of(TENANT_A, "doc-3")));

            assertThat(transport.bulkRequests()).hasSize(2);
            assertThat(RecordingOpenSearchTransport.idsOf(transport.bulkRequests().get(1)))
                    .containsExactly("doc-1", "doc-2", "doc-3");
            assertThat(stats.succeeded()).isEqualTo(3);
            // Nothing was indexed by the refused request, so its took time counts for nothing.
            assertThat(stats.tookMillis()).isEqualTo(7L);
        }

        @Test
        @DisplayName("a permanent item error is counted as failed and never sent again")
        void permanentItemErrorIsReportedNotRetried() throws IOException {
            transport.answeringBulk(RecordingOpenSearchTransport.failingItemsPermanently(2));

            BulkStats stats = indexer().bulkIndex(List.of(
                    FakeDocument.of(TENANT_A, "doc-1"),
                    FakeDocument.of(TENANT_A, "doc-2"),
                    FakeDocument.of(TENANT_A, "doc-3")));

            // One request only: a malformed document retried is a malformed document rejected again.
            assertThat(transport.bulkRequests()).hasSize(1);
            assertThat(stats).isEqualTo(new BulkStats(2, 1, 7L));
        }

        @Test
        @DisplayName("a transport failure wrapping a 429 is retried, with the leftover chunk intact")
        void aWrapped429IsRetried() throws IOException {
            transport.answeringBulk(RecordingOpenSearchTransport.failingTransportWithNested429());

            BulkStats stats = indexer().bulkIndex(List.of(
                    FakeDocument.of(TENANT_A, "doc-1"),
                    FakeDocument.of(TENANT_A, "doc-2")));

            // The attempt aborted mid-flight; both documents had to stay pending for the next one.
            assertThat(transport.bulkRequests()).hasSize(2);
            assertThat(RecordingOpenSearchTransport.idsOf(transport.bulkRequests().get(1)))
                    .containsExactly("doc-1", "doc-2");
            assertThat(stats.succeeded()).isEqualTo(2);
        }

        @Test
        @DisplayName("a failure that is not a 429 escapes without being retried")
        void aHardFailureIsNotRetried() {
            transport.alwaysAnsweringBulk(RecordingOpenSearchTransport.failingWholeRequest());

            assertThatExceptionOfType(OpenSearchException.class)
                    .isThrownBy(() -> indexer().bulkIndex(List.of(FakeDocument.of(TENANT_A, "doc-1"))))
                    .matches(ex -> ex.status() == 500, "status 500");
            assertThat(transport.bulkRequests()).hasSize(1);
        }

        @Test
        @DisplayName("documents still rejected after the last attempt are reported as failed, not lost")
        void exhaustedRetriesReportFailures() throws IOException {
            transport.alwaysAnsweringBulk(RecordingOpenSearchTransport.rejectingEveryItem());

            BulkStats stats = indexer(fastRetries(2)).bulkIndex(List.of(
                    FakeDocument.of(TENANT_A, "doc-1"),
                    FakeDocument.of(TENANT_A, "doc-2")));

            // Reported rather than thrown: the caller needs the list to dead-letter, and a throw
            // would hide the documents that did land in a larger batch.
            assertThat(stats.succeeded()).isZero();
            assertThat(stats.failed()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("shrinking the chunk under sustained backpressure")
    class ChunkShrinking {

        @Test
        @DisplayName("the chunk halves on every retry and stops at 50")
        void chunkHalvesPerRetryDownToTheFloor() throws IOException {
            transport.alwaysAnsweringBulk(RecordingOpenSearchTransport.rejectingEveryItem());
            List<FakeDocument> batch = batchOf(TENANT_A, 200);

            BulkStats stats = indexer(fastRetries(4)).bulkIndex(batch);

            // 200 → 100 → 50 → 50: halving, then the floor, which stops the round trips from
            // costing more than the smaller chunk saves.
            assertThat(transport.bulkChunkSizes())
                    .containsExactly(200, 100, 100, 50, 50, 50, 50, 50, 50, 50, 50);
            assertThat(stats.failed()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("bulk-load mode")
    class BulkLoadMode {

        @Test
        @DisplayName("switches each addressed index off refresh once, and restores every one of them")
        void switchesAndRestoresEachIndexOnce() throws IOException {
            indexer().bulkIndex(List.of(
                    FakeDocument.of(TENANT_A, "doc-1"),
                    FakeDocument.of(TENANT_B, "doc-2"),
                    FakeDocument.of(TENANT_A, "doc-3")));   // same index again: must not switch twice

            assertThat(indexesSwitchedOff()).containsExactlyInAnyOrder(POOL_A, POOL_B);
            assertThat(indexesRestored()).containsExactlyInAnyOrder(POOL_A, POOL_B);
            assertThat(transport.putSettingsRequests()).hasSize(4);
            assertThat(transport.refreshedIndexes()).containsExactlyInAnyOrder(POOL_A, POOL_B);

            assertThat(settingsFor(POOL_A, BulkDocumentIndexerTest::switchesOff).numberOfReplicas()).isZero();
            assertThat(settingsFor(POOL_A, BulkDocumentIndexerTest::restores).numberOfReplicas()).isEqualTo(1);
        }

        @Test
        @DisplayName("restores refresh and replicas even when the load blows up")
        void restoresAfterAFailure() {
            transport.alwaysAnsweringBulk(RecordingOpenSearchTransport.failingWholeRequest());

            assertThatExceptionOfType(OpenSearchException.class)
                    .isThrownBy(() -> indexer().bulkIndex(List.of(FakeDocument.of(TENANT_A, "doc-1"))));

            // Leaving an index at refresh_interval:-1, replicas:0 is worse than the failure itself:
            // nothing new becomes searchable and a node loss becomes data loss.
            assertThat(indexesRestored()).containsExactly(POOL_A);
            assertThat(transport.refreshedIndexes()).containsExactly(POOL_A);
        }

        @Test
        @DisplayName("a failed restoration is reported but does not replace the failure that caused it")
        void aFailingRestorationDoesNotMaskTheOriginalFailure() {
            transport.alwaysAnsweringBulk(RecordingOpenSearchTransport.failingWholeRequest())
                    .failingPutSettings(BulkDocumentIndexerTest::restores);

            // The 500 from the bulk load, not the 503 from the settings call in the finally block:
            // the second is a consequence, and swapping them sends whoever is paged to the wrong place.
            assertThatExceptionOfType(OpenSearchException.class)
                    .isThrownBy(() -> indexer().bulkIndex(List.of(FakeDocument.of(TENANT_A, "doc-1"))))
                    .matches(ex -> ex.status() == 500, "status 500");
            assertThat(indexesRestored()).containsExactly(POOL_A);
        }

        @Test
        @DisplayName("two domains holding a tenant of the same id each switch and restore their own index")
        void eachDomainSwitchesItsOwnIndex() throws IOException {
            FakeDocument document = FakeDocument.of(SHARED_ID, "doc-1");

            indexer().bulkIndex(List.of(document));
            new BulkDocumentIndexer<>(client, ORDERS, resolver, WriteDocuments.plaintextOnly(resolver),
                    fastRetries(2), OpenSearchObservations.noop()).bulkIndex(List.of(document));

            // The tenant id is the same in both; the index is not. An index composed from anything
            // but the addressed (domain, tenant) pair would switch one of these twice and the other
            // never — and "never" leaves a live index with refresh disabled.
            assertThat(indexesSwitchedOff())
                    .containsExactlyInAnyOrder("lab-results-pool-1-write", "orders-pool-1-write");
            assertThat(indexesRestored())
                    .containsExactlyInAnyOrder("lab-results-pool-1-write", "orders-pool-1-write");
        }
    }

    @Nested
    @DisplayName("the production backoff configuration")
    class DefaultRetryConfiguration {

        @Test
        @DisplayName("retries while documents remain pending, and stops when none do")
        void pendingDocumentsAreTheRetryableOutcome() {
            Predicate<List<?>> retryable = BulkDocumentIndexer.defaultRetryConfig().getResultPredicate();

            assertThat(retryable.test(List.of("doc-1"))).isTrue();
            assertThat(retryable.test(List.of())).isFalse();
        }

        @Test
        @DisplayName("treats a 429 as retryable wherever it sits in the cause chain, and nothing else")
        void onlyA429IsRetryableAsAnException() {
            Predicate<Throwable> retryable = BulkDocumentIndexer.defaultRetryConfig().getExceptionPredicate();

            assertThat(retryable.test(tooManyRequests())).isTrue();
            // Transports wrap: a 429 that arrives as the cause of an IOException is the same signal.
            assertThat(retryable.test(new IOException("connection reset", tooManyRequests()))).isTrue();
            assertThat(retryable.test(RecordingOpenSearchTransport
                    .openSearchException(400, "mapper_parsing_exception", "bad field"))).isFalse();
            assertThat(retryable.test(new IllegalStateException("something else entirely"))).isFalse();
        }

        @Test
        @DisplayName("gives the last outcome back instead of throwing, so leftovers can be dead-lettered")
        void doesNotThrowAfterTheLastAttempt() {
            assertThat(BulkDocumentIndexer.defaultRetryConfig().isFailAfterMaxAttempts()).isFalse();
            assertThat(BulkDocumentIndexer.defaultRetryConfig().getMaxAttempts()).isGreaterThan(1);
        }

        private static OpenSearchException tooManyRequests() {
            return RecordingOpenSearchTransport.openSearchException(
                    429, "es_rejected_execution_exception", "bulk queue is full");
        }
    }

    @Nested
    @DisplayName("what reaches telemetry")
    class Telemetry {

        @Test
        @DisplayName("no document content reaches a meter name or tag, through rejection and retry")
        void nothingSensitiveReachesMeters() throws IOException {
            RecordingTelemetry telemetry = new RecordingTelemetry();
            transport.answeringBulk(RecordingOpenSearchTransport.rejectingItems(0));
            FakeDocument document = new FakeDocument(TENANT_A, "doc-1", "sentinel-patient-name");

            new BulkDocumentIndexer<>(client, LAB_RESULTS, resolver, new WriteDocuments<>(resolver, secureMapper),
                    fastRetries(3), telemetry.observations()).bulkIndex(List.of(document));

            // The retry and rejection paths are the ones that log and meter the most, so they are
            // the ones most likely to reach for the document to explain themselves.
            assertThat(telemetry.meterNames()).contains("opensearch.bulk.rejected", "opensearch.bulk.retries");
            assertThat(telemetry.everyTagValue()).noneMatch(value -> value.contains(document.note()));
            assertThat(telemetry.everyTagValue()).noneMatch(value -> value.contains("doc-1"));
            assertThat(telemetry.everyKeyValue()).noneMatch(value -> value.contains(document.note()));
        }
    }

    // ---------------------------------------------------------------- fixtures

    private BulkDocumentIndexer<FakeDocument> indexer() {
        return indexer(fastRetries(3));
    }

    private BulkDocumentIndexer<FakeDocument> indexer(RetryConfig retries) {
        return new BulkDocumentIndexer<>(client, LAB_RESULTS, resolver,
                new WriteDocuments<>(resolver, secureMapper), retries, OpenSearchObservations.noop());
    }

    /**
     * The production shape of the retry policy with the waiting taken out: same retryable outcome
     * (documents still pending), same retryable exception (a {@code 429} anywhere in the cause
     * chain), same refusal to throw after the last attempt. 10 ms is resilience4j's minimum interval.
     */
    private static RetryConfig fastRetries(int maxAttempts) {
        return RetryConfig.<List<?>>custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(10)))
                .retryOnResult(pending -> !pending.isEmpty())
                .retryOnException(BulkDocumentIndexerTest::wrapsTooManyRequests)
                .failAfterMaxAttempts(false)
                .build();
    }

    private static boolean wrapsTooManyRequests(Throwable thrown) {
        for (Throwable cause = thrown; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof OpenSearchException ose && ose.status() == 429) {
                return true;
            }
        }
        return false;
    }

    private static List<FakeDocument> batchOf(String tenantId, int size) {
        List<FakeDocument> batch = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            batch.add(FakeDocument.of(tenantId, "doc-" + i));
        }
        return batch;
    }

    private List<String> indexesSwitchedOff() {
        return settingsIndexes(BulkDocumentIndexerTest::switchesOff);
    }

    private List<String> indexesRestored() {
        return settingsIndexes(BulkDocumentIndexerTest::restores);
    }

    private List<String> settingsIndexes(Predicate<PutIndicesSettingsRequest> which) {
        return transport.putSettingsRequests().stream()
                .filter(which)
                .flatMap(request -> request.index().stream())
                .toList();
    }

    private org.opensearch.client.opensearch.indices.IndexSettings settingsFor(
            String index, Predicate<PutIndicesSettingsRequest> which) {
        return transport.putSettingsRequests().stream()
                .filter(which)
                .filter(request -> request.index().contains(index))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no settings call matched for " + index))
                .settings();
    }

    private static boolean switchesOff(PutIndicesSettingsRequest request) {
        return "-1".equals(refreshInterval(request));
    }

    private static boolean restores(PutIndicesSettingsRequest request) {
        return "1s".equals(refreshInterval(request));
    }

    private static String refreshInterval(PutIndicesSettingsRequest request) {
        Time interval = request.settings().refreshInterval();
        return interval == null ? null : interval.time();
    }

    private static InMemoryTenantCatalog catalog() {
        return new InMemoryTenantCatalog(DOMAINS, domain -> POOLS, List.of(
                pooled(LAB_RESULTS, TENANT_A, PrivacyLevel.NORMAL, "lab-results-pool-1"),
                pooled(LAB_RESULTS, TENANT_B, PrivacyLevel.NORMAL, "lab-results-pool-2"),
                pooled(LAB_RESULTS, HIGH_TENANT, PrivacyLevel.HIGH, "lab-results-secure-pool-3"),
                pooled(LAB_RESULTS, KEYLESS_TENANT, PrivacyLevel.HIGH, "lab-results-secure-pool-3"),
                pooled(LAB_RESULTS, SHARED_ID, PrivacyLevel.NORMAL, "lab-results-pool-1"),
                pooled(ORDERS, SHARED_ID, PrivacyLevel.NORMAL, "orders-pool-1")));
    }

    private static Placement pooled(SearchDomain domain, String tenantId, PrivacyLevel level, String pool) {
        return new Placement(
                TenantRef.of(domain, tenantId), Tier.POOLED, pool + "-write", List.of(pool),
                true, MigrationState.STABLE, level);
    }
}
