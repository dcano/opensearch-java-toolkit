package io.twba.search.toolkit.opensearch.cp;

import io.twba.search.toolkit.IndexNames;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.TenantRef;

import java.util.List;
import java.util.function.ToIntFunction;
import java.util.Objects;

/**
 * Moves one pooled tenant to a different pool within its own family.
 *
 * <p>Takes a pool <em>number</em>, not a name, so the target is built by {@link IndexNames} from the
 * tenant's domain and privacy level: there is no way to hand this class a string that lands the
 * tenant in another domain's pool or in the other privacy family. The number is checked against the
 * domain's pool count, because a pool that was never provisioned is an index writes would fail to
 * reach — the failure per-domain pool counts exist to prevent.
 *
 * <p>Known limit, carried from the implementation this generalizes: a second reassignment keeps only
 * the pool the tenant wrote to before it, so the pool from two moves ago drops out of reads while its
 * generations may still hold documents. Closing that needs a step that prunes a dual-read target once
 * its generations have aged out, which nothing in the toolkit performs yet.
 */
public class PoolReassigner {

    private final TenantCatalog catalog;
    private final ToIntFunction<SearchDomain> poolCount;

    public PoolReassigner(TenantCatalog catalog, int poolCount) {
        this(catalog, domain -> poolCount);
    }

    public PoolReassigner(TenantCatalog catalog, ToIntFunction<SearchDomain> poolCount) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.poolCount = Objects.requireNonNull(poolCount, "poolCount");
    }

    /** New writes go to the new pool; reads span both until old generations age out via ISM. */
    public void reassign(TenantRef tenant, int newPoolNumber) {
        TenantCatalog.Placement p = catalog.placementOf(tenant);
        if (p.tier() != TenantCatalog.Tier.POOLED) {
            throw new IllegalStateException("pooled tenants only");
        }
        int pools = poolCount.applyAsInt(tenant.domain());
        if (newPoolNumber < 0 || newPoolNumber >= pools) {
            throw new IllegalArgumentException(
                    "pool %d does not exist in domain '%s', which has pools 0..%d"
                            .formatted(newPoolNumber, tenant.domain(), pools - 1));
        }

        String newPool = IndexNames.poolAlias(tenant.domain(), p.privacyLevel(), newPoolNumber);
        String newWriteAlias = IndexNames.writeAlias(newPool);
        if (newWriteAlias.equals(p.writeTarget())) {
            // A move to the current pool would dual-read the same index twice and signal a migration
            // that is not happening.
            throw new IllegalArgumentException(
                    "tenant '%s' in domain '%s' already writes to pool %d".formatted(
                            tenant.tenantId(), tenant.domain(), newPoolNumber));
        }
        String oldSearchAlias = p.searchTargets().getFirst();

        catalog.update(new TenantCatalog.Placement(
                tenant,
                TenantCatalog.Tier.POOLED,
                newWriteAlias,
                List.of(newPool, oldSearchAlias),        // dual read
                true,
                // Both targets are routed pools sharing one routing value, so routing is kept: the
                // resolver drops it only for non-STABLE states, where a target may be unrouted.
                TenantCatalog.MigrationState.STABLE,
                p.privacyLevel()));                      // a pool move never changes privacy posture
    }
}
