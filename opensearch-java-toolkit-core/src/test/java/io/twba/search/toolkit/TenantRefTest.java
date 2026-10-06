package io.twba.search.toolkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.twba.search.toolkit.PrivacyLevel.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantRefTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");

    @Test
    @DisplayName("the same tenant id in two domains is two distinct addresses")
    void sameTenantIdInTwoDomainsIsDistinct() {
        TenantRef inLabResults = TenantRef.of(LAB_RESULTS, "lab-a");
        TenantRef inOrders = TenantRef.of(ORDERS, "lab-a");

        assertThat(inLabResults).isNotEqualTo(inOrders);
        assertThat(inLabResults.tenantId()).isEqualTo(inOrders.tenantId());
        assertThat(inLabResults.domainName()).isEqualTo("lab-results");
    }

    @Test
    @DisplayName("equal domain and tenant make equal references, so they key a map")
    void equalReferencesKeyAMap() {
        assertThat(TenantRef.of(LAB_RESULTS, "lab-a"))
                .isEqualTo(new TenantRef(new SearchDomain("lab-results"), "lab-a"))
                .hasSameHashCodeAs(new TenantRef(new SearchDomain("lab-results"), "lab-a"));
    }

    @Test
    @DisplayName("reads as domain/tenant, because it is the subject of most error messages")
    void readsAsDomainSlashTenant() {
        assertThat(TenantRef.of(LAB_RESULTS, "lab-a")).hasToString("lab-results/lab-a");
    }

    @Test
    @DisplayName("refuses a missing or blank tenant id")
    void refusesBlankTenant() {
        assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TenantRef.of(null, "lab-a")).isInstanceOf(NullPointerException.class);
    }

    /**
     * A tenant id becomes an index-name component the moment the tenant is promoted, and promotion can
     * happen long after the id was first used. Each rule here closes one way an id could make its
     * dedicated index the same index as something else. If one goes red, a promotion somewhere writes
     * a tenant's documents into a pool, into the sealed family, or into another tenant's index.
     *
     * <p>Every refusal must name the id and say why: the operator reading it has to rename a tenant,
     * and "invalid" does not tell them what to rename it to.
     */
    @Nested
    @DisplayName("tenant id refusals")
    class Refusals {

        @ParameterizedTest(name = "blank id [{0}]")
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("refuses a blank id, saying it is blank")
        void refusesBlank(String id) {
            assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'" + id + "'")
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("refuses an uppercase id rather than lowercasing it")
        void refusesUppercaseWithoutLowercasing() {
            // Lowercasing on the caller's behalf would make "Clinic" and "clinic" one tenant, so two
            // laboratories would read each other's results.
            assertThatCode(() -> TenantRef.of(LAB_RESULTS, "clinic")).doesNotThrowAnyException();

            assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, "Clinic"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'Clinic'")
                    .hasMessageContaining("not lowercased");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"lab_a", "lab a", "lab.a", "lab/a", "lab*a", "-lab", "lab-", "lab--a", "clínica", "lab-ä"})
        @DisplayName("refuses characters outside lowercase letters, digits and single inner hyphens")
        void refusesCharactersAnIndexNameCannotSafelyCarry(String id) {
            assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'" + id + "'")
                    .hasMessageContaining("lowercase alphanumeric words joined by single hyphens");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"secure", "secure-clinic"})
        @DisplayName("refuses an id whose first segment is 'secure', naming the HIGH family")
        void refusesSecureFirstSegment(String id) {
            // NORMAL tenant "secure-clinic" would be placed at lab-results-secure-clinic: readable
            // patient names inside the family whose mapping promises none.
            assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'" + id + "'")
                    .hasMessageContaining("HIGH family");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"pool", "pool-2"})
        @DisplayName("refuses an id whose first segment is 'pool', naming the shared pool")
        void refusesPoolFirstSegment(String id) {
            // Dedicated tenant "pool-2" would be placed at lab-results-pool-2 — the shared pool itself.
            assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'" + id + "'")
                    .hasMessageContaining("shared pool");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"write", "clinic-write"})
        @DisplayName("refuses an id whose last segment is 'write', naming the write alias")
        void refusesWriteLastSegment(String id) {
            // Tenant "clinic-write"'s alias would be tenant "clinic"'s write alias.
            assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'" + id + "'")
                    .hasMessageContaining("write alias");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"a-000001", "a-000002", "a-1234567", "000001"})
        @DisplayName("refuses an id whose last segment is six or more digits, naming the backing index")
        void refusesGenerationLastSegment(String id) {
            // Tenant "a-000001"'s alias would be tenant "a"'s first backing index; "a-000002" its
            // second after rollover.
            assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'" + id + "'")
                    .hasMessageContaining("backing index");
        }
    }

    /**
     * The rules refuse exact segments, not substrings, and a rollover generation, not any digits. A
     * rule that over-reaches refuses real laboratories' ids — and the kata's own {@code tenant-high-2}.
     */
    @Nested
    @DisplayName("tenant id boundaries")
    class Boundaries {

        @Test
        @DisplayName("five trailing digits are an id; six are a generation")
        void fiveDigitsValidSixRefused() {
            assertThatCode(() -> TenantRef.of(LAB_RESULTS, "a-00001")).doesNotThrowAnyException();

            assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, "a-000001"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a single trailing digit is not a generation")
        void singleTrailingDigitIsValid() {
            assertThat(TenantRef.of(LAB_RESULTS, "tenant-high-2").tenantId()).isEqualTo("tenant-high-2");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"carpool", "pools", "pool1", "insecure-lab", "securely", "lab-secure", "lab-pool",
                "write-lab", "rewrite", "lab-writes", "000001-lab", "lab-000001-x"})
        @DisplayName("a reserved word inside a segment, or in a position it cannot collide from, is valid")
        void reservedWordsElsewhereAreValid(String id) {
            assertThat(TenantRef.of(LAB_RESULTS, id).tenantId()).isEqualTo(id);
        }

        @Test
        @DisplayName("an id whose longest derived name is exactly 255 bytes is valid")
        void longestNameAtTheLimitIsValid() {
            // lab-results-secure-<id>-000001 carries 26 bytes besides the id.
            String id = "a".repeat(255 - 26);
            assertThat("lab-results-secure-" + id + "-000001").hasSize(255);

            assertThatCode(() -> TenantRef.of(LAB_RESULTS, id)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an id whose longest derived name is 256 bytes is refused, naming the domain and the limit")
        void longestNameOverTheLimitIsRefused() {
            String id = "a".repeat(256 - 26);

            assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'" + id + "'")
                    .hasMessageContaining("'lab-results'")
                    .hasMessageContaining("255 bytes");
        }

        @Test
        @DisplayName("the length limit is measured against the tenant's own domain")
        void lengthLimitDependsOnTheDomain() {
            // orders-secure-<id>-000001 carries 21 bytes besides the id, five fewer than lab-results.
            String id = "a".repeat(255 - 21);

            assertThatCode(() -> TenantRef.of(ORDERS, id)).doesNotThrowAnyException();
            assertThatThrownBy(() -> TenantRef.of(LAB_RESULTS, id)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TenantRef.of(ORDERS, id + "a")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a valid id is kept exactly as given")
        void validIdIsNotRewritten() {
            TenantRef ref = TenantRef.of(LAB_RESULTS, "tenant-high-2");

            assertThat(IndexNames.tenantAlias(ref, NORMAL)).isEqualTo("lab-results-tenant-high-2");
        }
    }

    /**
     * Every tenant id the kata's test suites use, found by grepping the string literals of
     * the {@code src/test} trees under {@code opensearch-java-ra-legacy-kata} and keeping those passed as a tenant id
     * (to a resolver, catalog, limiter, key provider, cipher, blind indexer, document factory, or bound
     * as {@code opensearch.tenants.<id>}). The toolkit is stricter than the kata on purpose; this is
     * the evidence that the strictness costs the kata nothing, and the parity suite can reuse its ids.
     */
    @Nested
    @DisplayName("kata tenant ids")
    class KataTenantIds {

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {
                "tenant-a", "tenant-b", "tenant-big", "tenant-high", "tenant-high-2", "tenant-high-keyless",
                "tenant-high-throttled", "tenant-keyless", "tenant-normal", "tenant-other", "tenant-new",
                "tenant-unknown", "unconfigured-tenant", "lab-a", "lab-b"})
        @DisplayName("every tenant id the kata's suites use is accepted")
        void everyKataTenantIdIsAccepted(String id) {
            assertThat(TenantRef.of(LAB_RESULTS, id).tenantId()).isEqualTo(id);
        }
    }
}
