package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.cp.InMemoryTenantCatalog;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.MigrationState;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Placement;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The resolver is the only place topology is known, which includes which family a tenant's privacy
 * level puts it in — and, now that several applications share one control plane, which domain's
 * indices it may touch at all.
 *
 * <p>Target names in a catalog are <em>stored</em>, so they can be wrong. The family guard is the
 * last line of defence: a stored name that falls outside the placement's own
 * {@code (domain, privacy level)} family must fail the request rather than be returned, because
 * the alternative is readable values in an index promised to hold none, or one application writing
 * into another's.
 *
 * <p>{@code tenant-a} hashes to pool 2 of 4.
 */
class CatalogTenantIndexResolverTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");
    private static final SearchDomains DOMAINS = SearchDomains.of(LAB_RESULTS, ORDERS);
    private static final int KATA_POOLS = 4;

    @Nested
    @DisplayName("resolving a well-formed placement")
    class Resolution {

        @Test
        @DisplayName("a HIGH tenant resolves entirely inside the secure family, routed as before")
        void highTenantResolvesToSecureFamily() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-high");
            TenantIndexResolver resolver = resolverFor(
                    InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.HIGH, KATA_POOLS));

            assertThat(resolver.writeIndex(tenant)).isEqualTo("lab-results-secure-pool-1-write");
            assertThat(resolver.searchIndex(tenant)).isEqualTo("lab-results-secure-pool-1");
            assertThat(resolver.privacyLevel(tenant)).isEqualTo(PrivacyLevel.HIGH);
            // Same routing value as any pooled tenant: the secure family is still one shard per tenant.
            assertThat(resolver.routing(tenant)).contains("tenant-high");
        }

        @Test
        @DisplayName("a NORMAL tenant resolves exactly as it did in the kata")
        void normalTenantIsUnaffected() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(new InMemoryTenantCatalog(DOMAINS, KATA_POOLS), DOMAINS);

            assertThat(resolver.writeIndex(tenant)).isEqualTo("lab-results-pool-2-write");
            assertThat(resolver.searchIndex(tenant)).isEqualTo("lab-results-pool-2");
            assertThat(resolver.privacyLevel(tenant)).isEqualTo(PrivacyLevel.NORMAL);
            assertThat(resolver.routing(tenant)).contains("tenant-a");
        }

        @Test
        @DisplayName("dedicated HIGH tenants stay in the secure family too")
        void dedicatedHighTenantStaysSecure() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-big");
            TenantIndexResolver resolver = resolverFor(new Placement(
                    tenant, Tier.DEDICATED, "lab-results-secure-tenant-big-write",
                    List.of("lab-results-secure-tenant-big"), false, MigrationState.STABLE, PrivacyLevel.HIGH));

            assertThat(resolver.writeIndex(tenant)).isEqualTo("lab-results-secure-tenant-big-write");
            assertThat(resolver.searchIndex(tenant)).isEqualTo("lab-results-secure-tenant-big");
            assertThat(resolver.routing(tenant)).isEmpty();   // dedicated: no routing, as before
        }

        @Test
        @DisplayName("dual-read targets are joined with a comma, in catalog order")
        void dualReadTargetsAreCommaJoined() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            TenantIndexResolver resolver = resolverFor(new Placement(
                    tenant, Tier.POOLED, "lab-results-pool-3-write",
                    List.of("lab-results-pool-3", "lab-results-pool-2"), true, MigrationState.STABLE,
                    PrivacyLevel.NORMAL));

            assertThat(resolver.searchIndex(tenant)).isEqualTo("lab-results-pool-3,lab-results-pool-2");
        }
    }

    @Nested
    @DisplayName("routing")
    class Routing {

        @Test
        @DisplayName("routing is dropped during DUAL_READ on both families alike")
        void routingDroppedDuringMigrationOnBothFamilies() {
            TenantRef high = TenantRef.of(LAB_RESULTS, "tenant-high");
            TenantRef plain = TenantRef.of(LAB_RESULTS, "tenant-a");
            TenantIndexResolver secure = resolverFor(new Placement(
                    high, Tier.POOLED, "lab-results-secure-pool-1-write",
                    List.of("lab-results-secure-pool-1"), true, MigrationState.DUAL_READ, PrivacyLevel.HIGH));
            TenantIndexResolver normal = resolverFor(new Placement(
                    plain, Tier.POOLED, "lab-results-pool-1-write",
                    List.of("lab-results-pool-1"), true, MigrationState.DUAL_READ, PrivacyLevel.NORMAL));

            assertThat(secure.routing(high)).isEqualTo(Optional.<String>empty());
            assertThat(normal.routing(plain)).isEqualTo(Optional.<String>empty());
        }

        @Test
        @DisplayName("routing is dropped while BACKFILLING as well")
        void routingDroppedWhileBackfilling() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            TenantIndexResolver resolver = resolverFor(new Placement(
                    tenant, Tier.POOLED, "lab-results-pool-1-write",
                    List.of("lab-results-pool-1"), true, MigrationState.BACKFILLING, PrivacyLevel.NORMAL));

            assertThat(resolver.routing(tenant)).isEmpty();
        }

        @Test
        @DisplayName("the routing value is the bare tenant id, not domain/tenant")
        void routingValueIsTheBareTenantId() {
            // Must match the kata's routing value: the same documents routed with "orders/lab-a"
            // would hash to a different shard, and routed searches would silently miss them.
            // Routing applies inside one index, and indices are already per domain.
            TenantRef tenant = TenantRef.of(ORDERS, "lab-a");
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(new InMemoryTenantCatalog(DOMAINS, KATA_POOLS), DOMAINS);

            assertThat(resolver.routing(tenant)).isEqualTo(Optional.of("lab-a"));
        }
    }

    @Nested
    @DisplayName("tenants in two domains")
    class TwoDomains {

        @Test
        @DisplayName("spec: the same tenant id in two domains resolves within each domain's own family")
        void sameTenantIdInTwoDomainsResolvesIndependently() {
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(new InMemoryTenantCatalog(DOMAINS, KATA_POOLS), DOMAINS);
            TenantRef inLabResults = TenantRef.of(LAB_RESULTS, "lab-a");
            TenantRef inOrders = TenantRef.of(ORDERS, "lab-a");

            assertThat(resolver.writeIndex(inLabResults)).isEqualTo("lab-results-pool-1-write");
            assertThat(resolver.searchIndex(inLabResults)).isEqualTo("lab-results-pool-1");
            assertThat(resolver.writeIndex(inOrders)).isEqualTo("orders-pool-1-write");
            assertThat(resolver.searchIndex(inOrders)).isEqualTo("orders-pool-1");
        }

        @Test
        @DisplayName("spec: privacy level is per domain — each resolves its own family and posture")
        void privacyLevelIsPerDomain() {
            TenantRef highInLabResults = TenantRef.of(LAB_RESULTS, "lab-a");
            TenantRef normalInOrders = TenantRef.of(ORDERS, "lab-a");
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(new InMemoryTenantCatalog(DOMAINS,
                    d -> KATA_POOLS, List.of(
                    InMemoryTenantCatalog.pooled(highInLabResults, PrivacyLevel.HIGH, KATA_POOLS),
                    InMemoryTenantCatalog.pooled(normalInOrders, PrivacyLevel.NORMAL, KATA_POOLS))), DOMAINS);

            assertThat(resolver.privacyLevel(normalInOrders)).isEqualTo(PrivacyLevel.NORMAL);
            assertThat(resolver.privacyLevel(highInLabResults)).isEqualTo(PrivacyLevel.HIGH);
            assertThat(resolver.writeIndex(highInLabResults)).isEqualTo("lab-results-secure-pool-1-write");
            assertThat(resolver.writeIndex(normalInOrders)).isEqualTo("orders-pool-1-write");
            assertThat(resolver.privacyLevel(normalInOrders)).isEqualTo(PrivacyLevel.NORMAL);
        }
    }

    @Nested
    @DisplayName("the family guard")
    class FamilyGuard {

        @Test
        @DisplayName("spec: a HIGH placement pointing at a plaintext pool fails the request, not the promise")
        void misplacedHighTenantFailsClosed() {
            // A control-plane bug: if it resolved, the next write would put readable values in an
            // index whose whole point is that it holds none.
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-high");
            TenantIndexResolver resolver = resolverFor(new Placement(
                    tenant, Tier.POOLED, "lab-results-pool-1-write",
                    List.of("lab-results-pool-1"), true, MigrationState.STABLE, PrivacyLevel.HIGH));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.writeIndex(tenant))
                    .withMessageContaining("tenant-high")
                    .withMessageContaining("lab-results-pool-1-write")
                    .withMessageContaining("lab-results-secure-");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.searchIndex(tenant));
        }

        @Test
        @DisplayName("a NORMAL placement pointing at the secure family is refused as well")
        void misplacedNormalTenantFailsClosed() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            TenantIndexResolver resolver = resolverFor(new Placement(
                    tenant, Tier.POOLED, "lab-results-secure-pool-1-write",
                    List.of("lab-results-secure-pool-1"), true, MigrationState.STABLE, PrivacyLevel.NORMAL));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.writeIndex(tenant));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.searchIndex(tenant));
        }

        @Test
        @DisplayName("spec: a foreign-domain target is refused, naming the tenant, the domain and the target")
        void foreignDomainTargetIsRefused() {
            // Same privacy level, right shape, wrong application: a lab-results tenant pointed at
            // orders' pool would interleave two applications' documents in one index.
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-x");
            TenantIndexResolver resolver = resolverFor(new Placement(
                    tenant, Tier.POOLED, "orders-pool-1-write",
                    List.of("orders-pool-1"), true, MigrationState.STABLE, PrivacyLevel.NORMAL));

            // The domain must appear as itself, not only as the prefix of the "lab-results-*"
            // family the message also names — hence the refusal of a trailing hyphen.
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.writeIndex(tenant))
                    .withMessageContaining("tenant-x")
                    .withMessageMatching("(?s).*\\blab-results\\b(?!-).*")
                    .withMessageContaining("orders-pool-1-write");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.searchIndex(tenant))
                    .withMessageContaining("orders-pool-1");
        }

        @Test
        @DisplayName("another domain's secure family is refused for a HIGH tenant")
        void foreignSecureFamilyIsRefused() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-high");
            TenantIndexResolver resolver = resolverFor(new Placement(
                    tenant, Tier.POOLED, "orders-secure-pool-1-write",
                    List.of("orders-secure-pool-1"), true, MigrationState.STABLE, PrivacyLevel.HIGH));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.writeIndex(tenant))
                    .withMessageContaining("orders-secure-pool-1-write");
        }

        @Test
        @DisplayName("spec: a tenant of a domain whose family contains a declared one's is refused, however plausible its placement")
        void foreignTargetOfAnExtendingDomainIsRefused() {
            // "lab-results-pool-1-write" starts with "lab-", so SearchDomain("lab").owns(...) says yes:
            // the family guard alone would let a "lab" tenant write into lab-results' pool. That
            // cannot be fixed one name at a time, so it is fixed by declaration — "lab" and
            // "lab-results" cannot be declared together (SearchDomainsTest in core) — and this
            // resolver refuses any domain it was not declared with. The catalog here is a stub
            // precisely because a real one would also refuse; the resolver must not rely on that.
            SearchDomain lab = new SearchDomain("lab");
            TenantRef tenant = TenantRef.of(lab, "tenant-x");
            TenantCatalog catalog = stubCatalog(new Placement(
                    tenant, Tier.POOLED, "lab-results-pool-1-write",
                    List.of("lab-results-pool-1"), true, MigrationState.STABLE, PrivacyLevel.NORMAL));
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(catalog, SearchDomains.of(LAB_RESULTS));

            // "lab" must be named as itself, not found inside "lab-results" in the declared list.
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.writeIndex(tenant))
                    .withMessageMatching("(?s).*\\blab\\b(?!-).*");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.searchIndex(tenant))
                    .withMessageMatching("(?s).*\\blab\\b(?!-).*");
        }

        @Test
        @DisplayName("a tenant of an extending domain cannot reach the declared domain's secure family either")
        void extendingDomainCannotReachTheSecureFamily() {
            // The worst form of the overlap: lab-results-secure-pool-1 also starts with "lab-" and not
            // with "lab-secure-", so SearchDomain("lab").owns(..., NORMAL) is true — readable values
            // from a NORMAL lab tenant, in an index promised to hold none.
            TenantRef tenant = TenantRef.of(new SearchDomain("lab"), "tenant-x");
            TenantCatalog catalog = stubCatalog(new Placement(
                    tenant, Tier.POOLED, "lab-results-secure-pool-1-write",
                    List.of("lab-results-secure-pool-1"), true, MigrationState.STABLE, PrivacyLevel.NORMAL));
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(catalog, SearchDomains.of(LAB_RESULTS));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.writeIndex(tenant))
                    .withMessageMatching("(?s).*\\blab\\b(?!-).*");
        }

        @Test
        @DisplayName("every search target is checked, not just the first")
        void everySearchTargetIsChecked() {
            // A dual-read placement whose second target is wrong must not resolve: a guard that
            // only looked at the head of the list would let the bad target ride along.
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-high");
            TenantIndexResolver resolver = resolverFor(new Placement(
                    tenant, Tier.POOLED, "lab-results-secure-pool-3-write",
                    List.of("lab-results-secure-pool-3", "lab-results-pool-1"), true, MigrationState.DUAL_READ,
                    PrivacyLevel.HIGH));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.searchIndex(tenant))
                    .withMessageContaining("'lab-results-pool-1'");
        }

        @Test
        @DisplayName("a good write target does not vouch for bad search targets, nor the reverse")
        void writeAndSearchAreGuardedSeparately() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            TenantIndexResolver badSearch = resolverFor(new Placement(
                    tenant, Tier.POOLED, "lab-results-pool-1-write",
                    List.of("orders-pool-1"), true, MigrationState.STABLE, PrivacyLevel.NORMAL));
            TenantIndexResolver badWrite = resolverFor(new Placement(
                    tenant, Tier.POOLED, "orders-pool-1-write",
                    List.of("lab-results-pool-1"), true, MigrationState.STABLE, PrivacyLevel.NORMAL));

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> badSearch.searchIndex(tenant));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> badWrite.writeIndex(tenant));
        }
    }

    @Nested
    @DisplayName("declared domains")
    class DeclaredDomains {

        /** The four ways a caller reaches the catalog through this resolver. */
        static Stream<Named<Consumer<TenantIndexResolver>>> everyResolverMethod() {
            TenantRef undeclared = TenantRef.of(ORDERS, "tenant-a");
            return Stream.of(
                    Named.of("writeIndex", r -> r.writeIndex(undeclared)),
                    Named.of("searchIndex", r -> r.searchIndex(undeclared)),
                    Named.of("routing", r -> r.routing(undeclared)),
                    Named.of("privacyLevel", r -> r.privacyLevel(undeclared)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("everyResolverMethod")
        @DisplayName("spec: an undeclared domain is refused, naming it, before the catalog is consulted")
        void undeclaredDomainIsRefusedBeforeTheCatalogIsAsked(Consumer<TenantIndexResolver> call) {
            // Not consulting the catalog matters: a lazily-provisioning catalog would otherwise record
            // a placement for a domain nobody checked for overlap. The stub would answer with a
            // well-formed orders placement, so only the declaration check can stop this.
            List<TenantRef> asked = new ArrayList<>();
            TenantCatalog catalog = new TenantCatalog() {
                @Override
                public Placement placementOf(TenantRef tenant) {
                    asked.add(tenant);
                    return InMemoryTenantCatalog.pooled(tenant, PrivacyLevel.NORMAL, KATA_POOLS);
                }

                @Override
                public void update(Placement placement) {
                    throw new UnsupportedOperationException();
                }
            };
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(catalog, SearchDomains.of(LAB_RESULTS));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> call.accept(resolver))
                    .withMessageMatching("(?s).*\\borders\\b.*");
            assertThat(asked).isEmpty();
        }

        @Test
        @DisplayName("two declared, disjoint domains are both served by one resolver")
        void bothDeclaredDomainsAreServed() {
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(
                    new InMemoryTenantCatalog(SearchDomains.of(LAB_RESULTS, ORDERS), KATA_POOLS),
                    SearchDomains.of(ORDERS, LAB_RESULTS));

            assertThat(resolver.writeIndex(TenantRef.of(LAB_RESULTS, "tenant-a"))).isEqualTo("lab-results-pool-2-write");
            assertThat(resolver.writeIndex(TenantRef.of(ORDERS, "tenant-a"))).isEqualTo("orders-pool-2-write");
        }
    }

    @Nested
    @DisplayName("a placement for the wrong tenant")
    class WrongTenant {

        @Test
        @DisplayName("a placement returned for another tenant in the same domain is refused, naming both")
        void placementForAnotherTenantIsRefused() {
            // The targets are in the right family, so the family guard would pass them: tenant-a would
            // write into tenant-b's pool with tenant-a's routing, and read tenant-b's shard.
            TenantRef asked = TenantRef.of(LAB_RESULTS, "tenant-a");
            TenantRef returned = TenantRef.of(LAB_RESULTS, "tenant-b");
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(
                    stubCatalog(InMemoryTenantCatalog.pooled(returned, PrivacyLevel.NORMAL, KATA_POOLS)), DOMAINS);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.writeIndex(asked))
                    .withMessageContaining("tenant-a")
                    .withMessageContaining("tenant-b");
        }

        @Test
        @DisplayName("a placement returned for the same tenant id in another declared domain is refused")
        void placementForTheSameIdInAnotherDomainIsRefused() {
            // The dangerous variant: the family guard checks the placement's own domain, and orders'
            // targets are genuinely orders'. Without comparing the tenant, a lab-results request
            // would resolve into orders-pool-2 and interleave two applications' documents.
            TenantRef asked = TenantRef.of(LAB_RESULTS, "tenant-a");
            TenantRef returned = TenantRef.of(ORDERS, "tenant-a");
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(
                    stubCatalog(InMemoryTenantCatalog.pooled(returned, PrivacyLevel.HIGH, KATA_POOLS)), DOMAINS);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.searchIndex(asked));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> resolver.privacyLevel(asked));
        }
    }

    /** A catalog that answers every lookup with one fixed placement, whoever is asked about. */
    private static TenantCatalog stubCatalog(Placement placement) {
        return new TenantCatalog() {
            @Override
            public Placement placementOf(TenantRef tenant) {
                return placement;
            }

            @Override
            public void update(Placement p) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static TenantIndexResolver resolverFor(Placement placement) {
        TenantCatalog catalog = new InMemoryTenantCatalog(DOMAINS, d -> KATA_POOLS, List.of(placement));
        return new CatalogTenantIndexResolver(catalog, DOMAINS);
    }
}
