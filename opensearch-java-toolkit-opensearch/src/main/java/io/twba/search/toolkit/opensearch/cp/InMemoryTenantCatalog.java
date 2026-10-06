package io.twba.search.toolkit.opensearch.cp;

import io.twba.search.toolkit.IndexNames;
import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.TenantRef;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import java.util.Objects;

/**
 * In-memory adapter for the control-plane catalog: a stand-in for the provisioning database.
 *
 * <p>Unknown {@code (domain, tenant)} pairs are provisioned lazily on first read as {@code POOLED}
 * and {@code NORMAL}, with the pool picked by hashing the tenant id. Once written, the placement is
 * a <em>record</em>, so the control plane can move that one tenant ({@link PoolReassigner},
 * {@link TenantTierMigrator}) without reshuffling anyone else.
 *
 * <p>{@code NORMAL} is the lazy default on purpose. {@code HIGH} privacy needs key material, and a
 * tenant that acquired it by accident would fail closed at index time for want of a key; opting in
 * is a deliberate act of provisioning, never a side effect of first contact.
 *
 * <p>Serves only the domains it was declared with. A {@link SearchDomains} has checked that no two
 * of them overlap, which is what makes a target's family unambiguous; a tenant in an undeclared
 * domain was never part of that check, so it is refused rather than lazily provisioned.
 *
 * <p>The pool count is asked for per domain. It is deployment configuration rather than part of a
 * domain's identity, but it is not safe to assume one count fits every domain sharing this catalog:
 * a domain provisioned with two pools, placed by a catalog that believes in four, would be routed
 * to indices that do not exist.
 */
public class InMemoryTenantCatalog implements TenantCatalog {

    private final Map<TenantRef, Placement> placements = new ConcurrentHashMap<>();
    private final SearchDomains domains;
    private final ToIntFunction<SearchDomain> poolCount;
    private final TenantDocumentCensus census;

    /** Every domain shares one pool count; every unknown pair gets a hashed POOLED, NORMAL placement. */
    public InMemoryTenantCatalog(SearchDomains domains, int poolCount) {
        this(domains, domain -> poolCount);
    }

    public InMemoryTenantCatalog(SearchDomains domains, ToIntFunction<SearchDomain> poolCount) {
        this(domains, poolCount, List.of());
    }

    /** Seeds deliberate placements, e.g. known-large tenants spread across pools at provisioning. */
    public InMemoryTenantCatalog(SearchDomains domains, ToIntFunction<SearchDomain> poolCount, List<Placement> seed) {
        this(domains, poolCount, seed, TenantDocumentCensus.assumeOccupied());
    }

    /**
     * Seeds placements and supplies the census consulted before a privacy-level change. With the
     * default {@link TenantDocumentCensus#assumeOccupied()} every flip on a STABLE tenant is refused,
     * which is the correct posture until something can actually count documents.
     */
    public InMemoryTenantCatalog(SearchDomains domains, ToIntFunction<SearchDomain> poolCount,
                                 List<Placement> seed, TenantDocumentCensus census) {
        this.domains = Objects.requireNonNull(domains, "domains");
        this.poolCount = Objects.requireNonNull(poolCount, "poolCount");
        this.census = Objects.requireNonNull(census, "census");
        seed.forEach(p -> placements.put(declared(p.tenant()), p));
    }

    @Override
    public Placement placementOf(TenantRef tenant) {
        return placements.computeIfAbsent(declared(tenant),
                t -> pooled(t, PrivacyLevel.NORMAL, poolCount.applyAsInt(t.domain())));
    }

    @Override
    public void update(Placement placement) {
        declared(placement.tenant());
        Placement before = placements.get(placement.tenant());
        if (before != null && before.privacyLevel() != placement.privacyLevel()) {
            rejectDirectFlip(before, placement);
        }
        placements.put(placement.tenant(), placement);
    }

    /**
     * A level change is a reindex migration, not a catalog edit: the two families store different
     * documents, so flipping the pointer would leave every existing document in the family the
     * tenant no longer reads from. Refused while the tenant is STABLE and occupied; a migration in
     * flight (BACKFILLING / DUAL_READ) is precisely the workflow that is allowed to move the level,
     * because it is also moving the data.
     */
    private void rejectDirectFlip(Placement before, Placement after) {
        if (before.state() != MigrationState.STABLE) {
            return;
        }
        if (!census.hasDocuments(before.tenant())) {
            return;   // nothing to strand: an empty tenant may be reclassified freely
        }
        SearchDomain domain = before.tenant().domain();
        throw new IllegalStateException(
                ("cannot change tenant '%s' in domain '%s' from %s to %s privacy directly: its documents "
                        + "live in the %s* family and would be stranded. Run the privacy-level reindex "
                        + "migration, which moves the data and advances the placement through BACKFILLING "
                        + "before the level changes.")
                        .formatted(before.tenantId(), domain, before.privacyLevel(), after.privacyLevel(),
                                domain.indexFamily(before.privacyLevel())));
    }

    private TenantRef declared(TenantRef tenant) {
        domains.require(tenant.domain());
        return tenant;
    }

    /**
     * The pooled placement for a tenant at a given privacy level — the shape used both for lazy
     * provisioning and for seeding a {@code HIGH} tenant into the secure family.
     *
     * <p>The pool is chosen from the tenant id's hash alone, not the {@code TenantRef}'s, so a tenant
     * lands in the same pool number in every domain and matches the placement the implementation
     * this generalizes produced.
     */
    public static Placement pooled(TenantRef tenant, PrivacyLevel privacyLevel, int poolCount) {
        if (poolCount <= 0) {
            throw new IllegalStateException(
                    "pool count for domain '%s' must be positive, was %d".formatted(tenant.domain(), poolCount));
        }
        String pool = IndexNames.poolAlias(tenant.domain(), privacyLevel,
                Math.floorMod(tenant.tenantId().hashCode(), poolCount));
        return new Placement(
                tenant,
                Tier.POOLED,
                IndexNames.writeAlias(pool),   // rollover alias
                List.of(pool),
                true,                   // pooled tenants are routed to one shard
                MigrationState.STABLE,
                privacyLevel);
    }
}
