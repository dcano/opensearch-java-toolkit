package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.BulkStats;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.obs.OpenSearchObservations;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recorder the "nothing sensitive in telemetry" check hunts through.
 *
 * <p>That check is only as good as this class's completeness. It works by asserting that a sentinel
 * appears <em>nowhere</em> in the published surface, which is a strong assertion over a surface that
 * is fully enumerated and a vacuous one over a surface with somewhere to hide. So the tests below
 * are mostly about coverage of the surface rather than about any individual value: meter names, tag
 * keys, tag values, observation names, contextual names, and key values of <em>both</em>
 * cardinalities.
 *
 * <p>The high-cardinality half is where a real leak would sit. The tenant id, shard counts and hit
 * counts are span-only by design, so a recorder that enumerated low-cardinality key values alone
 * would report a clean surface while the span carried a patient's name.
 */
class RecordedTelemetryTest {

    private static final SearchDomain DOMAIN = new SearchDomain("conformance");
    private static final TenantRef TENANT = TenantRef.of(DOMAIN, "tenant-alpha");
    private static final String OPERATION = "conformanceSearch";
    private static final String INDEX = "conformance-pool-0";
    private static final String PRIVACY = "NORMAL";

    private final RecordedTelemetry telemetry = new RecordedTelemetry();

    @Nested
    @DisplayName("the published surface")
    class PublishedSurface {

        @Test
        @DisplayName("carries every meter's name and every tag key and value")
        void carriesMeterNamesAndTags() throws IOException {
            search(OPERATION);

            assertThat(telemetry.everythingPublished())
                    .contains("opensearch.search.took", "opensearch.search.hits", "opensearch.search.shards")
                    .contains("domain", DOMAIN.name())
                    .contains("operation", OPERATION)
                    .contains("index", INDEX)
                    .contains("privacy", PRIVACY);
        }

        @Test
        @DisplayName("carries the observation's name and the span name")
        void carriesObservationAndSpanNames() throws IOException {
            search(OPERATION);

            assertThat(telemetry.everythingPublished())
                    .contains(OpenSearchObservations.SEARCH_OBSERVATION)
                    .contains("opensearch " + OPERATION);
        }

        @Test
        @DisplayName("carries high-cardinality key values, which is where a leak would sit")
        void carriesHighCardinalityKeyValues() throws IOException {
            // The span-only half. A recorder that enumerated low-cardinality key values alone would
            // report a clean surface while the span carried the tenant id, and the conformance
            // check built on it would pass for a system that leaks.
            search(OPERATION);

            assertThat(telemetry.everythingPublished())
                    .contains("tenant.id", TENANT.tenantId())
                    .contains("db.system", "opensearch");
        }

        @Test
        @DisplayName("carries key values added after the observation started")
        void carriesKeyValuesAddedDuringTheCall() throws IOException {
            // Contexts are captured on start, but hit counts and shard counts are attached while the
            // call runs. A recorder that copied the key values at start instead of holding the
            // context would miss every one of them — and those are precisely the values derived
            // from the response.
            search(OPERATION);

            assertThat(telemetry.everythingPublished())
                    .contains("opensearch.hits.total", "2")
                    .contains("opensearch.shards.total", "1");
        }

        @Test
        @DisplayName("keeps every observation, not only the last")
        void keepsEveryObservation() throws IOException {
            search("firstOperation");
            search("secondOperation");

            assertThat(telemetry.everythingPublished())
                    .contains("opensearch firstOperation")
                    .contains("opensearch secondOperation");
        }

        @Test
        @DisplayName("a sentinel that reached a meter tag is found")
        void aLeakIntoAMeterTagIsFound() throws IOException {
            // The failure this whole apparatus exists to catch, reproduced from the outside: an
            // operation name derived from user input becomes a meter tag and a span attribute. If
            // this assertion can find it here, the conformance check can find it there.
            search("conformance-query-sentinel");

            assertThat(telemetry.everythingPublished())
                    .anySatisfy(value -> assertThat(value).contains("conformance-query-sentinel"));
        }
    }

    @Nested
    @DisplayName("the meter tag surface on its own")
    class MeterTagSurface {

        @Test
        @DisplayName("holds the low-cardinality values and not the tenant id")
        void holdsLowCardinalityValuesOnly() throws IOException {
            // The split the toolkit promises: per-tenant time series are how a shared cluster grows
            // thousands of series per metric, so the tenant id must be on the span alone.
            search(OPERATION);

            assertThat(telemetry.everyMeterTagValue())
                    .contains(DOMAIN.name(), OPERATION, INDEX, PRIVACY)
                    .doesNotContain(TENANT.tenantId());
        }

        @Test
        @DisplayName("is empty before anything is published")
        void isEmptyBeforeAnythingIsPublished() {
            assertThat(telemetry.everyMeterTagValue()).isEmpty();
            assertThat(telemetry.everythingPublished()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the key values of one observation")
    class KeyValuesOfOneObservation {

        @Test
        @DisplayName("are those of the named observation only")
        void areScopedToTheNamedObservation() throws IOException {
            search(OPERATION);
            telemetry.observations().bulk(DOMAIN, 3, () -> new BulkStats(3, 0, 11L));

            assertThat(telemetry.keyValuesOf(OpenSearchObservations.SEARCH_OBSERVATION))
                    .contains(TENANT.tenantId(), OPERATION, INDEX)
                    .as("a bulk observation's document count belongs to the bulk span, not the search one")
                    .doesNotContain("3");
            assertThat(telemetry.keyValuesOf(OpenSearchObservations.BULK_OBSERVATION))
                    .contains("3")
                    .doesNotContain(TENANT.tenantId());
        }

        @Test
        @DisplayName("are empty for an observation that never happened")
        void areEmptyForAnUnknownObservation() {
            assertThat(telemetry.keyValuesOf("opensearch.never.happened")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void search(String operation) throws IOException {
        telemetry.observations().search(DOMAIN, operation, TENANT, INDEX, PRIVACY,
                RecordedTelemetryTest::twoHits);
    }

    private static SearchResponse<String> twoHits() {
        return SearchResponse.searchResponseOf(r -> r
                .took(7L)
                .timedOut(false)
                .shards(ShardStatistics.of(s -> s.total(1).successful(1).skipped(0).failed(0)))
                .hits(HitsMetadata.of(h -> h
                        .total(t -> t.value(2L).relation(TotalHitsRelation.Eq))
                        .hits(List.of(
                                Hit.of(hit -> hit.index(INDEX).id("doc-1").score(1.0).source("a document")),
                                Hit.of(hit -> hit.index(INDEX).id("doc-2").score(0.5).source("another")))))));
    }
}
