package io.twba.search.toolkit.opensearch;

import io.twba.search.toolkit.IndexNames;
import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Tier;

import java.util.Optional;
import java.util.Objects;

/**
 * Derives targets from tier and pool number rather than reading them from a stored placement.
 *
 * <p>No family guard is applied here, unlike the catalog-backed resolver, and the reason is worth
 * being precise about. Every name is <em>constructed</em> by {@link IndexNames} from a declared domain
 * and a validated {@link TenantRef}, and that grammar is unambiguous: no valid tenant id or pool number
 * yields a name another input also yields, or a name outside the family it was built for. The guard
 * exists where names are <em>stored</em>, because stored names can be wrong.
 *
 * <p>That was not always true. Before tenant ids were validated, a {@code NORMAL} dedicated tenant
 * named {@code secure-clinic} was built into the {@code HIGH} family, and one named {@code pool-2}
 * into a shared pool — both by this class, both silently.
 */
public class TieredTenantIndexResolver implements TenantIndexResolver {

    private final TenantPlacementCatalogRepository catalog;
    private final SearchDomains domains;

    public TieredTenantIndexResolver(TenantPlacementCatalogRepository catalog, SearchDomains domains) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.domains = Objects.requireNonNull(domains, "domains");
    }

    @Override
    public String writeIndex(TenantRef tenant) {
        return IndexNames.writeAlias(searchIndex(tenant));   // rollover alias
    }

    @Override
    public String searchIndex(TenantRef tenant) {
        var p = retrieve(tenant);
        // The privacy level picks the family; the tier picks the shape within it. HIGH is not a
        // third tier — a HIGH tenant is pooled or dedicated exactly as a NORMAL one is.
        return switch (p.tier()) {
            case POOLED -> IndexNames.poolAlias(tenant.domain(), p.privacyLevel(), p.poolNumber());
            case DEDICATED -> IndexNames.tenantAlias(tenant, p.privacyLevel());
        };
    }

    @Override
    public Optional<String> routing(TenantRef tenant) {
        // Unchanged by privacy: routing follows the tier on both families.
        return retrieve(tenant).tier() == Tier.POOLED
                ? Optional.of(tenant.tenantId())   // route pooled tenants to one shard
                : Optional.empty();
    }

    @Override
    public PrivacyLevel privacyLevel(TenantRef tenant) {
        return retrieve(tenant).privacyLevel();
    }

    private TenantPlacement retrieve(TenantRef tenant) {
        domains.require(tenant.domain());
        return catalog.retrieve(tenant);
    }
}
