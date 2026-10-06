package io.twba.search.toolkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.twba.search.toolkit.PrivacyLevel.HIGH;
import static io.twba.search.toolkit.PrivacyLevel.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Index names are where a tenant's data physically lives. Two different inputs producing one name is
 * not a cosmetic bug: it puts a dedicated tenant inside a shared pool, or readable values inside the
 * sealed family, and nothing fails — the cluster simply accepts writes to an index that already exists.
 *
 * <p>The literals below are the kata's; the property tests are the guarantee the grammar exists for.
 */
class IndexNamesTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");

    @Nested
    @DisplayName("kata parity")
    class KataParity {

        @Test
        @DisplayName("a shared pool's alias, write alias and first index are the kata's names")
        void poolNamesMatchTheKata() {
            String alias = IndexNames.poolAlias(LAB_RESULTS, NORMAL, 1);

            assertThat(alias).isEqualTo("lab-results-pool-1");
            assertThat(IndexNames.writeAlias(alias)).isEqualTo("lab-results-pool-1-write");
            assertThat(IndexNames.firstIndex(alias)).isEqualTo("lab-results-pool-1-000001");
        }

        @Test
        @DisplayName("a secure pool's names are the kata's names")
        void securePoolNamesMatchTheKata() {
            String alias = IndexNames.poolAlias(LAB_RESULTS, HIGH, 0);

            assertThat(alias).isEqualTo("lab-results-secure-pool-0");
            assertThat(IndexNames.writeAlias(alias)).isEqualTo("lab-results-secure-pool-0-write");
            assertThat(IndexNames.firstIndex(alias)).isEqualTo("lab-results-secure-pool-0-000001");
        }

        @Test
        @DisplayName("a dedicated HIGH tenant's alias and write alias are the kata's names")
        void dedicatedHighTenantNamesMatchTheKata() {
            String alias = IndexNames.tenantAlias(TenantRef.of(LAB_RESULTS, "tenant-big"), HIGH);

            assertThat(alias).isEqualTo("lab-results-secure-tenant-big");
            assertThat(IndexNames.writeAlias(alias)).isEqualTo("lab-results-secure-tenant-big-write");
        }

        @Test
        @DisplayName("a dedicated NORMAL tenant's names follow the kata's migrator: family + id")
        void dedicatedNormalTenantNamesMatchTheKata() {
            String alias = IndexNames.tenantAlias(TenantRef.of(LAB_RESULTS, "tenant-big"), NORMAL);

            assertThat(alias).isEqualTo("lab-results-tenant-big");
            assertThat(IndexNames.firstIndex(alias)).isEqualTo("lab-results-tenant-big-000001");
        }
    }

    /**
     * The four names that are not index names: the two patterns a template and a policy claim, and
     * the two asset ids they are stored under. They are derived from the same family prefix as the
     * indices, and their failure modes are the quiet ones — a pattern that matches nothing installs
     * cleanly and never applies, and a policy id that collides overwrites another family's retention.
     */
    @Nested
    @DisplayName("patterns, template names and policy ids")
    class ProvisioningNames {

        @Test
        @DisplayName("the index patterns and asset ids are the kata's names")
        void provisioningNamesMatchTheKata() {
            assertThat(IndexNames.indexPattern(LAB_RESULTS, NORMAL)).isEqualTo("lab-results-*");
            assertThat(IndexNames.indexPattern(LAB_RESULTS, HIGH)).isEqualTo("lab-results-secure-*");
            assertThat(IndexNames.poolPattern(LAB_RESULTS, NORMAL)).isEqualTo("lab-results-pool-*");
            assertThat(IndexNames.poolPattern(LAB_RESULTS, HIGH)).isEqualTo("lab-results-secure-pool-*");
            assertThat(IndexNames.templateName(LAB_RESULTS, NORMAL)).isEqualTo("lab-results-template");
            assertThat(IndexNames.templateName(LAB_RESULTS, HIGH)).isEqualTo("lab-results-secure-template");
            assertThat(IndexNames.lifecyclePolicyName(LAB_RESULTS, NORMAL)).isEqualTo("lab-results-lifecycle");
            assertThat(IndexNames.lifecyclePolicyName(LAB_RESULTS, HIGH))
                    .isEqualTo("lab-results-secure-lifecycle");
        }

        @Test
        @DisplayName("the NORMAL index pattern deliberately also matches the HIGH family")
        void theBaseIndexPatternOverlapsTheSecureFamily() {
            // Resolved by template priority, not by a cleverer pattern. If the overlap ever went
            // away the priority arithmetic would become dead code someone would then delete.
            assertThat(matchesGlob("lab-results-secure-pool-0-000001",
                    IndexNames.indexPattern(LAB_RESULTS, NORMAL))).isTrue();
        }

        @Test
        @DisplayName("the NORMAL pool pattern deliberately does not match the HIGH family")
        void theBasePoolPatternIsDisjointFromTheSecureFamily() {
            // A lifecycle policy's last state is a delete. The plaintext family's retention must
            // not reach the sealed family's data, which is usually kept under different rules.
            assertThat(matchesGlob("lab-results-secure-pool-0-000001",
                    IndexNames.poolPattern(LAB_RESULTS, NORMAL))).isFalse();
            assertThat(matchesGlob("lab-results-secure-pool-0-000001",
                    IndexNames.poolPattern(LAB_RESULTS, HIGH))).isTrue();
        }

        @Test
        @DisplayName("another domain's names are claimed by neither of this domain's patterns")
        void anotherDomainIsNotClaimed() {
            SearchDomain orders = new SearchDomain("orders");

            assertThat(matchesGlob("orders-pool-0-000001", IndexNames.indexPattern(LAB_RESULTS, NORMAL)))
                    .isFalse();
            assertThat(matchesGlob("lab-results-pool-0-000001", IndexNames.poolPattern(orders, NORMAL)))
                    .isFalse();
        }

        @Test
        @DisplayName("a missing domain or level is refused rather than rendered as 'null'")
        void nullsAreRefusedByEveryProvisioningName() {
            assertThatThrownBy(() -> IndexNames.indexPattern(null, NORMAL)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.indexPattern(LAB_RESULTS, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.poolPattern(null, NORMAL)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.poolPattern(LAB_RESULTS, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.templateName(null, NORMAL)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.templateName(LAB_RESULTS, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.lifecyclePolicyName(null, NORMAL)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.lifecyclePolicyName(LAB_RESULTS, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("argument checks")
    class ArgumentChecks {

        @Test
        @DisplayName("pool zero is a pool; the kata numbers pools from zero")
        void poolZeroIsAccepted() {
            assertThat(IndexNames.poolAlias(LAB_RESULTS, NORMAL, 0)).isEqualTo("lab-results-pool-0");
        }

        @Test
        @DisplayName("a negative pool number is refused, naming the number")
        void negativePoolNumberIsRefused() {
            // "pool--1" would be a name no other rule was written to keep disjoint.
            assertThatThrownBy(() -> IndexNames.poolAlias(LAB_RESULTS, NORMAL, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("-1");
        }

        @Test
        @DisplayName("a missing domain, level, tenant or alias is refused rather than rendered as 'null'")
        void nullsAreRefused() {
            TenantRef tenant = TenantRef.of(LAB_RESULTS, "tenant-a");

            assertThatThrownBy(() -> IndexNames.poolAlias(null, NORMAL, 1)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.poolAlias(LAB_RESULTS, null, 1)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.tenantAlias(null, NORMAL)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.tenantAlias(tenant, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.writeAlias(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> IndexNames.firstIndex(null)).isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * The guarantee, checked exhaustively rather than by example. Tenant ids are every combination of
     * up to three segments drawn from a vocabulary chosen to be hostile — the reserved words, generation
     * shapes, digits that look like pool numbers, and near-misses of all of them. Whatever
     * {@link TenantRef} accepts must produce names no other input produces.
     *
     * <p>Dropping any one refusal rule from {@code TenantRef} lets an id through whose name collides —
     * {@code secure-a}, {@code pool-1}, {@code a-write}, {@code a-000001} — and this goes red.
     */
    @Nested
    @DisplayName("names cannot be reshaped by their inputs")
    class Unambiguous {

        private static final List<String> SEGMENTS = List.of(
                "a", "b", "1", "2", "12345", "00001",
                "000001", "000002", "1234567",
                "pool", "secure", "write",
                "carpool", "pools", "insecure", "securely", "writes", "rewrite");

        private static final int[] POOLS = {0, 1, 2, 12345, 123456, 1234567, Integer.MAX_VALUE};

        @Test
        @DisplayName("within one domain, every derived name has exactly one origin")
        void everyDerivedNameHasOneOrigin() {
            Map<String, String> origins = new LinkedHashMap<>();
            List<String> collisions = new ArrayList<>();

            for (PrivacyLevel level : PrivacyLevel.values()) {
                for (int pool : POOLS) {
                    String alias = IndexNames.poolAlias(LAB_RESULTS, level, pool);
                    record(origins, collisions, alias, level + " pool " + pool + " alias");
                    record(origins, collisions, IndexNames.writeAlias(alias), level + " pool " + pool + " write alias");
                    record(origins, collisions, IndexNames.firstIndex(alias), level + " pool " + pool + " first index");
                }
                for (TenantRef tenant : acceptedTenants()) {
                    String alias = IndexNames.tenantAlias(tenant, level);
                    record(origins, collisions, alias, level + " tenant " + tenant.tenantId() + " alias");
                    record(origins, collisions, IndexNames.writeAlias(alias), level + " tenant " + tenant.tenantId() + " write alias");
                    record(origins, collisions, IndexNames.firstIndex(alias), level + " tenant " + tenant.tenantId() + " first index");
                }
            }

            assertThat(collisions).as("names derived from two different inputs").isEmpty();
        }

        @Test
        @DisplayName("every name derived at a level is owned by that level and not by the other")
        void everyDerivedNameIsOwnedByItsOwnLevelOnly() {
            // The resolver's family guard is SearchDomain.owns. A name built for NORMAL that HIGH owns
            // (or the reverse) passes the guard at the wrong level: readable values in the sealed family.
            List<String> misowned = new ArrayList<>();

            for (PrivacyLevel level : PrivacyLevel.values()) {
                PrivacyLevel other = level == NORMAL ? HIGH : NORMAL;
                List<String> names = new ArrayList<>();
                for (int pool : POOLS) {
                    names.addAll(derived(IndexNames.poolAlias(LAB_RESULTS, level, pool)));
                }
                for (TenantRef tenant : acceptedTenants()) {
                    names.addAll(derived(IndexNames.tenantAlias(tenant, level)));
                }
                for (String name : names) {
                    if (!LAB_RESULTS.owns(name, level) || LAB_RESULTS.owns(name, other)) {
                        misowned.add(level + ": " + name);
                    }
                }
            }

            assertThat(misowned).as("names not owned by exactly the level they were built for").isEmpty();
        }

        @Test
        @DisplayName("every derived name is claimed by its own level's index pattern")
        void everyDerivedNameIsClaimedByItsTemplatePattern() {
            // A template whose pattern misses part of its own family installs cleanly and never
            // applies to those indices; the first write to one creates it dynamically mapped —
            // which on the secure family means it will accept a readable value.
            List<String> unclaimed = new ArrayList<>();

            for (PrivacyLevel level : PrivacyLevel.values()) {
                String pattern = IndexNames.indexPattern(LAB_RESULTS, level);
                for (String name : everyDerivedNameAt(level)) {
                    if (!matchesGlob(name, pattern)) {
                        unclaimed.add(level + ": " + name);
                    }
                }
            }

            assertThat(unclaimed).as("names their own family's template pattern does not match").isEmpty();
        }

        @Test
        @DisplayName("a pool pattern claims every pool name and no tenant's name at all")
        void thePoolPatternClaimsPoolsOnly() {
            // Lifecycle actions roll over and eventually delete. A dedicated tenant's index is not
            // a pool generation, and a tenant id that could slip into the pool pattern would have
            // its own index rolled and, four hundred days later, dropped.
            List<String> misclaimed = new ArrayList<>();

            for (PrivacyLevel level : PrivacyLevel.values()) {
                String pattern = IndexNames.poolPattern(LAB_RESULTS, level);
                for (int pool : POOLS) {
                    for (String name : derived(IndexNames.poolAlias(LAB_RESULTS, level, pool))) {
                        if (!matchesGlob(name, pattern)) {
                            misclaimed.add("pool name missed: " + name);
                        }
                    }
                }
                for (TenantRef tenant : acceptedTenants()) {
                    for (String name : derived(IndexNames.tenantAlias(tenant, level))) {
                        if (matchesGlob(name, pattern)) {
                            misclaimed.add("tenant name claimed: " + name);
                        }
                    }
                }
            }

            assertThat(misclaimed).as("pool-pattern mismatches").isEmpty();
        }

        @Test
        @DisplayName("the hostile vocabulary still yields valid ids, so the properties are not vacuous")
        void vocabularyIsNotVacuous() {
            assertThat(acceptedTenants())
                    .extracting(TenantRef::tenantId)
                    .hasSizeGreaterThan(1000)
                    .contains("a", "a-1", "carpool", "insecure-a", "a-secure", "a-pool", "write-a", "a-12345",
                            "000001-a", "a-write-b", "securely", "a-2");
        }

        private static List<TenantRef> acceptedTenants() {
            List<String> ids = new ArrayList<>(SEGMENTS);
            for (String s1 : SEGMENTS) {
                for (String s2 : SEGMENTS) {
                    ids.add(s1 + "-" + s2);
                    for (String s3 : SEGMENTS) {
                        ids.add(s1 + "-" + s2 + "-" + s3);
                    }
                }
            }
            List<TenantRef> accepted = new ArrayList<>();
            for (String id : ids) {
                try {
                    accepted.add(TenantRef.of(LAB_RESULTS, id));
                } catch (IllegalArgumentException refused) {
                    // Refused ids produce no names; that is the point of refusing them.
                }
            }
            return accepted;
        }

        private static List<String> derived(String alias) {
            return List.of(alias, IndexNames.writeAlias(alias), IndexNames.firstIndex(alias));
        }

        private static List<String> everyDerivedNameAt(PrivacyLevel level) {
            List<String> names = new ArrayList<>();
            for (int pool : POOLS) {
                names.addAll(derived(IndexNames.poolAlias(LAB_RESULTS, level, pool)));
            }
            for (TenantRef tenant : acceptedTenants()) {
                names.addAll(derived(IndexNames.tenantAlias(tenant, level)));
            }
            return names;
        }

        private static void record(Map<String, String> origins, List<String> collisions, String name, String origin) {
            String previous = origins.putIfAbsent(name, origin);
            if (previous != null) {
                collisions.add(name + " <- " + previous + " AND " + origin);
            }
        }
    }

    /**
     * OpenSearch index patterns are globs. Only {@code *} is meaningful in the patterns this class
     * builds, and every other character is literal — including the hyphens the grammar joins with.
     */
    private static boolean matchesGlob(String name, String glob) {
        String regex = java.util.Arrays.stream(glob.split("\\*", -1))
                .map(java.util.regex.Pattern::quote)
                .reduce((left, right) -> left + ".*" + right)
                .orElseThrow();
        return name.matches(regex);
    }
}
