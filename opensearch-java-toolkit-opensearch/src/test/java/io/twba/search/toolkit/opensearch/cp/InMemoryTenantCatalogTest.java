package io.twba.search.toolkit.opensearch.cp;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.MigrationState;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Placement;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The catalog is the only source of truth for a tenant's privacy level, so these tests are about
 * what it refuses as much as what it records.
 *
 * <p>The kata's catalog served one application and keyed on a bare tenant id. This one keys on
 * {@code (domain, tenant)}, and the tests that could not exist in the kata — the same tenant id in
 * two domains, per-domain pool counts, a census consulted per domain — are the ones that prove the
 * generalization did not quietly collapse two applications into one namespace.
 *
 * <p>Literal index names ({@code lab-results-pool-1}) are pinned on purpose: the kata produced them,
 * and a later parity suite depends on the toolkit producing them character for character. The pool
 * numbers are {@code floorMod(tenantId.hashCode(), 4)} — {@code lab-a} is 1, {@code tenant-a} is 2,
 * {@code tenant-new} (negative hash) is 1 — computed once and written down, not recomputed here.
 */
class InMemoryTenantCatalogTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");
    private static final SearchDomains DOMAINS = SearchDomains.of(LAB_RESULTS, ORDERS);

    /** The kata's fixed pool count. */
    private static final int KATA_POOLS = 4;

    @Nested
    @DisplayName("lazy provisioning")
    class LazyProvisioning {

        @Test
        @DisplayName("an unknown tenant is provisioned NORMAL, in the plaintext pool family")
        void lazyProvisioningDefaultsToNormal() {
            Placement placement = new InMemoryTenantCatalog(DOMAINS, KATA_POOLS)
                    .placementOf(TenantRef.of(LAB_RESULTS, "tenant-new"));

            assertThat(placement.privacyLevel()).isEqualTo(PrivacyLevel.NORMAL);
            assertThat(placement.tier()).isEqualTo(Tier.POOLED);
            assertThat(placement.state()).isEqualTo(MigrationState.STABLE);
            assertThat(placement.routed()).isTrue();
            // tenant-new has a negative hashCode: a plain % would mint "pool--3".
            assertThat(placement.writeTarget()).isEqualTo("lab-results-pool-1-write");
            assertThat(placement.searchTargets()).containsExactly("lab-results-pool-1");
        }

        @Test
        @DisplayName("spec: an unknown (domain, tenant) pair defaults to NORMAL in that domain's pooled family")
        void unknownPairDefaultsToNormalPooledInItsOwnDomain() {
            Placement placement = new InMemoryTenantCatalog(DOMAINS, KATA_POOLS)
                    .placementOf(TenantRef.of(ORDERS, "lab-a"));

            assertThat(placement.tenant()).isEqualTo(TenantRef.of(ORDERS, "lab-a"));
            assertThat(placement.privacyLevel()).isEqualTo(PrivacyLevel.NORMAL);
            assertThat(placement.tier()).isEqualTo(Tier.POOLED);
            assertThat(placement.writeTarget()).isEqualTo("orders-pool-1-write");
            assertThat(placement.searchTargets()).containsExactly("orders-pool-1");
        }

        @Test
        @DisplayName("once provisioned the placement is a record: later reads see updates, not a fresh hash")
        void provisionedPlacementIsARecord() {
            // The point of a catalog over a hash: the control plane can move one tenant and have
            // it stay moved. A catalog that re-derived on read would silently undo every move.
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, KATA_POOLS);
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            catalog.placementOf(tenant);
            Placement moved = new Placement(tenant, Tier.POOLED, "lab-results-pool-0-write",
                    List.of("lab-results-pool-0"), true, MigrationState.STABLE, PrivacyLevel.NORMAL);

            catalog.update(moved);

            assertThat(catalog.placementOf(tenant)).isEqualTo(moved);
        }
    }

    @Nested
    @DisplayName("tenants in two domains")
    class TwoDomains {

        @Test
        @DisplayName("spec: the same tenant id in two domains is two independent entries, each in its own family")
        void sameTenantIdInTwoDomainsResolvesIndependently() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, KATA_POOLS);

            Placement inLabResults = catalog.placementOf(TenantRef.of(LAB_RESULTS, "lab-a"));
            Placement inOrders = catalog.placementOf(TenantRef.of(ORDERS, "lab-a"));

            assertThat(inLabResults).isNotEqualTo(inOrders);
            assertThat(inLabResults.writeTarget()).isEqualTo("lab-results-pool-1-write");
            assertThat(inLabResults.searchTargets()).containsExactly("lab-results-pool-1");
            assertThat(inOrders.writeTarget()).isEqualTo("orders-pool-1-write");
            assertThat(inOrders.searchTargets()).containsExactly("orders-pool-1");
        }

        @Test
        @DisplayName("spec: privacy level is per domain — HIGH in one, NORMAL in the other, neither disturbs the other")
        void privacyLevelIsPerDomain() {
            TenantRef highInLabResults = TenantRef.of(LAB_RESULTS, "lab-a");
            TenantRef normalInOrders = TenantRef.of(ORDERS, "lab-a");
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> KATA_POOLS, List.of(
                    InMemoryTenantCatalog.pooled(highInLabResults, PrivacyLevel.HIGH, KATA_POOLS),
                    InMemoryTenantCatalog.pooled(normalInOrders, PrivacyLevel.NORMAL, KATA_POOLS)));

            // Interleave the reads: a catalog keyed on the bare tenant id would let the last seed
            // win for both, or let one lookup's lazy provisioning overwrite the other.
            Placement orders = catalog.placementOf(normalInOrders);
            Placement labResults = catalog.placementOf(highInLabResults);
            Placement ordersAgain = catalog.placementOf(normalInOrders);

            assertThat(labResults.privacyLevel()).isEqualTo(PrivacyLevel.HIGH);
            assertThat(labResults.writeTarget()).isEqualTo("lab-results-secure-pool-1-write");
            assertThat(orders.privacyLevel()).isEqualTo(PrivacyLevel.NORMAL);
            assertThat(orders.writeTarget()).isEqualTo("orders-pool-1-write");
            assertThat(ordersAgain).isEqualTo(orders);
        }

        @Test
        @DisplayName("a seeded HIGH tenant in one domain does not make the same id HIGH when first seen in another")
        void lazyProvisioningInOneDomainIgnoresASeedInAnother() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> KATA_POOLS, List.of(
                    InMemoryTenantCatalog.pooled(TenantRef.of(LAB_RESULTS, "lab-a"), PrivacyLevel.HIGH, KATA_POOLS)));

            assertThat(catalog.placementOf(TenantRef.of(ORDERS, "lab-a")).privacyLevel())
                    .isEqualTo(PrivacyLevel.NORMAL);
            assertThat(catalog.placementOf(TenantRef.of(LAB_RESULTS, "lab-a")).privacyLevel())
                    .isEqualTo(PrivacyLevel.HIGH);
        }

        @Test
        @DisplayName("an update in one domain leaves the same tenant id in another domain untouched")
        void updateInOneDomainDoesNotLeakIntoAnother() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS,
                    d -> KATA_POOLS, List.of(), TenantDocumentCensus.assumeEmpty());
            TenantRef inOrders = TenantRef.of(ORDERS, "lab-a");
            Placement ordersBefore = catalog.placementOf(inOrders);

            catalog.update(InMemoryTenantCatalog.pooled(TenantRef.of(LAB_RESULTS, "lab-a"), PrivacyLevel.HIGH, KATA_POOLS));

            assertThat(catalog.placementOf(inOrders)).isEqualTo(ordersBefore);
        }
    }

    @Nested
    @DisplayName("pool count per domain")
    class PoolCountPerDomain {

        @Test
        @DisplayName("each domain is placed with its own pool count")
        void eachDomainUsesItsOwnPoolCount() {
            // tenant-b hashes to pool 3 of 4 but pool 1 of 2. A catalog applying lab-results' count
            // to orders would route orders/tenant-b to orders-pool-3, an index that does not exist.
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> d.equals(ORDERS) ? 2 : 4);

            assertThat(catalog.placementOf(TenantRef.of(LAB_RESULTS, "tenant-b")).searchTargets())
                    .containsExactly("lab-results-pool-3");
            assertThat(catalog.placementOf(TenantRef.of(ORDERS, "tenant-b")).searchTargets())
                    .containsExactly("orders-pool-1");
        }

        @Test
        @DisplayName("a zero pool count fails with an IllegalStateException naming the domain")
        void zeroPoolCountNamesTheDomain() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> d.equals(ORDERS) ? 0 : 4);

            // Not an ArithmeticException out of floorMod: the operator needs to know which
            // domain's deployment configuration is wrong.
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> catalog.placementOf(TenantRef.of(ORDERS, "lab-a")))
                    .withMessageContaining("orders")
                    .withMessageContaining("0");
            assertThat(catalog.placementOf(TenantRef.of(LAB_RESULTS, "lab-a")).searchTargets())
                    .containsExactly("lab-results-pool-1");
        }

        @Test
        @DisplayName("a negative pool count is refused the same way")
        void negativePoolCountIsRefused() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, -4);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> catalog.placementOf(TenantRef.of(LAB_RESULTS, "lab-a")))
                    .withMessageContaining("lab-results");
        }

        @Test
        @DisplayName("a single pool is the smallest legal count and places everyone in pool 0")
        void onePoolIsLegal() {
            assertThat(new InMemoryTenantCatalog(DOMAINS, 1).placementOf(TenantRef.of(LAB_RESULTS, "tenant-b")).searchTargets())
                    .containsExactly("lab-results-pool-0");
        }
    }

    @Nested
    @DisplayName("seeding")
    class Seeding {

        @Test
        @DisplayName("a seeded HIGH tenant keeps HIGH and lands in the secure family")
        void seededHighTenantIsHigh() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-high");
            TenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> KATA_POOLS,
                    List.of(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.HIGH, KATA_POOLS)));

            Placement placement = catalog.placementOf(tenant);

            assertThat(placement.privacyLevel()).isEqualTo(PrivacyLevel.HIGH);
            assertThat(placement.writeTarget()).isEqualTo("lab-results-secure-pool-1-write");
            assertThat(placement.searchTargets()).containsExactly("lab-results-secure-pool-1");
            // Still pooled and routed: privacy changes the family, not the topology.
            assertThat(placement.tier()).isEqualTo(Tier.POOLED);
            assertThat(placement.routed()).isTrue();
        }

        @Test
        @DisplayName("the first secure pool keeps the kata's names")
        void firstSecurePoolKeepsTheKatasNames() {
            Placement placement = InMemoryTenantCatalog.pooled(
                    TenantRef.of(LAB_RESULTS, "tenant-high"), PrivacyLevel.HIGH, 1);

            assertThat(placement.writeTarget()).isEqualTo("lab-results-secure-pool-0-write");
            assertThat(placement.searchTargets()).containsExactly("lab-results-secure-pool-0");
        }
    }

    @Nested
    @DisplayName("declared domains")
    class DeclaredDomains {

        // Only lab-results is declared. Orders is a perfectly valid domain; it simply was never
        // checked for overlap with lab-results, so this catalog has no business placing it.
        private static final SearchDomains LAB_RESULTS_ONLY = SearchDomains.of(LAB_RESULTS);

        @Test
        @DisplayName("spec: reading a placement for an undeclared domain fails naming the domain, rather than provisioning one")
        void placementOfRefusesAnUndeclaredDomain() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(LAB_RESULTS_ONLY, KATA_POOLS);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> catalog.placementOf(TenantRef.of(ORDERS, "lab-a")))
                    .withMessageMatching("(?s).*\\borders\\b.*");
        }

        @Test
        @DisplayName("spec: writing a placement for an undeclared domain fails naming the domain")
        void updateRefusesAnUndeclaredDomain() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(
                    LAB_RESULTS_ONLY, d -> KATA_POOLS, List.of(), TenantDocumentCensus.assumeEmpty());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> catalog.update(
                            InMemoryTenantCatalog.pooled(TenantRef.of(ORDERS, "lab-a"), PrivacyLevel.NORMAL, KATA_POOLS)))
                    .withMessageMatching("(?s).*\\borders\\b.*");
        }

        @Test
        @DisplayName("spec: seeding a placement for an undeclared domain fails construction, naming the domain")
        void seedingRefusesAnUndeclaredDomain() {
            // Seeds come from provisioning config. A seed for an undeclared domain accepted at startup
            // would be served later without ever passing through placementOf's check on the way in.
            List<Placement> seed = List.of(
                    InMemoryTenantCatalog.pooled(TenantRef.of(LAB_RESULTS, "lab-a"), PrivacyLevel.HIGH, KATA_POOLS),
                    InMemoryTenantCatalog.pooled(TenantRef.of(ORDERS, "lab-a"), PrivacyLevel.HIGH, KATA_POOLS));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new InMemoryTenantCatalog(LAB_RESULTS_ONLY, d -> KATA_POOLS, seed))
                    .withMessageMatching("(?s).*\\borders\\b.*");
        }
    }

    @Nested
    @DisplayName("privacy-level changes")
    class PrivacyFlips {

        @Test
        @DisplayName("flipping the level on a STABLE, occupied tenant is refused and changes nothing")
        void directFlipIsRejected() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> KATA_POOLS,
                    List.of(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.NORMAL, KATA_POOLS)),
                    TenantDocumentCensus.assumeOccupied());
            Placement before = catalog.placementOf(tenant);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> catalog.update(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.HIGH, KATA_POOLS)))
                    .satisfies(e -> assertThat(e.getMessage())
                            .contains("tenant-a", "NORMAL", "HIGH")
                            // The operator has to be told what to do instead, not just "no".
                            .contains("migration"));

            assertThat(catalog.placementOf(tenant)).isEqualTo(before);
        }

        @Test
        @DisplayName("the refusal names the domain as well as the tenant")
        void refusalNamesTheDomain() {
            // Two applications share this control plane; "tenant lab-a" alone does not say which
            // one's migration to run. The domain must appear as itself, not only as the prefix of
            // a family name like "orders-*", which is why the match refuses a trailing hyphen.
            TenantRef tenant = TenantRef.of(ORDERS, "lab-a");
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> KATA_POOLS,
                    List.of(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.NORMAL, KATA_POOLS)));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> catalog.update(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.HIGH, KATA_POOLS)))
                    .withMessageContaining("lab-a")
                    .withMessageMatching("(?s).*\\borders\\b(?!-).*");
        }

        @Test
        @DisplayName("the default census assumes occupancy, so an unguarded flip is refused")
        void defaultCensusRefusesFlip() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> KATA_POOLS,
                    List.of(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.HIGH, KATA_POOLS)));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> catalog.update(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.NORMAL, KATA_POOLS)));
            assertThat(catalog.placementOf(tenant).privacyLevel()).isEqualTo(PrivacyLevel.HIGH);
        }

        @Test
        @DisplayName("an empty tenant may be reclassified — there is nothing to strand")
        void flipAllowedWhenNoDocumentsExist() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> KATA_POOLS,
                    List.of(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.NORMAL, KATA_POOLS)),
                    TenantDocumentCensus.assumeEmpty());

            assertThatNoException()
                    .isThrownBy(() -> catalog.update(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.HIGH, KATA_POOLS)));
            assertThat(catalog.placementOf(tenant).privacyLevel()).isEqualTo(PrivacyLevel.HIGH);
        }

        @Test
        @DisplayName("the census is asked about the (domain, tenant) being flipped, not the tenant id at large")
        void censusIsConsultedPerDomain() {
            // lab-a holds documents in lab-results only. Its orders entry is empty, so reclassifying
            // it there strands nothing — and a census that rolled up across domains would refuse.
            List<TenantRef> asked = new ArrayList<>();
            TenantDocumentCensus census = tenant -> {
                asked.add(tenant);
                return tenant.domain().equals(LAB_RESULTS);
            };
            TenantRef inLabResults = TenantRef.of(LAB_RESULTS, "lab-a");
            TenantRef inOrders = TenantRef.of(ORDERS, "lab-a");
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> KATA_POOLS, List.of(
                    InMemoryTenantCatalog.pooled(inLabResults, PrivacyLevel.NORMAL, KATA_POOLS),
                    InMemoryTenantCatalog.pooled(inOrders, PrivacyLevel.NORMAL, KATA_POOLS)), census);

            assertThatNoException()
                    .isThrownBy(() -> catalog.update(InMemoryTenantCatalog.pooled(inOrders, PrivacyLevel.HIGH, KATA_POOLS)));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> catalog.update(InMemoryTenantCatalog.pooled(inLabResults, PrivacyLevel.HIGH, KATA_POOLS)));

            assertThat(catalog.placementOf(inOrders).privacyLevel()).isEqualTo(PrivacyLevel.HIGH);
            assertThat(catalog.placementOf(inLabResults).privacyLevel()).isEqualTo(PrivacyLevel.NORMAL);
            assertThat(asked).containsExactly(inOrders, inLabResults);
        }

        @Test
        @DisplayName("a migration in flight may carry the level across — it is moving the data too")
        void flipAllowedWhileBackfilling() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            Placement backfilling = new Placement(tenant, Tier.POOLED, "lab-results-pool-1-write",
                    List.of("lab-results-pool-1"), true, MigrationState.BACKFILLING, PrivacyLevel.NORMAL);
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS,
                    d -> KATA_POOLS, List.of(backfilling), TenantDocumentCensus.assumeOccupied());

            assertThatNoException()
                    .isThrownBy(() -> catalog.update(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.HIGH, KATA_POOLS)));
            assertThat(catalog.placementOf(tenant).privacyLevel()).isEqualTo(PrivacyLevel.HIGH);
        }

        @Test
        @DisplayName("the first write for a never-seen pair is provisioning, not a flip, even at HIGH")
        void firstWriteIsNotAFlip() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS,
                    d -> KATA_POOLS, List.of(), TenantDocumentCensus.assumeOccupied());
            TenantRef tenant = TenantRef.of(ORDERS, "lab-a");

            assertThatNoException()
                    .isThrownBy(() -> catalog.update(InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.HIGH, KATA_POOLS)));
            assertThat(catalog.placementOf(tenant).privacyLevel()).isEqualTo(PrivacyLevel.HIGH);
        }

        @Test
        @DisplayName("an update that leaves the level alone is an ordinary control-plane write")
        void sameLevelUpdatePasses() {
            InMemoryTenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, KATA_POOLS);
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            catalog.placementOf(tenant);

            assertThatNoException().isThrownBy(() -> new PoolReassigner(catalog, KATA_POOLS).reassign(tenant, 3));
            assertThat(catalog.placementOf(tenant).privacyLevel()).isEqualTo(PrivacyLevel.NORMAL);
            assertThat(catalog.placementOf(tenant).searchTargets()).contains("lab-results-pool-3");
        }
    }

    @Nested
    @DisplayName("the placement record")
    class PlacementRecord {

        @Test
        @DisplayName("refuses a placement with no privacy level rather than defaulting it")
        void refusesMissingPrivacyLevel() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Placement(TenantRef.of(LAB_RESULTS, "lab-a"), Tier.POOLED,
                            "lab-results-pool-1-write", List.of("lab-results-pool-1"), true,
                            MigrationState.STABLE, null))
                    .withMessageContaining("privacyLevel");
        }

        @Test
        @DisplayName("refuses a placement with no tenant")
        void refusesMissingTenant() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Placement(null, Tier.POOLED, "lab-results-pool-1-write",
                            List.of("lab-results-pool-1"), true, MigrationState.STABLE, PrivacyLevel.NORMAL))
                    .withMessageContaining("tenant");
        }

        @Test
        @DisplayName("search targets are copied, so the list handed in cannot be edited past the family guard")
        void searchTargetsAreCopied() {
            List<String> targets = new ArrayList<>(List.of("lab-results-secure-pool-1"));
            Placement placement = new Placement(TenantRef.of(LAB_RESULTS, "lab-a"), Tier.POOLED,
                    "lab-results-secure-pool-1-write", targets, true, MigrationState.STABLE, PrivacyLevel.HIGH);

            targets.add("lab-results-pool-1");

            assertThat(placement.searchTargets()).containsExactly("lab-results-secure-pool-1");
        }
    }
}
