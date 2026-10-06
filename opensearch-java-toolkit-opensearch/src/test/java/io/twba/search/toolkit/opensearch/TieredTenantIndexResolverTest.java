package io.twba.search.toolkit.opensearch;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The stateless-catalog resolver still has to learn the same rule as the catalog-backed one: the
 * privacy level picks the family, the tier picks the shape — and now the domain picks the name the
 * family descends from.
 *
 * <p>The literal names are the kata's, pinned for the parity suite.
 */
class TieredTenantIndexResolverTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");
    private static final SearchDomains DOMAINS = SearchDomains.of(LAB_RESULTS, ORDERS);

    @Nested
    @DisplayName("the kata's names")
    class KataNames {

        @Test
        @DisplayName("NORMAL tenants resolve to the same names as the kata")
        void normalTenantsUnchanged() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");
            TieredTenantIndexResolver resolver = resolverFor(new TenantPlacement(tenant, Tier.POOLED, 2));

            assertThat(resolver.searchIndex(tenant)).isEqualTo("lab-results-pool-2");
            assertThat(resolver.writeIndex(tenant)).isEqualTo("lab-results-pool-2-write");
            assertThat(resolver.privacyLevel(tenant)).isEqualTo(PrivacyLevel.NORMAL);
            assertThat(resolver.routing(tenant)).contains("tenant-a");
        }

        @Test
        @DisplayName("a NORMAL dedicated tenant gets its own index in the plaintext family, unrouted")
        void normalDedicatedTenant() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-big");
            TieredTenantIndexResolver resolver = resolverFor(new TenantPlacement(tenant, Tier.DEDICATED, 0));

            assertThat(resolver.searchIndex(tenant)).isEqualTo("lab-results-tenant-big");
            assertThat(resolver.writeIndex(tenant)).isEqualTo("lab-results-tenant-big-write");
            assertThat(resolver.routing(tenant)).isEmpty();
        }

        @Test
        @DisplayName("HIGH tenants resolve into the secure family, pooled or dedicated")
        void highTenantsResolveToSecureFamily() {
            TenantRef high = TenantRef.of(LAB_RESULTS, "tenant-high");
            TenantRef big = TenantRef.of(LAB_RESULTS, "tenant-big");
            TieredTenantIndexResolver pooled = resolverFor(
                    new TenantPlacement(high, Tier.POOLED, 2, PrivacyLevel.HIGH));
            TieredTenantIndexResolver dedicated = resolverFor(
                    new TenantPlacement(big, Tier.DEDICATED, 0, PrivacyLevel.HIGH));

            assertThat(pooled.searchIndex(high)).isEqualTo("lab-results-secure-pool-2");
            assertThat(pooled.writeIndex(high)).isEqualTo("lab-results-secure-pool-2-write");
            assertThat(pooled.privacyLevel(high)).isEqualTo(PrivacyLevel.HIGH);
            assertThat(dedicated.searchIndex(big)).isEqualTo("lab-results-secure-tenant-big");
            assertThat(dedicated.writeIndex(big)).isEqualTo("lab-results-secure-tenant-big-write");
        }

        @Test
        @DisplayName("the first secure pool keeps the kata's name")
        void firstSecurePoolKeepsTheKatasName() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-high");
            TieredTenantIndexResolver resolver = resolverFor(new TenantPlacement(tenant, Tier.POOLED, 0, PrivacyLevel.HIGH));

            assertThat(resolver.searchIndex(tenant)).isEqualTo("lab-results-secure-pool-0");
            assertThat(resolver.writeIndex(tenant)).isEqualTo("lab-results-secure-pool-0-write");
        }

        @Test
        @DisplayName("routing follows the tier, not the privacy level")
        void routingIsUnchangedByPrivacy() {
            TenantRef high = TenantRef.of(LAB_RESULTS, "tenant-high");
            TenantRef big = TenantRef.of(LAB_RESULTS, "tenant-big");

            assertThat(resolverFor(new TenantPlacement(high, Tier.POOLED, 1, PrivacyLevel.HIGH))
                    .routing(high)).contains("tenant-high");
            assertThat(resolverFor(new TenantPlacement(big, Tier.DEDICATED, 0, PrivacyLevel.HIGH))
                    .routing(big)).isEmpty();
        }
    }

    @Nested
    @DisplayName("tenants in two domains")
    class TwoDomains {

        @Test
        @DisplayName("names descend from the tenant's domain, for pooled and dedicated alike")
        void namesDescendFromTheDomain() {
            TenantRef pooled = TenantRef.of(ORDERS, "lab-a");
            TenantRef dedicated = TenantRef.of(ORDERS, "tenant-big");

            assertThat(resolverFor(new TenantPlacement(pooled, Tier.POOLED, 3)).writeIndex(pooled))
                    .isEqualTo("orders-pool-3-write");
            assertThat(resolverFor(new TenantPlacement(dedicated, Tier.DEDICATED, 0, PrivacyLevel.HIGH))
                    .searchIndex(dedicated)).isEqualTo("orders-secure-tenant-big");
        }

        @Test
        @DisplayName("spec: the same tenant id in two domains resolves independently, privacy included")
        void sameTenantIdInTwoDomainsIsIndependent() {
            TenantRef inLabResults = TenantRef.of(LAB_RESULTS, "lab-a");
            TenantRef inOrders = TenantRef.of(ORDERS, "lab-a");
            TenantPlacementCatalogRepository repository = tenant -> tenant.domain().equals(LAB_RESULTS)
                    ? new TenantPlacement(tenant, Tier.POOLED, 1, PrivacyLevel.HIGH)
                    : new TenantPlacement(tenant, Tier.POOLED, 1, PrivacyLevel.NORMAL);
            TieredTenantIndexResolver resolver = new TieredTenantIndexResolver(repository, DOMAINS);

            assertThat(resolver.privacyLevel(inLabResults)).isEqualTo(PrivacyLevel.HIGH);
            assertThat(resolver.writeIndex(inLabResults)).isEqualTo("lab-results-secure-pool-1-write");
            assertThat(resolver.privacyLevel(inOrders)).isEqualTo(PrivacyLevel.NORMAL);
            assertThat(resolver.writeIndex(inOrders)).isEqualTo("orders-pool-1-write");
        }

        @Test
        @DisplayName("the routing value is the bare tenant id, not domain/tenant")
        void routingValueIsTheBareTenantId() {
            // A domain-qualified routing value would shard the same documents differently from the
            // kata, and routed searches would miss what was indexed under the bare id.
            TenantRef tenant = TenantRef.of(ORDERS, "lab-a");

            assertThat(resolverFor(new TenantPlacement(tenant, Tier.POOLED, 1)).routing(tenant))
                    .isEqualTo(Optional.of("lab-a"));
        }
    }

    @Nested
    @DisplayName("family integrity")
    class FamilyIntegrity {

        /**
         * Valid ids, several of which merely <em>contain</em> a reserved word. The ids that could
         * reshape a name ({@code secure-clinic}, {@code pool-2}) are refused by {@code TenantRef}
         * and tested in core; this resolver applies no guard of its own, so what it must guarantee
         * is that every id core <em>accepts</em> builds names owned by exactly the level asked for.
         */
        static Stream<Arguments> everyShapeAtEveryLevel() {
            List<String> ids = List.of("tenant-a", "insecure-lab", "lab-secure", "securely", "carpool",
                    "tenant-pool", "pools", "rewrite", "clinic-12345");
            Stream.Builder<Arguments> cases = Stream.builder();
            for (String id : ids) {
                for (Tier tier : Tier.values()) {
                    for (PrivacyLevel level : PrivacyLevel.values()) {
                        cases.add(Arguments.of(id, tier, level));
                    }
                }
            }
            return cases.build();
        }

        @ParameterizedTest(name = "{0} {1} {2}")
        @MethodSource("everyShapeAtEveryLevel")
        @DisplayName("spec: every constructed write and search name is owned by its own level and not by the other")
        void constructedNamesAreOwnedByExactlyTheirLevel(String tenantId, Tier tier, PrivacyLevel level) {
            // If this goes red, a NORMAL tenant's readable documents are being written into the HIGH
            // family (or a HIGH tenant's sealed ones read from the plaintext family) — and nothing
            // downstream checks, because this resolver's names are trusted as constructed.
            TenantRef tenant = TenantRef.of(LAB_RESULTS, tenantId);
            PrivacyLevel other = level == PrivacyLevel.HIGH ? PrivacyLevel.NORMAL : PrivacyLevel.HIGH;
            TieredTenantIndexResolver resolver = resolverFor(new TenantPlacement(tenant, tier, 3, level));

            String write = resolver.writeIndex(tenant);
            String search = resolver.searchIndex(tenant);

            assertThat(List.of(write, search)).allSatisfy(name -> {
                assertThat(LAB_RESULTS.owns(name, level)).as("%s owned at %s", name, level).isTrue();
                assertThat(LAB_RESULTS.owns(name, other)).as("%s owned at %s", name, other).isFalse();
            });
        }
    }

    @Nested
    @DisplayName("declared domains")
    class DeclaredDomains {

        static Stream<Named<Consumer<TieredTenantIndexResolver>>> everyResolverMethod() {
            TenantRef undeclared = TenantRef.of(ORDERS, "tenant-a");
            return Stream.of(
                    Named.of("writeIndex", r -> r.writeIndex(undeclared)),
                    Named.of("searchIndex", r -> r.searchIndex(undeclared)),
                    Named.of("routing", r -> r.routing(undeclared)),
                    Named.of("privacyLevel", r -> r.privacyLevel(undeclared)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("everyResolverMethod")
        @DisplayName("spec: an undeclared domain is refused, naming it, even when the repository would answer")
        void undeclaredDomainIsRefused(Consumer<TieredTenantIndexResolver> call) {
            TieredTenantIndexResolver resolver = new TieredTenantIndexResolver(
                    tenant -> new TenantPlacement(tenant, Tier.POOLED, 1), SearchDomains.of(LAB_RESULTS));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> call.accept(resolver))
                    .withMessageMatching("(?s).*\\borders\\b.*");
        }
    }

    private static TieredTenantIndexResolver resolverFor(TenantPlacement placement) {
        return new TieredTenantIndexResolver(tenant -> placement, DOMAINS);
    }
}
