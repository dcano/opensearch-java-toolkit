package io.twba.search.toolkit.opensearch.cp;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.MigrationState;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Placement;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Tier;
import io.twba.search.toolkit.opensearch.dp.CatalogTenantIndexResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Moving a hot tenant to a quieter pool is the control plane's cheapest rebalancing move, and the
 * kata never tested it. What it must guarantee: new writes go to the new pool, reads span new and
 * old until the old generation ages out, routing survives (both are routed pools), and the move
 * never changes the tenant's privacy family or domain.
 *
 * <p>The toolkit's reassigner takes a pool <em>number</em>. The kata took a pool name, which let a
 * caller hand it {@code "secure-pool-1"} for a NORMAL tenant or another application's pool. Most of
 * the tests below go through {@link CatalogTenantIndexResolver} as well as the catalog, because
 * what matters is not the stored placement but what a request would resolve to afterwards — and
 * the resolver's family guard would refuse a reassignment that crossed a family.
 *
 * <p>{@code tenant-a} hashes to pool 2 of 4, {@code lab-a} and {@code tenant-high} to pool 1.
 */
class PoolReassignerTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");
    private static final SearchDomains DOMAINS = SearchDomains.of(LAB_RESULTS, ORDERS);
    private static final int POOLS = 4;

    @Nested
    @DisplayName("a NORMAL pooled tenant")
    class NormalTenant {

        private final InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, POOLS);
        private final TenantIndexResolver resolver = new CatalogTenantIndexResolver(catalog, DOMAINS);
        private final TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");

        @Test
        @DisplayName("writes go to the new pool's write alias")
        void writesGoToTheNewPool() {
            new PoolReassigner(catalog, POOLS).reassign(tenant, 3);

            assertThat(resolver.writeIndex(tenant)).isEqualTo("lab-results-pool-3-write");
        }

        @Test
        @DisplayName("reads span the new pool first and the old pool second")
        void readsSpanNewThenOld() {
            // Old documents stay where they were written; dropping the old pool from the search
            // targets would make the tenant's history vanish the moment it is moved.
            new PoolReassigner(catalog, POOLS).reassign(tenant, 3);

            assertThat(catalog.placementOf(tenant).searchTargets())
                    .containsExactly("lab-results-pool-3", "lab-results-pool-2");
            assertThat(resolver.searchIndex(tenant)).isEqualTo("lab-results-pool-3,lab-results-pool-2");
        }

        @Test
        @DisplayName("routing is kept: both targets are routed pools sharing the tenant id as routing value")
        void routingIsKept() {
            new PoolReassigner(catalog, POOLS).reassign(tenant, 3);

            Placement placement = catalog.placementOf(tenant);
            assertThat(placement.state()).isEqualTo(MigrationState.STABLE);
            assertThat(placement.routed()).isTrue();
            assertThat(placement.tier()).isEqualTo(Tier.POOLED);
            assertThat(resolver.routing(tenant)).contains("tenant-a");
        }

        @Test
        @DisplayName("privacy level stays NORMAL")
        void privacyLevelStaysNormal() {
            new PoolReassigner(catalog, POOLS).reassign(tenant, 3);

            assertThat(resolver.privacyLevel(tenant)).isEqualTo(PrivacyLevel.NORMAL);
        }

        @Test
        @DisplayName("pool 0 is a legal destination")
        void poolZeroIsLegal() {
            new PoolReassigner(catalog, POOLS).reassign(tenant, 0);

            assertThat(resolver.writeIndex(tenant)).isEqualTo("lab-results-pool-0-write");
        }

        @Test
        @DisplayName("a negative pool number is refused and the placement is left as it was")
        void negativePoolNumberIsRefused() {
            Placement before = catalog.placementOf(tenant);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PoolReassigner(catalog, POOLS).reassign(tenant, -1))
                    .withMessageContaining("-1");

            assertThat(catalog.placementOf(tenant)).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("the destination pool")
    class DestinationPool {

        @Test
        @DisplayName("a pool number equal to the pool count is refused — pools are numbered from 0 — and nothing moves")
        void poolNumberEqualToTheCountIsRefused() {
            // lab-results-pool-4 was never provisioned: every later write for the tenant would fail.
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, POOLS);
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            Placement before = catalog.placementOf(tenant);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PoolReassigner(catalog, POOLS).reassign(tenant, POOLS));

            assertThat(catalog.placementOf(tenant)).isEqualTo(before);
        }

        @Test
        @DisplayName("the refusal names the pool, the domain and the range that does exist")
        void refusalNamesPoolDomainAndRange() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, POOLS);
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PoolReassigner(catalog, POOLS).reassign(tenant, 7))
                    .withMessageMatching("(?s).*\\b7\\b.*")
                    .withMessageMatching("(?s).*\\blab-results\\b(?!-).*")
                    .withMessageMatching("(?s).*\\b0\\b.*\\b3\\b.*");
        }

        @Test
        @DisplayName("the last pool (count - 1) is a legal destination")
        void lastPoolIsLegal() {
            // tenant-a hashes to pool 0 of 2, so pool 1 is both the boundary and a real move.
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, 2);
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");

            new PoolReassigner(catalog, 2).reassign(tenant, 1);

            assertThat(catalog.placementOf(tenant).writeTarget()).isEqualTo("lab-results-pool-1-write");
        }

        @Test
        @DisplayName("each domain's own pool count decides: pool 2 is refused in a 2-pool domain and accepted in a 4-pool one")
        void poolCountIsPerDomain() {
            // lab-a is in pool 1 in both domains. A reassigner applying one count to every domain
            // either refuses a real pool or sends orders' tenant to orders-pool-2, which does not exist.
            ToIntFunction<SearchDomain> pools = d -> d.equals(ORDERS) ? 2 : 4;
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, pools);
            PoolReassigner reassigner = new PoolReassigner(catalog, pools);
            TenantRef inOrders = TenantRef.of(ORDERS, "lab-a");
            TenantRef inLabResults = TenantRef.of(LAB_RESULTS, "lab-a");
            Placement ordersBefore = catalog.placementOf(inOrders);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> reassigner.reassign(inOrders, 2))
                    .withMessageMatching("(?s).*\\borders\\b.*");
            reassigner.reassign(inLabResults, 2);

            assertThat(catalog.placementOf(inOrders)).isEqualTo(ordersBefore);
            assertThat(catalog.placementOf(inLabResults).writeTarget()).isEqualTo("lab-results-pool-2-write");
        }

        @Test
        @DisplayName("a move to the pool the tenant already writes to is refused and nothing changes")
        void moveToTheCurrentPoolIsRefused() {
            // Accepted, it would record a dual read of one index twice — a migration that is not happening.
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, POOLS);
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");   // pool 2
            Placement before = catalog.placementOf(tenant);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PoolReassigner(catalog, POOLS).reassign(tenant, 2))
                    .withMessageContaining("tenant-a")
                    .withMessageMatching("(?s).*\\b2\\b.*");

            assertThat(catalog.placementOf(tenant)).isEqualTo(before);
        }

        @Test
        @DisplayName("'current pool' means the pool written to now, not the one the tenant id hashes to")
        void currentPoolIsTheWriteTargetNotTheHashedPool() {
            // After one move tenant-a writes to pool 3. Asking for pool 3 again is the no-op move.
            // (What a second, real move does to reads is a documented known limit — not asserted here.)
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, POOLS);
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            PoolReassigner reassigner = new PoolReassigner(catalog, POOLS);
            reassigner.reassign(tenant, 3);
            Placement afterFirstMove = catalog.placementOf(tenant);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> reassigner.reassign(tenant, 3));

            assertThat(catalog.placementOf(tenant)).isEqualTo(afterFirstMove);
        }

        @Test
        @DisplayName("a HIGH tenant's move to its current secure pool is refused too")
        void highTenantMoveToCurrentPoolIsRefused() {
            // The comparison must be made within the tenant's own family: a check that built the
            // NORMAL name would never match a secure write alias, and would let this through.
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-high");   // pool 1
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> POOLS,
                    List.of(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.HIGH, POOLS)));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PoolReassigner(catalog, POOLS).reassign(tenant, 1));
        }
    }

    @Nested
    @DisplayName("a HIGH pooled tenant")
    class HighTenant {

        private final TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-high");
        // assumeOccupied: were the reassigner to write a NORMAL placement, the catalog would refuse
        // it as a direct flip. The asserts below would catch it either way.
        private final InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> POOLS,
                List.of(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.HIGH, POOLS)),
                TenantDocumentCensus.assumeOccupied());
        private final TenantIndexResolver resolver = new CatalogTenantIndexResolver(catalog, DOMAINS);

        @Test
        @DisplayName("stays in the secure family, for writes and for both read targets")
        void staysInTheSecureFamily() {
            // The move is a number; the family comes from the tenant's own privacy level. A
            // reassigner that built "lab-results-pool-3" here would send sealed-only data to a
            // plaintext index — the resolver's guard would refuse it, failing every request.
            new PoolReassigner(catalog, POOLS).reassign(tenant, 3);

            assertThat(resolver.privacyLevel(tenant)).isEqualTo(PrivacyLevel.HIGH);
            assertThat(resolver.writeIndex(tenant)).isEqualTo("lab-results-secure-pool-3-write");
            assertThat(resolver.searchIndex(tenant))
                    .isEqualTo("lab-results-secure-pool-3,lab-results-secure-pool-1");
            assertThat(resolver.routing(tenant)).contains("tenant-high");
        }
    }

    @Nested
    @DisplayName("tenants in two domains")
    class TwoDomains {

        @Test
        @DisplayName("builds the new pool from the tenant's own domain")
        void newPoolBelongsToTheTenantsDomain() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, POOLS);
            TenantRef inOrders = TenantRef.of(ORDERS, "lab-a");

            new PoolReassigner(catalog, POOLS).reassign(inOrders, 3);

            Placement placement = catalog.placementOf(inOrders);
            assertThat(placement.writeTarget()).isEqualTo("orders-pool-3-write");
            assertThat(placement.searchTargets()).containsExactly("orders-pool-3", "orders-pool-1");
        }

        @Test
        @DisplayName("moving a tenant in one domain leaves the same tenant id in another where it was")
        void moveDoesNotTouchTheOtherDomain() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, POOLS);
            TenantRef inLabResults = TenantRef.of(LAB_RESULTS, "lab-a");
            TenantRef inOrders = TenantRef.of(ORDERS, "lab-a");
            Placement labResultsBefore = catalog.placementOf(inLabResults);

            new PoolReassigner(catalog, POOLS).reassign(inOrders, 3);

            assertThat(catalog.placementOf(inLabResults)).isEqualTo(labResultsBefore);
        }
    }

    @Nested
    @DisplayName("a DEDICATED tenant")
    class DedicatedTenant {

        @Test
        @DisplayName("is refused — it has no pool to leave — and its placement is untouched")
        void dedicatedTenantIsRefused() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-big");
            Placement dedicated = new Placement(tenant, Tier.DEDICATED, "lab-results-tenant-big-write",
                    List.of("lab-results-tenant-big"), false, MigrationState.STABLE, PrivacyLevel.NORMAL);
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> POOLS, List.of(dedicated));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new PoolReassigner(catalog, POOLS).reassign(tenant, 3));

            assertThat(catalog.placementOf(tenant)).isEqualTo(dedicated);
        }
    }
}
