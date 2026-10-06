package io.twba.search.toolkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SearchDomain#owns} is the family guard every resolved target passes through, and it is only
 * as trustworthy as the set of domains beside it. Domain {@code lab} owns every name starting
 * {@code lab-} — including {@code lab-results-secure-pool-1}. If two such domains share a control plane,
 * a NORMAL {@code lab} tenant can be resolved into {@code lab-results}' sealed family and the guard
 * waves it through. This class is where that pairing is refused.
 */
class SearchDomainsTest {

    private static final SearchDomain LAB = new SearchDomain("lab");
    private static final SearchDomain LABS = new SearchDomain("labs");
    private static final SearchDomain LAB_RESULTS = new SearchDomain("lab-results");
    private static final SearchDomain ORDERS = new SearchDomain("orders");

    @Nested
    @DisplayName("overlapping domains")
    class Overlapping {

        @Test
        @DisplayName("lab and lab-results cannot be declared together, and the refusal names both")
        void labAndLabResultsRefused() {
            assertThatThrownBy(() -> SearchDomains.of(LAB, LAB_RESULTS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'lab'")
                    .hasMessageContaining("'lab-results'");
        }

        @Test
        @DisplayName("the refusal does not depend on declaration order")
        void refusalIsOrderIndependent() {
            assertThatThrownBy(() -> SearchDomains.of(LAB_RESULTS, LAB))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'lab'")
                    .hasMessageContaining("'lab-results'");
        }

        @Test
        @DisplayName("a domain whose NORMAL family starts inside another's HIGH family is refused")
        void domainInsideAnotherSecureFamilyRefused() {
            // "lab-results-secure" itself is refused by SearchDomain; the next thing along is not, and
            // its every index sits inside lab-results-secure-, the sealed family of lab-results.
            SearchDomain insideSecure = new SearchDomain("lab-results-secure-archive");
            assertThat(insideSecure.indexFamily(PrivacyLevel.NORMAL)).startsWith(LAB_RESULTS.indexFamily(PrivacyLevel.HIGH));

            assertThatThrownBy(() -> SearchDomains.of(LAB_RESULTS, insideSecure))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'lab-results'")
                    .hasMessageContaining("'lab-results-secure-archive'");
        }

        @Test
        @DisplayName("an overlapping pair is found wherever it sits in the declaration")
        void overlapFoundAmongOthers() {
            // Guards against checking only neighbours: the overlapping pair is first and last.
            assertThatThrownBy(() -> SearchDomains.of(List.of(LAB, ORDERS, LABS, LAB_RESULTS)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'lab'")
                    .hasMessageContaining("'lab-results'");
        }

        @Test
        @DisplayName("acceptance is exactly disjoint ownership: no declared domain owns another's names")
        void acceptedIffNoDomainOwnsAnothersNames() {
            // Refusing too much bans legitimate neighbours like lab/labs; refusing too little lets owns()
            // vouch for a foreign index. Both directions are checked against owns() itself.
            List<SearchDomain> candidates = List.of(
                    LAB, LABS, LAB_RESULTS, ORDERS, new SearchDomain("l"), new SearchDomain("lab-x"),
                    new SearchDomain("lab-results-x"), new SearchDomain("lab-results-secure-x"),
                    new SearchDomain("secure-notes"), new SearchDomain("orders-2"), new SearchDomain("lab1"));
            List<String> wrong = new ArrayList<>();

            for (SearchDomain a : candidates) {
                for (SearchDomain b : candidates) {
                    if (a.equals(b)) {
                        continue;
                    }
                    boolean overlaps = ownsAnyNameOf(a, b) || ownsAnyNameOf(b, a);
                    boolean accepted;
                    try {
                        SearchDomains.of(a, b);
                        accepted = true;
                    } catch (IllegalArgumentException refused) {
                        accepted = false;
                    }
                    if (accepted == overlaps) {
                        wrong.add(a + " + " + b + (accepted ? " accepted but overlap" : " refused but disjoint"));
                    }
                }
            }

            assertThat(wrong).isEmpty();
        }

        private static boolean ownsAnyNameOf(SearchDomain owner, SearchDomain other) {
            for (PrivacyLevel level : PrivacyLevel.values()) {
                for (int pool = 0; pool < 3; pool++) {
                    String alias = IndexNames.poolAlias(other, level, pool);
                    for (PrivacyLevel ownerLevel : PrivacyLevel.values()) {
                        if (owner.owns(alias, ownerLevel)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    @Nested
    @DisplayName("disjoint domains")
    class Disjoint {

        @Test
        @DisplayName("lab-results and orders can be declared together")
        void labResultsAndOrdersAccepted() {
            SearchDomains domains = SearchDomains.of(LAB_RESULTS, ORDERS);

            assertThat(domains.all()).containsExactlyInAnyOrder(LAB_RESULTS, ORDERS);
        }

        @Test
        @DisplayName("lab and labs can be declared together: 'labs-' does not start with 'lab-'")
        void prefixWithoutHyphenBoundaryAccepted() {
            SearchDomains domains = SearchDomains.of(LAB, LABS);

            assertThat(domains.all()).containsExactlyInAnyOrder(LAB, LABS);
        }

        @Test
        @DisplayName("declaring the same domain twice is one domain, not an overlap with itself")
        void duplicatesCollapse() {
            SearchDomains domains = SearchDomains.of(ORDERS, new SearchDomain("orders"), LAB_RESULTS);

            assertThat(domains.all()).containsExactlyInAnyOrder(ORDERS, LAB_RESULTS);
        }

        @Test
        @DisplayName("the collection form accepts and refuses like the varargs form")
        void collectionFormBehavesTheSame() {
            assertThat(SearchDomains.of(List.of(LAB_RESULTS, ORDERS)).all()).containsExactlyInAnyOrder(LAB_RESULTS, ORDERS);
            assertThatThrownBy(() -> SearchDomains.of(List.of(LAB_RESULTS, LAB)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("declaration")
    class Declaration {

        @Test
        @DisplayName("a control plane serving no domain is refused")
        void emptyRefused() {
            assertThatThrownBy(SearchDomains::of).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SearchDomains.of(List.of())).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the declared set cannot be altered after the overlap check")
        void declaredSetIsUnmodifiable() {
            // A domain slipped in after construction would never have been checked for overlap.
            SearchDomains domains = SearchDomains.of(LAB_RESULTS);

            assertThatThrownBy(() -> domains.all().add(LAB)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("contains answers for declared and undeclared domains")
        void containsReflectsTheDeclaration() {
            SearchDomains domains = SearchDomains.of(LAB_RESULTS, ORDERS);

            assertThat(domains.contains(new SearchDomain("lab-results"))).isTrue();
            assertThat(domains.contains(LAB)).isFalse();
        }
    }

    @Nested
    @DisplayName("require")
    class Require {

        @Test
        @DisplayName("returns a declared domain")
        void returnsDeclaredDomain() {
            SearchDomains domains = SearchDomains.of(LAB_RESULTS, ORDERS);

            assertThat(domains.require(new SearchDomain("orders"))).isEqualTo(ORDERS);
        }

        @Test
        @DisplayName("refuses an undeclared domain, naming it")
        void refusesUndeclaredDomainNamingIt() {
            // An undeclared domain was never checked for overlap, so owns() cannot be trusted for it.
            SearchDomains domains = SearchDomains.of(LAB_RESULTS, ORDERS);

            assertThatThrownBy(() -> domains.require(LAB))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'lab'");
        }
    }
}
