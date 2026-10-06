package io.twba.search.toolkit.opensearch.cp;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.TenantRef;

import java.util.List;
import java.util.Objects;

/**
 * The control plane's single source of truth for where a tenant's documents live, per domain.
 *
 * <p>Keyed on {@link TenantRef}, so the same tenant id in two domains is two independent entries
 * with two independent privacy levels. Nothing derives a placement from a property file or a naming
 * convention: a second source would eventually disagree with the catalog, and the disagreement
 * would look like plaintext in a family that promised to hold none.
 */
public interface TenantCatalog {

    enum Tier { POOLED, DEDICATED }

    enum MigrationState { STABLE, BACKFILLING, DUAL_READ }

    /**
     * Where one tenant's documents live within one domain, and in what shape.
     *
     * @param tenant        the (domain, tenant) this placement addresses
     * @param tier          pooled or dedicated; independent of privacy — {@code HIGH} is not a tier
     * @param writeTarget   the write alias: a pool's or the tenant's own
     * @param searchTargets usually one; two while a migration or reassignment is dual-reading
     * @param routed        true for pooled tenants, which are routed to one shard
     * @param state         whether a migration is in flight
     * @param privacyLevel  decides the family every target above must belong to
     */
    record Placement(
            TenantRef tenant,
            Tier tier,
            String writeTarget,
            List<String> searchTargets,
            boolean routed,
            MigrationState state,
            PrivacyLevel privacyLevel
    ) {
        public Placement {
            Objects.requireNonNull(tenant, "tenant");
            // No default: a placement that forgot its privacy level would silently be NORMAL,
            // which is exactly the mistake that puts a readable value in the wrong family.
            Objects.requireNonNull(privacyLevel, "privacyLevel");
            searchTargets = List.copyOf(searchTargets);
        }

        public String tenantId() {
            return tenant.tenantId();
        }
    }

    Placement placementOf(TenantRef tenant);

    /** A control-plane write. A real adapter audits it: who, when, before and after. */
    void update(Placement placement);
}
