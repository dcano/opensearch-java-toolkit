package io.twba.search.toolkit.opensearch.obs;

import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.testlog.RecordedLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.ShardStatistics;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The read half of the observation surface, a sibling of {@link OpenSearchObservationsTest} rather
 * than an addition to it: the bulk half was written against a different requirement and is green,
 * and the two halves share nothing but the class under test.
 *
 * <p>The load-bearing property is the split. Four low-cardinality values — domain, operation, index,
 * privacy — become meter tags, and everything that varies per request stays on the span. Get it
 * wrong in one direction and a shared cluster grows a time series per tenant per meter; get it wrong
 * in the other and the first question of any incident ("which application?") has no answer.
 *
 * <p>The tenant id is the case that matters, because {@code search} is handed one. It must reach the
 * span and neither a meter nor a log line.
 */
class SearchObservationsTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");

    private static final TenantRef TENANT = TenantRef.of(LAB_RESULTS, "clinic-a");
    private static final String OPERATION = "search-notes";
    private static final String INDEX = "lab-results-pool-1";
    private static final String PRIVACY = "HIGH";

    private final RecordingTelemetry telemetry = new RecordingTelemetry();
    private final OpenSearchObservations observations = telemetry.observations();

    @BeforeEach
    void clearLogs() {
        RecordedLogs.clear();
    }

    @Nested
    @DisplayName("a completed search")
    class CompletedSearch {

        @Test
        @DisplayName("returns the cluster's response untouched")
        void theResponseIsHandedBack() throws IOException {
            SearchResponse<String> response = responseWith(2, 131L, healthyShards());

            assertThat(observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY, () -> response))
                    .isSameAs(response);
        }

        @Test
        @DisplayName("records the cluster's own took time and hit count against all four tags")
        void tookAndHitsAreRecorded() throws IOException {
            observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY,
                    () -> responseWith(2, 131L, healthyShards()));

            // Cluster-reported took, i.e. latency without the network and deserialization the
            // observation timer includes; the gap between the two is the client's own overhead.
            assertThat(searchMeter("opensearch.search.took").timer()
                    .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(131.0);
            assertThat(searchMeter("opensearch.search.hits").summary().max()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("records the shard fan-out and what routing pruned")
        void shardFanOutIsRecorded() throws IOException {
            ShardStatistics shards = ShardStatistics.of(s -> s.total(9).successful(9).skipped(6).failed(0));

            observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY,
                    () -> responseWith(1, 4L, shards));

            // Skipped shards are the payoff of per-tenant routing: without this meter, nobody can
            // tell whether routing is doing anything.
            assertThat(searchMeter("opensearch.search.shards").summary().max()).isEqualTo(9.0);
            assertThat(searchMeter("opensearch.search.shards.skipped").summary().max()).isEqualTo(6.0);
        }

        @Test
        @DisplayName("keeps two domains on separate series")
        void domainsDoNotShareASeries() throws IOException {
            observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY,
                    () -> responseWith(1, 3L, healthyShards()));
            observations.search(ORDERS, OPERATION, TenantRef.of(ORDERS, "clinic-a"), "orders-pool-1", "NORMAL",
                    () -> responseWith(1, 9L, healthyShards()));

            assertThat(searchMeter("opensearch.search.took").timer()
                    .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(3.0);
            assertThat(telemetry.meters().get("opensearch.search.took")
                    .tags("domain", "orders", "operation", OPERATION, "index", "orders-pool-1",
                            "privacy", "NORMAL")
                    .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(9.0);
        }

        @Test
        @DisplayName("a response with no total falls back to the hits it actually carried")
        void anAbsentTotalFallsBackToTheHits() throws IOException {
            SearchResponse<String> noTotal = SearchResponse.searchResponseOf(r -> r
                    .took(1L).timedOut(false).shards(healthyShards())
                    .hits(HitsMetadata.of(h -> h.hits(List.of(hit("doc-1"), hit("doc-2"))))));

            observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY, () -> noTotal);

            assertThat(searchMeter("opensearch.search.hits").summary().max()).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("what the cluster reported going wrong")
    class Trouble {

        @Test
        @DisplayName("a partial result is counted, because partial results look like missing data")
        void aTimedOutSearchIsCounted() throws IOException {
            SearchResponse<String> timedOut = SearchResponse.searchResponseOf(r -> r
                    .took(30_000L).timedOut(true).shards(healthyShards())
                    .hits(HitsMetadata.of(h -> h.total(t -> t.value(1).relation(TotalHitsRelation.Eq))
                            .hits(List.of(hit("doc-1"))))));

            observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY, () -> timedOut);

            assertThat(searchMeter("opensearch.search.timed.out").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("shard-level failures inside a successful search are counted")
        void shardFailuresAreCounted() throws IOException {
            ShardStatistics partial = ShardStatistics.of(s -> s.total(5).successful(4).skipped(0).failed(1));

            observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY,
                    () -> responseWith(1, 2L, partial));

            // A search that quietly searched four shards out of five returns fewer results and a
            // 200; this counter is the only thing that says so.
            assertThat(searchMeter("opensearch.search.shards.failed").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a failing search records the error on the span and lets it through")
        void aFailureIsNotSwallowed() {
            assertThatExceptionOfType(IOException.class)
                    .isThrownBy(() -> observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY,
                            () -> {
                                throw new IOException("the cluster went away");
                            }));

            assertThat(telemetry.contexts()).hasSize(1);
            assertThat(telemetry.contexts().getFirst().getError()).isInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("the throttle counter")
    class Throttling {

        @Test
        @DisplayName("counts refused probes per domain and operation")
        void throttledProbesAreCountedPerDomainAndOperation() {
            observations.identifierSearchThrottled(LAB_RESULTS, OPERATION);
            observations.identifierSearchThrottled(LAB_RESULTS, OPERATION);
            observations.identifierSearchThrottled(ORDERS, OPERATION);

            assertThat(telemetry.meters().get("opensearch.search.identifier.throttled")
                    .tags("domain", "lab-results", "operation", OPERATION).counter().count()).isEqualTo(2.0);
            assertThat(telemetry.meters().get("opensearch.search.identifier.throttled")
                    .tags("domain", "orders", "operation", OPERATION).counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("carries no tenant: who was throttled belongs on the audit record")
        void theThrottleCounterNamesNoTenant() {
            observations.identifierSearchThrottled(LAB_RESULTS, OPERATION);

            // This counter answers "is anyone hitting the limit" for a dashboard; the audit trail,
            // which has the retention and the access controls for it, answers "who".
            assertThat(telemetry.everyTagValue()).containsExactlyInAnyOrder("lab-results", OPERATION);
        }
    }

    @Nested
    @DisplayName("what is not published")
    class Secrecy {

        @Test
        @DisplayName("the tenant id reaches the span and no meter")
        void theTenantIdIsOnTheSpanOnly() throws IOException {
            observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY,
                    () -> responseWith(1, 3L, healthyShards()));

            assertThat(telemetry.keyValuesOf(OpenSearchObservations.SEARCH_OBSERVATION))
                    .anySatisfy(keyValue -> {
                        assertThat(keyValue.getKey()).isEqualTo("tenant.id");
                        assertThat(keyValue.getValue()).isEqualTo("clinic-a");
                    });
            assertThat(telemetry.everyTagValue()).doesNotContain("clinic-a");
        }

        @Test
        @DisplayName("the per-search log line names the domain and index, and no tenant")
        void theLogLineNamesNoTenant() throws IOException {
            observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY,
                    () -> responseWith(1, 3L, healthyShards()));

            assertThat(RecordedLogs.messages()).singleElement().satisfies(line -> {
                assertThat(line).contains("lab-results", OPERATION, INDEX, PRIVACY);
                // Counts and identifiers only. A tenant id here would put a per-tenant fact into
                // the application log, which has neither the retention nor the access controls
                // the audit trail has.
                assertThat(line).doesNotContain("clinic-a");
            });
        }

        @Test
        @DisplayName("every published tag value comes from a closed set")
        void tagsCarryNothingButLowCardinalityVocabulary() throws IOException {
            // 997 hits and 131 ms are deliberately distinctive: a per-request number that found its
            // way onto a tag would appear here as a value outside the closed set.
            observations.search(LAB_RESULTS, OPERATION, TENANT, INDEX, PRIVACY,
                    () -> responseWith(997, 131L, ShardStatistics.of(s -> s.total(9).successful(8).skipped(6).failed(1))));

            assertThat(telemetry.everyTagValue())
                    .isSubsetOf("lab-results", OPERATION, INDEX, PRIVACY);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private io.micrometer.core.instrument.search.RequiredSearch searchMeter(String name) {
        return telemetry.meters().get(name)
                .tags("domain", "lab-results", "operation", OPERATION, "index", INDEX, "privacy", PRIVACY);
    }

    private static ShardStatistics healthyShards() {
        return ShardStatistics.of(s -> s.total(1).successful(1).skipped(0).failed(0));
    }

    private static SearchResponse<String> responseWith(long totalHits, long tookMillis, ShardStatistics shards) {
        return SearchResponse.searchResponseOf(r -> r
                .took(tookMillis)
                .timedOut(false)
                .shards(shards)
                .hits(HitsMetadata.of(h -> h
                        .total(t -> t.value(totalHits).relation(TotalHitsRelation.Eq))
                        .hits(List.of(hit("doc-1"))))));
    }

    private static Hit<String> hit(String id) {
        return Hit.of(h -> h.index(INDEX).id(id).score(1.0).source("a document"));
    }
}
