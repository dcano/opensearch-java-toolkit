package io.twba.search.toolkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.twba.search.toolkit.PrivacyLevel.HIGH;
import static io.twba.search.toolkit.PrivacyLevel.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The domain descriptor has one job that matters more than the rest: reproduce, from a name, the
 * exact index families the implementation it generalizes hard-coded. Everything the parity suite
 * will later claim rests on these literals, so they are pinned here where they are cheap to check.
 */
class SearchDomainTest {

    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");

    @Nested
    @DisplayName("index families")
    class IndexFamilies {

        @Test
        @DisplayName("reproduces the families the kata hard-coded")
        void reproducesTheKataFamilies() {
            assertThat(LAB_RESULTS.indexFamily(NORMAL)).isEqualTo("lab-results-");
            assertThat(LAB_RESULTS.indexFamily(HIGH)).isEqualTo("lab-results-secure-");
        }

        @Test
        @DisplayName("a second domain gets families disjoint from the first")
        void secondDomainIsDisjoint() {
            assertThat(ORDERS.indexFamily(NORMAL)).isEqualTo("orders-");
            assertThat(ORDERS.indexFamily(HIGH)).isEqualTo("orders-secure-");

            // Neither family may be a prefix of the other's, at either level: a prefix relation is
            // how one domain's indices end up inside another domain's resolution.
            for (PrivacyLevel mine : PrivacyLevel.values()) {
                for (PrivacyLevel theirs : PrivacyLevel.values()) {
                    assertThat(LAB_RESULTS.indexFamily(mine))
                            .doesNotStartWith(ORDERS.indexFamily(theirs));
                    assertThat(ORDERS.indexFamily(theirs))
                            .doesNotStartWith(LAB_RESULTS.indexFamily(mine));
                }
            }
        }
    }

    @Nested
    @DisplayName("ownership")
    class Ownership {

        @Test
        @DisplayName("each level owns its own family")
        void ownsItsOwnFamily() {
            assertThat(LAB_RESULTS.owns("lab-results-pool-0", NORMAL)).isTrue();
            assertThat(LAB_RESULTS.owns("lab-results-secure-pool-0", HIGH)).isTrue();
        }

        @Test
        @DisplayName("NORMAL does not own a secure target, though it shares the prefix")
        void normalDoesNotOwnSecure() {
            // The whole reason owns() is not a startsWith(): a secure index name begins with the
            // NORMAL family, so the naive test hands a sealed index to the level allowed to write
            // readable values into it.
            assertThat("lab-results-secure-pool-0").startsWith(LAB_RESULTS.indexFamily(NORMAL));
            assertThat(LAB_RESULTS.owns("lab-results-secure-pool-0", NORMAL)).isFalse();
        }

        @Test
        @DisplayName("HIGH does not own a plaintext target")
        void highDoesNotOwnPlaintext() {
            assertThat(LAB_RESULTS.owns("lab-results-pool-0", HIGH)).isFalse();
        }

        @Test
        @DisplayName("a target from another domain is owned by neither level")
        void foreignDomainOwnedByNeither() {
            assertThat(LAB_RESULTS.owns("orders-pool-0", NORMAL)).isFalse();
            assertThat(LAB_RESULTS.owns("orders-pool-0", HIGH)).isFalse();
            assertThat(LAB_RESULTS.owns("orders-secure-pool-0", NORMAL)).isFalse();
            assertThat(LAB_RESULTS.owns("orders-secure-pool-0", HIGH)).isFalse();
        }

        @Test
        @DisplayName("a bare domain name is not one of its own targets")
        void bareNameIsNotATarget() {
            assertThat(LAB_RESULTS.owns("lab-results", NORMAL)).isFalse();
        }
    }

    @Nested
    @DisplayName("name validation")
    class NameValidation {

        @Test
        @DisplayName("rejects names an index cannot carry")
        void rejectsUnusableNames() {
            for (String bad : new String[] {"Lab-Results", "lab_results", "-lab", "lab-", "lab--results", "", "lab results"}) {
                assertThatThrownBy(() -> new SearchDomain(bad))
                        .as("domain name '%s'", bad)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("refuses a name whose NORMAL family would collide with another domain's HIGH family")
        void refusesSecureSuffix() {
            // "lab-results-secure" would produce the NORMAL family "lab-results-secure-", which is
            // byte-identical to the HIGH family of "lab-results". One set of index names, two
            // families claiming them, and one of the two may hold readable values.
            assertThat(new SearchDomain("lab-results").indexFamily(HIGH)).isEqualTo("lab-results-secure-");
            assertThatThrownBy(() -> new SearchDomain("lab-results-secure"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("collides");
        }

        @Test
        @DisplayName("allows 'secure' inside a name where it collides with nothing")
        void allowsSecureElsewhere() {
            assertThat(new SearchDomain("secure-notes").indexFamily(NORMAL)).isEqualTo("secure-notes-");
        }
    }
}
