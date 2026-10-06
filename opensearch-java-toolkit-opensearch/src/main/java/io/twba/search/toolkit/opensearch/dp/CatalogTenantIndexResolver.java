package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog;

import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * Resolves targets from the control-plane catalog, and refuses any the catalog got wrong.
 *
 * <p>Holds its own {@link SearchDomains} rather than trusting the catalog to have checked: the catalog
 * is a port, and an adapter backed by a database has no reason to know which domains were declared.
 * The family guard is only as good as {@link SearchDomain#owns}, and {@code owns} is only trustworthy
 * for a domain that was checked for overlap with the others.
 */
public class CatalogTenantIndexResolver implements TenantIndexResolver {

    private final TenantCatalog catalog;
    private final SearchDomains domains;

    public CatalogTenantIndexResolver(TenantCatalog catalog, SearchDomains domains) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.domains = Objects.requireNonNull(domains, "domains");
    }

    @Override
    public String writeIndex(TenantRef tenant) {
        var p = placementOf(tenant);
        return checkFamily(p, List.of(p.writeTarget())).getFirst();
    }

    @Override
    public String searchIndex(TenantRef tenant) {
        var p = placementOf(tenant);
        // Multi-index search is native: comma-joined targets in one request.
        return String.join(",", checkFamily(p, p.searchTargets()));
    }

    @Override
    public Optional<String> routing(TenantRef tenant) {
        var p = placementOf(tenant);
        // Routing is a function of tier, not of privacy: a HIGH pooled tenant routes by tenant id
        // exactly like a NORMAL one, so the secure family gets the same single-shard search.
        //
        // The routing value is the bare tenant id, not the domain-qualified reference. Routing
        // only ever applies inside one index, and indices are already per domain.
        //
        // During DUAL_READ one target may be unrouted (dedicated); dropping routing is CORRECT
        // here — it costs fan-out, never correctness.
        return (p.routed() && p.state() == TenantCatalog.MigrationState.STABLE)
                ? Optional.of(tenant.tenantId())
                : Optional.empty();
    }

    @Override
    public PrivacyLevel privacyLevel(TenantRef tenant) {
        return placementOf(tenant).privacyLevel();
    }

    /**
     * Refuses an undeclared domain before the catalog is consulted, and a placement the catalog
     * returned for some other tenant: either would put the family guard to work on a domain it has
     * no reason to trust.
     */
    private TenantCatalog.Placement placementOf(TenantRef tenant) {
        domains.require(tenant.domain());
        var p = catalog.placementOf(tenant);
        if (!p.tenant().equals(tenant)) {
            throw new IllegalStateException(
                    "the catalog returned a placement for '%s' when asked for '%s'".formatted(p.tenant(), tenant));
        }
        return p;
    }

    /**
     * The last line of defence for the family guarantee. Target names are minted by the control
     * plane, so a {@code HIGH} placement pointing at the plaintext family — or any placement
     * pointing at another domain's indices — is a catalog bug. It is also the kind that ends with
     * readable values in an index that was promised to hold none, or with one application writing
     * into another's. Failing the request is the cheap outcome.
     */
    private static List<String> checkFamily(TenantCatalog.Placement p, List<String> targets) {
        SearchDomain domain = p.tenant().domain();
        PrivacyLevel level = p.privacyLevel();
        for (String target : targets) {
            if (!domain.owns(target, level)) {
                throw new IllegalStateException(
                        "tenant '%s' in domain '%s' is %s privacy but its placement targets '%s', which is not in the %s* family"
                                .formatted(p.tenantId(), domain, level, target, domain.indexFamily(level)));
            }
        }
        return targets;
    }
}
