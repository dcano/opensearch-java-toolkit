package io.twba.search.toolkit.opensearch;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The lazily-hashing repository behind {@link TieredTenantIndexResolver}. The kata hid its pool
 * count in a constant on the resolver; here it is asked for per domain, because two domains sharing
 * one repository may be provisioned with different pool counts, and placing with the wrong one
 * routes a tenant to an index that does not exist.
 *
 * <p>Pool numbers are {@code floorMod(tenantId.hashCode(), count)}, from the tenant id alone so a
 * tenant lands where the kata put it: {@code tenant-a} is 2 of 4, {@code tenant-b} is 3 of 4 and
 * 1 of 2, {@code tenant-new} (negative hash) is 1 of 4.
 */
class InMemoryTenantPlacementCatalogRepositoryTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");
    private static final SearchDomains DOMAINS = SearchDomains.of(LAB_RESULTS, ORDERS);

    @Test
    @DisplayName("an unknown tenant is placed NORMAL and pooled, in the kata's pool")
    void unknownTenantIsPooledNormalInTheKatasPool() {
        TenantPlacement placement = new InMemoryTenantPlacementCatalogRepository(DOMAINS, 4)
                .retrieve(TenantRef.of(LAB_RESULTS, "tenant-a"));

        assertThat(placement.tier()).isEqualTo(Tier.POOLED);
        assertThat(placement.privacyLevel()).isEqualTo(PrivacyLevel.NORMAL);
        assertThat(placement.poolNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("a tenant id with a negative hash still gets a non-negative pool")
    void negativeHashGivesNonNegativePool() {
        // A plain % would return -3 here and mint "lab-results-pool--3".
        assertThat(new InMemoryTenantPlacementCatalogRepository(DOMAINS, 4)
                .retrieve(TenantRef.of(LAB_RESULTS, "tenant-new")).poolNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("resolves through the tiered resolver to the kata's literal names")
    void resolvesToTheKatasNames() {
        TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
        TieredTenantIndexResolver resolver =
                new TieredTenantIndexResolver(new InMemoryTenantPlacementCatalogRepository(DOMAINS, 4), DOMAINS);

        assertThat(resolver.writeIndex(tenant)).isEqualTo("lab-results-pool-2-write");
        assertThat(resolver.routing(tenant)).contains("tenant-a");
    }

    @Test
    @DisplayName("each domain is placed with its own pool count")
    void eachDomainUsesItsOwnPoolCount() {
        InMemoryTenantPlacementCatalogRepository repository =
                new InMemoryTenantPlacementCatalogRepository(DOMAINS, d -> d.equals(ORDERS) ? 2 : 4);

        TenantPlacement inLabResults = repository.retrieve(TenantRef.of(LAB_RESULTS, "tenant-b"));
        TenantPlacement inOrders = repository.retrieve(TenantRef.of(ORDERS, "tenant-b"));

        assertThat(inLabResults.poolNumber()).isEqualTo(3);
        assertThat(inOrders.poolNumber()).isEqualTo(1);
        assertThat(inOrders.tenant()).isEqualTo(TenantRef.of(ORDERS, "tenant-b"));
    }

    @Test
    @DisplayName("a zero pool count fails with an IllegalStateException naming the domain")
    void zeroPoolCountNamesTheDomain() {
        InMemoryTenantPlacementCatalogRepository repository =
                new InMemoryTenantPlacementCatalogRepository(DOMAINS, d -> d.equals(ORDERS) ? 0 : 4);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> repository.retrieve(TenantRef.of(ORDERS, "tenant-b")))
                .withMessageContaining("orders");
        // The misconfigured domain does not take the healthy one down with it.
        assertThat(repository.retrieve(TenantRef.of(LAB_RESULTS, "tenant-b")).poolNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("a negative pool count is refused the same way")
    void negativePoolCountIsRefused() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new InMemoryTenantPlacementCatalogRepository(DOMAINS, -1)
                        .retrieve(TenantRef.of(LAB_RESULTS, "tenant-b")))
                .withMessageContaining("lab-results");
    }

    @Test
    @DisplayName("spec: a tenant in an undeclared domain is refused, naming the domain, rather than lazily placed")
    void undeclaredDomainIsRefused() {
        // Lazy placement is the danger: an undeclared domain was never checked for overlap with the
        // declared ones, so a placement recorded for it could name another domain's pools.
        InMemoryTenantPlacementCatalogRepository repository =
                new InMemoryTenantPlacementCatalogRepository(SearchDomains.of(LAB_RESULTS), 4);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> repository.retrieve(TenantRef.of(ORDERS, "tenant-a")))
                .withMessageMatching("(?s).*\\borders\\b.*");
    }

    @Test
    @DisplayName("a placement is remembered: a later pool-count answer does not move a placed tenant")
    void placementIsRemembered() {
        // Pinning a tenant on first contact is what makes a later change of pool count safe for
        // tenants already placed; re-hashing on every read would move them without their data.
        int[] calls = {0};
        InMemoryTenantPlacementCatalogRepository repository = new InMemoryTenantPlacementCatalogRepository(DOMAINS, d -> {
            calls[0]++;
            return calls[0] == 1 ? 4 : 2;
        });
        TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-b");

        TenantPlacement first = repository.retrieve(tenant);
        TenantPlacement second = repository.retrieve(tenant);

        assertThat(second).isEqualTo(first);
        assertThat(second.poolNumber()).isEqualTo(3);
    }
}
