package io.twba.search.toolkit;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The domains one control plane serves, declared together so they can be checked against each other.
 *
 * <p>Some collisions can only be seen in a set. {@code SearchDomain("lab")} and
 * {@code SearchDomain("lab-results")} are each perfectly valid, but {@code lab-results-pool-1} starts
 * with {@code lab-}, so the family guard for {@code lab} would vouch for an index belonging to
 * {@code lab-results} — including {@code lab-results-secure-pool-1}, which a {@code NORMAL} {@code lab}
 * tenant could then write readable values into. No rule about one name in isolation can refuse that
 * without banning hyphens, which would also ban {@code lab-results}.
 *
 * <p>So the check lives where domains meet. Catalogs and resolvers take a {@code SearchDomains} and
 * refuse any tenant whose domain is not in it, which makes {@link SearchDomain#owns} trustworthy for
 * every domain they will ever be asked about.
 *
 * <p><strong>Its limit, stated plainly:</strong> this sees the domains declared in one process. Two
 * applications that each declare one overlapping domain against the same cluster are not caught here.
 * That is a deployment constraint, like never pointing two builds of the same domain at one cluster.
 */
public final class SearchDomains {

    private final Set<SearchDomain> domains;

    private SearchDomains(Collection<SearchDomain> declared) {
        Set<SearchDomain> set = new LinkedHashSet<>();
        for (SearchDomain domain : declared) {
            set.add(Objects.requireNonNull(domain, "domain"));
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException("a control plane must serve at least one domain");
        }
        List<SearchDomain> list = List.copyOf(set);
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                refuseOverlap(list.get(i), list.get(j));
            }
        }
        this.domains = Set.copyOf(set);
    }

    public static SearchDomains of(SearchDomain... domains) {
        return new SearchDomains(List.of(domains));
    }

    public static SearchDomains of(Collection<SearchDomain> domains) {
        return new SearchDomains(domains);
    }

    public boolean contains(SearchDomain domain) {
        return domains.contains(domain);
    }

    /**
     * Returns {@code domain} if it is declared here, and fails otherwise. An undeclared domain has not
     * been checked against the others, so nothing it claims to own can be trusted.
     */
    public SearchDomain require(SearchDomain domain) {
        Objects.requireNonNull(domain, "domain");
        if (!domains.contains(domain)) {
            throw new IllegalStateException(
                    "domain '%s' is not declared for this control plane (declared: %s); an undeclared domain "
                            .formatted(domain, domains)
                            + "has not been checked for overlap with the others, so its targets cannot be trusted");
        }
        return domain;
    }

    public Set<SearchDomain> all() {
        return domains;
    }

    /**
     * Two families overlap exactly when one domain's name is the other's plus a hyphen and more. The
     * secure families need no separate check: {@code a-secure-} extends {@code a-}, so if the base
     * families are disjoint the secure ones are too.
     */
    private static void refuseOverlap(SearchDomain a, SearchDomain b) {
        SearchDomain shorter = a.name().length() <= b.name().length() ? a : b;
        SearchDomain longer = shorter == a ? b : a;
        String shorterFamily = shorter.indexFamily(PrivacyLevel.NORMAL);
        if (longer.indexFamily(PrivacyLevel.NORMAL).startsWith(shorterFamily)) {
            throw new IllegalArgumentException(
                    ("domains '%s' and '%s' cannot share a control plane: every index of '%s' starts with '%s', "
                            + "the family of '%s', so the family guard could not tell them apart")
                            .formatted(shorter, longer, longer, shorterFamily, shorter));
        }
    }

    @Override
    public String toString() {
        return domains.toString();
    }
}
