package io.twba.search.toolkit.opensearch.obs;

import io.twba.search.toolkit.BulkStats;
import io.twba.search.toolkit.SearchDomain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The write half of the observation surface.
 *
 * <p>Two requirements meet here. The first is that {@code domain} is a low-cardinality tag on every
 * bulk meter: a shared cluster now serves several applications, and without the tag one domain's
 * backpressure spike and another's are one undifferentiated line — which makes the first question
 * anyone asks during an incident unanswerable from the dashboard.
 *
 * <p>The second is that nothing sensitive is published. That is asserted here over the whole set of
 * meters and key values rather than over the ones this test expects, so a new tag carrying something
 * it should not is caught by the same assertion.
 */
class OpenSearchObservationsTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");

    private final RecordingTelemetry telemetry = new RecordingTelemetry();
    private final OpenSearchObservations observations = telemetry.observations();

    @Nested
    @DisplayName("a completed bulk load")
    class CompletedBulk {

        @Test
        @DisplayName("counts the outcome per domain and records the cluster's own took time")
        void countsOutcomesAndTiming() throws IOException {
            BulkStats stats = observations.bulk(LAB_RESULTS, 10, () -> new BulkStats(8, 2, 42L));

            assertThat(stats).isEqualTo(new BulkStats(8, 2, 42L));
            assertThat(counter("opensearch.bulk.documents", "lab-results", "succeeded")).isEqualTo(8.0);
            assertThat(counter("opensearch.bulk.documents", "lab-results", "failed")).isEqualTo(2.0);
            assertThat(telemetry.meters().get("opensearch.bulk.took").tag("domain", "lab-results")
                    .timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(42.0);
        }

        @Test
        @DisplayName("keeps two domains' loads on separate series")
        void domainsDoNotShareASeries() throws IOException {
            observations.bulk(LAB_RESULTS, 3, () -> new BulkStats(3, 0, 5L));
            observations.bulk(ORDERS, 7, () -> new BulkStats(7, 0, 9L));

            // Drop the domain tag and these two collapse into one counter of 10, which is the
            // number nobody can act on.
            assertThat(counter("opensearch.bulk.documents", "lab-results", "succeeded")).isEqualTo(3.0);
            assertThat(counter("opensearch.bulk.documents", "orders", "succeeded")).isEqualTo(7.0);
        }

        @Test
        @DisplayName("names the domain on the span and carries the document counts as high cardinality")
        void theSpanCarriesTheDomainAndTheCounts() throws IOException {
            observations.bulk(LAB_RESULTS, 4, () -> new BulkStats(4, 0, 11L));

            assertThat(telemetry.keyValuesOf(OpenSearchObservations.BULK_OBSERVATION))
                    .anySatisfy(keyValue -> {
                        assertThat(keyValue.getKey()).isEqualTo("domain");
                        assertThat(keyValue.getValue()).isEqualTo("lab-results");
                    });
            assertThat(telemetry.everyKeyValue()).contains("4");   // documents, then succeeded
        }

        @Test
        @DisplayName("a failing bulk load still stops its observation and lets the failure through")
        void aFailureIsNotSwallowed() {
            assertThatExceptionOfType(IOException.class)
                    .isThrownBy(() -> observations.bulk(LAB_RESULTS, 1, () -> {
                        throw new IOException("the cluster went away");
                    }));

            assertThat(telemetry.contexts()).hasSize(1);
            assertThat(telemetry.contexts().getFirst().getError()).isInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("backpressure signals")
    class Backpressure {

        @Test
        @DisplayName("separates a whole-request rejection from per-item rejections, per domain")
        void rejectionScopeIsTagged() {
            observations.bulkRejection(LAB_RESULTS, "request", 500);
            observations.bulkRejection(LAB_RESULTS, "item", 3);

            assertThat(telemetry.meters().get("opensearch.bulk.rejected")
                    .tags("domain", "lab-results", "scope", "request").counter().count()).isEqualTo(500.0);
            assertThat(telemetry.meters().get("opensearch.bulk.rejected")
                    .tags("domain", "lab-results", "scope", "item").counter().count()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("records the attempt number and the chunk size it shrank to")
        void retriesRecordTheAttemptAndChunkSize() {
            observations.bulkRetry(LAB_RESULTS, 1, 500);
            observations.bulkRetry(LAB_RESULTS, 2, 250);

            assertThat(telemetry.meters().get("opensearch.bulk.retries")
                    .tags("domain", "lab-results", "attempt", "2").counter().count()).isEqualTo(1.0);
            // The shrinking is the interesting part of a backpressure incident: a chunk that keeps
            // halving says the cluster is still refusing work.
            assertThat(telemetry.meters().get("opensearch.bulk.chunk.size")
                    .tag("domain", "lab-results").summary().max()).isEqualTo(500.0);
        }
    }

    @Nested
    @DisplayName("what is not published")
    class Secrecy {

        @Test
        @DisplayName("every tag value published is a domain name or a fixed vocabulary word")
        void tagsCarryNothingButLowCardinalityVocabulary() throws IOException {
            // Deliberately distinctive counts: 11, 13, 17 and 50 appear in no legitimate tag, so a
            // document count that found its way onto one shows up as a value outside the closed set.
            observations.bulk(LAB_RESULTS, 11, () -> new BulkStats(11, 13, 17L));
            observations.bulkRejection(LAB_RESULTS, "item", 17);
            observations.bulkRetry(LAB_RESULTS, 3, 50);

            // A closed set. Anything else — a tenant id, a document id, a field value, a per-call
            // count — would make the series count grow with the data, and some of those values must
            // never be stored outside the cluster at all. "3" is the retry attempt, which is bounded
            // by the retry policy.
            assertThat(telemetry.everyTagValue())
                    .isSubsetOf("lab-results", "succeeded", "failed", "item", "request", "3");
        }

        @Test
        @DisplayName("no meter is created per tenant: the bulk half never sees a tenant id")
        void noTenantIdReachesAMeter() throws IOException {
            observations.bulk(LAB_RESULTS, 1, () -> new BulkStats(1, 0, 1L));

            assertThat(telemetry.everyTagValue()).doesNotContain("tenant-a");
            assertThat(telemetry.meterNames()).allMatch(name -> name.startsWith("opensearch.bulk."));
        }
    }

    @Nested
    @DisplayName("the no-op instance")
    class NoOp {

        @Test
        @DisplayName("still runs the call and returns its stats")
        void noopStillExecutesTheCall() throws IOException {
            BulkStats stats = OpenSearchObservations.noop()
                    .bulk(LAB_RESULTS, 1, () -> new BulkStats(1, 0, 2L));

            assertThat(stats).isEqualTo(new BulkStats(1, 0, 2L));
        }
    }

    private double counter(String name, String domain, String outcome) {
        return telemetry.meters().get(name).tags("domain", domain, "outcome", outcome).counter().count();
    }
}
