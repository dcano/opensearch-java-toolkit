package io.twba.search.toolkit.opensearch;

import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Tier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import java.util.Objects;

/**
 * Lazily places every unknown {@code (domain, tenant)} pair into a hashed pool.
 *
 * <p>Concurrent, unlike the map it generalizes: {@code computeIfAbsent} on a plain {@code HashMap}
 * under concurrent first reads can lose a placement or corrupt the map, and a resolver is exactly
 * the component every request thread calls first.
 */
public class InMemoryTenantPlacementCatalogRepository implements TenantPlacementCatalogRepository {

    private final Map<TenantRef, TenantPlacement> catalog = new ConcurrentHashMap<>();
    private final SearchDomains domains;
    private final ToIntFunction<SearchDomain> poolCount;

    public InMemoryTenantPlacementCatalogRepository(SearchDomains domains, int poolCount) {
        this(domains, domain -> poolCount);
    }

    public InMemoryTenantPlacementCatalogRepository(SearchDomains domains, ToIntFunction<SearchDomain> poolCount) {
        this.domains = Objects.requireNonNull(domains, "domains");
        this.poolCount = Objects.requireNonNull(poolCount, "poolCount");
    }

    @Override
    public TenantPlacement retrieve(TenantRef tenant) {
        domains.require(tenant.domain());
        return catalog.computeIfAbsent(tenant, this::placementOf);
    }

    private TenantPlacement placementOf(TenantRef tenant) {
        int pools = poolCount.applyAsInt(tenant.domain());
        if (pools <= 0) {
            throw new IllegalStateException(
                    "pool count for domain '%s' must be positive, was %d".formatted(tenant.domain(), pools));
        }
        return new TenantPlacement(tenant, Tier.POOLED, Math.floorMod(tenant.tenantId().hashCode(), pools));
    }
}
