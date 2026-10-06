package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.KeyUnavailableException;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.cp.InMemoryTenantCatalog;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The one component that chooses between the application's document and its sealed counterpart.
 *
 * <p>Everything here is about a single question — <em>which object reaches the client</em> — because
 * the whole privacy posture reduces to it. Two properties are load-bearing:
 *
 * <ul>
 *   <li>{@code NORMAL} hands over the <em>same instance</em>. Asserted with identity rather than
 *       equality on purpose: an equal copy would pass an equality assertion while quietly proving
 *       that a second serialization step had appeared between the application and the cluster.</li>
 *   <li>Nothing, in any circumstance, downgrades a {@code HIGH} tenant to a plaintext write. A
 *       missing mapper, a missing key, a mismatched tenant — each raises. That is the only outcome
 *       that keeps "the secure family holds no readable value" true, and the failure it prevents
 *       looks like success from every angle except the index.</li>
 * </ul>
 */
class WriteDocumentsTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");
    private static final SearchDomains DOMAINS = SearchDomains.of(LAB_RESULTS, ORDERS);
    private static final int POOLS = 4;

    private static final String NORMAL_TENANT = "tenant-normal";
    private static final String HIGH_TENANT = "tenant-high";
    private static final String KEYLESS_TENANT = "tenant-keyless";
    /** Held by both domains, HIGH in one and NORMAL in the other — the level is per pair, not per id. */
    private static final String SHARED_ID = "tenant-shared";

    private final TenantIndexResolver resolver = new CatalogTenantIndexResolver(catalog(), DOMAINS);
    private final CountingSecureMapper secureMapper = new CountingSecureMapper(Set.of(KEYLESS_TENANT));
    private final WriteDocuments<FakeDocument> documents = new WriteDocuments<>(resolver, secureMapper);

    @Nested
    @DisplayName("the privacy branch")
    class PrivacyBranch {

        @Test
        @DisplayName("a NORMAL tenant writes the application's own document instance, not a copy")
        void normalWritesTheSameInstance() {
            FakeDocument document = FakeDocument.of(NORMAL_TENANT, "doc-1");

            Object payload = documents.forWrite(TenantRef.of(LAB_RESULTS, NORMAL_TENANT), document);

            assertThat(payload).isSameAs(document);
            assertThat(secureMapper.sealCount()).isZero();   // the plaintext path does not touch crypto
        }

        @Test
        @DisplayName("a HIGH tenant writes what the secure mapper produced, and nothing of the document itself")
        void highWritesTheSecureCounterpart() {
            FakeDocument document = FakeDocument.of(HIGH_TENANT, "doc-2");

            Object payload = documents.forWrite(TenantRef.of(LAB_RESULTS, HIGH_TENANT), document);

            assertThat(payload)
                    .isInstanceOf(CountingSecureMapper.SealedStub.class)
                    .isNotSameAs(document);
            assertThat(secureMapper.sealedDocumentIds()).containsExactly("doc-2");
        }

        @Test
        @DisplayName("the same tenant id gets a different shape in each domain")
        void theLevelIsPerDomainAndTenant() {
            FakeDocument document = FakeDocument.of(SHARED_ID, "doc-3");

            // SHARED_ID is seeded HIGH in orders and left to lazy NORMAL provisioning in lab-results.
            assertThat(documents.forWrite(TenantRef.of(LAB_RESULTS, SHARED_ID), document)).isSameAs(document);
            assertThat(documents.forWrite(TenantRef.of(ORDERS, SHARED_ID), document))
                    .isInstanceOf(CountingSecureMapper.SealedStub.class);
        }
    }

    @Nested
    @DisplayName("failing closed")
    class FailingClosed {

        @Test
        @DisplayName("a deployment with no secure mapper refuses a HIGH tenant, naming the tenant and the domain")
        void plaintextOnlyRefusesHighTenants() {
            WriteDocuments<FakeDocument> plaintextOnly = WriteDocuments.plaintextOnly(resolver);
            FakeDocument document = FakeDocument.of(HIGH_TENANT, "doc-4");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> plaintextOnly.forWrite(TenantRef.of(LAB_RESULTS, HIGH_TENANT), document))
                    .withMessageContaining(HIGH_TENANT)
                    .withMessageContaining(LAB_RESULTS.name());
        }

        @Test
        @DisplayName("that same deployment still writes NORMAL tenants unchanged")
        void plaintextOnlyStillServesNormalTenants() {
            WriteDocuments<FakeDocument> plaintextOnly = WriteDocuments.plaintextOnly(resolver);
            FakeDocument document = FakeDocument.of(NORMAL_TENANT, "doc-5");

            assertThat(plaintextOnly.forWrite(TenantRef.of(LAB_RESULTS, NORMAL_TENANT), document))
                    .isSameAs(document);
        }

        @Test
        @DisplayName("a HIGH tenant with no key material raises instead of being sealed or written")
        void missingKeyMaterialPropagates() {
            FakeDocument document = FakeDocument.of(KEYLESS_TENANT, "doc-6");

            // The mapper's failure must escape unchanged. Swallowed — or turned into an empty shape —
            // it becomes a document in the secure family with no recoverable value in it.
            assertThatExceptionOfType(KeyUnavailableException.class)
                    .isThrownBy(() -> documents.forWrite(TenantRef.of(LAB_RESULTS, KEYLESS_TENANT), document))
                    .withMessageContaining(KEYLESS_TENANT);
        }

        @Test
        @DisplayName("a document addressed as another tenant is refused before any sealing happens")
        void tenantMismatchIsRefused() {
            FakeDocument foreign = FakeDocument.of(NORMAL_TENANT, "doc-7");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> documents.forWrite(TenantRef.of(LAB_RESULTS, HIGH_TENANT), foreign))
                    .withMessageContaining(NORMAL_TENANT)
                    .withMessageContaining(HIGH_TENANT);
            // Checked before the branch, so the wrong tenant's key never touches this document.
            assertThat(secureMapper.sealCount()).isZero();
        }
    }

    private static TenantCatalog catalog() {
        return new InMemoryTenantCatalog(DOMAINS, domain -> POOLS, List.of(
                InMemoryTenantCatalog.pooled(TenantRef.of(LAB_RESULTS, HIGH_TENANT), PrivacyLevel.HIGH, POOLS),
                InMemoryTenantCatalog.pooled(TenantRef.of(LAB_RESULTS, KEYLESS_TENANT), PrivacyLevel.HIGH, POOLS),
                InMemoryTenantCatalog.pooled(TenantRef.of(ORDERS, SHARED_ID), PrivacyLevel.HIGH, POOLS)));
        // NORMAL_TENANT and (lab-results, SHARED_ID) are unseeded: lazy provisioning defaults them to NORMAL.
    }
}
