package io.twba.search.toolkit.opensearch.dp;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexRequest;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The single-document write, checked by looking at the request that reached the transport.
 *
 * <p>Four things about that request are contractual, and each has a failure mode worth naming:
 * <ul>
 *   <li><b>{@code _id} is the document's own id.</b> Anything else — a generated id, a hash — turns
 *       re-ingesting the same source message into a duplicate document, which no later read can
 *       tell from two real events.</li>
 *   <li><b>the index is the resolver's write target.</b> The resolver is the only place topology is
 *       known; an index composed anywhere else is how a tenant's documents end up in the wrong
 *       family or the wrong domain.</li>
 *   <li><b>routing is the resolver's routing.</b> Dropped, a pooled tenant's documents scatter over
 *       every shard and its single-shard searches stop finding them.</li>
 *   <li><b>the payload is the one {@link WriteDocuments} chose.</b> Not a re-derived shape.</li>
 * </ul>
 *
 * <p>Placements are seeded with explicit targets so the expected index names are literals here
 * rather than something re-derived from the code under test.
 */
class DocumentIndexerTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomains DOMAINS = SearchDomains.of(LAB_RESULTS);
    private static final int POOLS = 4;

    private static final String POOLED_TENANT = "tenant-pooled";
    private static final String DEDICATED_TENANT = "tenant-dedicated";
    private static final String HIGH_TENANT = "tenant-high";
    private static final String KEYLESS_TENANT = "tenant-keyless";

    private final RecordingOpenSearchTransport transport = new RecordingOpenSearchTransport();
    private final OpenSearchClient client = new OpenSearchClient(transport);
    private final TenantIndexResolver resolver = new CatalogTenantIndexResolver(catalog(), DOMAINS);
    private final CountingSecureMapper secureMapper = new CountingSecureMapper(Set.of(KEYLESS_TENANT));

    private final DocumentIndexer<FakeDocument> indexer =
            new DocumentIndexer<>(client, LAB_RESULTS, resolver, new WriteDocuments<>(resolver, secureMapper));

    @Nested
    @DisplayName("the request it builds")
    class TheRequest {

        @Test
        @DisplayName("addresses the resolver's write index with the document's own id and the tenant's routing")
        void addressesTheResolvedTarget() throws IOException {
            FakeDocument document = FakeDocument.of(POOLED_TENANT, "doc-1");

            indexer.index(document);

            IndexRequest<?> request = onlyRequest();
            assertThat(request.index()).isEqualTo("lab-results-pool-1-write");
            assertThat(request.id()).isEqualTo("doc-1");
            assertThat(request.routing()).isEqualTo(POOLED_TENANT);
            assertThat(request.document()).isSameAs(document);
        }

        @Test
        @DisplayName("sends no routing for a dedicated tenant, which has a whole index to itself")
        void dedicatedTenantIsUnrouted() throws IOException {
            indexer.index(FakeDocument.of(DEDICATED_TENANT, "doc-2"));

            IndexRequest<?> request = onlyRequest();
            assertThat(request.index()).isEqualTo("lab-results-tenant-dedicated-write");
            assertThat(request.routing()).isNull();
        }

        @Test
        @DisplayName("carries the sealed payload for a HIGH tenant, with nothing readable left in it")
        void highTenantSendsTheSealedPayload() throws IOException {
            FakeDocument document = FakeDocument.of(HIGH_TENANT, "doc-3");

            indexer.index(document);

            IndexRequest<?> request = onlyRequest();
            assertThat(request.index()).isEqualTo("lab-results-secure-pool-3-write");
            assertThat(request.document())
                    .isInstanceOf(CountingSecureMapper.SealedStub.class)
                    .isNotSameAs(document);
            // The note is the sentinel: if it is reachable from the payload, the plaintext document
            // leaked into the secure family whatever the mapping says.
            assertThat(String.valueOf(request.document())).doesNotContain(document.note());
        }

        @Test
        @DisplayName("reports the cluster's own result, so a re-send is visibly an update")
        void reportsTheClusterResult() throws IOException {
            FakeDocument document = FakeDocument.of(POOLED_TENANT, "doc-4");

            assertThat(indexer.index(document)).isEqualTo("created");
            assertThat(indexer.index(document)).isEqualTo("updated");

            // Same _id, same index: the second write replaced the first instead of adding to it.
            assertThat(transport.stored()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("failing closed")
    class FailingClosed {

        @Test
        @DisplayName("a HIGH tenant in a deployment with no secure mapper makes no cluster call at all")
        void highTenantWithoutMapperSendsNothing() {
            DocumentIndexer<FakeDocument> plaintextOnly = new DocumentIndexer<>(client, LAB_RESULTS, resolver);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> plaintextOnly.index(FakeDocument.of(HIGH_TENANT, "doc-5")))
                    .withMessageContaining(HIGH_TENANT);
            assertThat(transport.indexRequests()).isEmpty();
        }

        @Test
        @DisplayName("a HIGH tenant with no key material makes no cluster call at all")
        void keylessTenantSendsNothing() {
            assertThatExceptionOfType(KeyUnavailableException.class)
                    .isThrownBy(() -> indexer.index(FakeDocument.of(KEYLESS_TENANT, "doc-6")))
                    .withMessageContaining(KEYLESS_TENANT);
            // Shaped before the request is built: a keyless tenant leaves nothing half-written.
            assertThat(transport.indexRequests()).isEmpty();
            assertThat(transport.stored()).isEmpty();
        }
    }

    private IndexRequest<?> onlyRequest() {
        assertThat(transport.indexRequests()).hasSize(1);
        return transport.indexRequests().getFirst();
    }

    private static InMemoryTenantCatalog catalog() {
        return new InMemoryTenantCatalog(DOMAINS, domain -> POOLS, List.of(
                pooled(POOLED_TENANT, PrivacyLevel.NORMAL, "lab-results-pool-1"),
                pooled(HIGH_TENANT, PrivacyLevel.HIGH, "lab-results-secure-pool-3"),
                pooled(KEYLESS_TENANT, PrivacyLevel.HIGH, "lab-results-secure-pool-3"),
                new Placement(
                        TenantRef.of(LAB_RESULTS, DEDICATED_TENANT), Tier.DEDICATED,
                        "lab-results-tenant-dedicated-write", List.of("lab-results-tenant-dedicated"),
                        false, MigrationState.STABLE, PrivacyLevel.NORMAL)));
    }

    private static Placement pooled(String tenantId, PrivacyLevel level, String pool) {
        return new Placement(
                TenantRef.of(LAB_RESULTS, tenantId), Tier.POOLED, pool + "-write", List.of(pool),
                true, MigrationState.STABLE, level);
    }
}
