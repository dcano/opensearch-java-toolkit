package io.twba.search.toolkit.opensearch.cp;

import io.twba.search.toolkit.IndexNames;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Conflicts;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch.core.CountRequest;

import java.io.IOException;
import java.util.List;

/**
 * Promotes one pooled tenant to its own dedicated index, without a read outage.
 *
 * <p>Every index and alias name is built from the tenant's domain and privacy level, so a promotion
 * stays inside the tenant's family: a {@code HIGH} tenant is promoted into {@code <domain>-secure-},
 * never into the plaintext family.
 */
public class TenantTierMigrator {

    private final OpenSearchClient client;
    private final TenantCatalog catalog;

    public TenantTierMigrator(OpenSearchClient client, TenantCatalog catalog) {
        this.client = client;
        this.catalog = catalog;
    }

    public void promoteToDedicated(TenantRef tenant) throws IOException {
        TenantCatalog.Placement before = catalog.placementOf(tenant);
        String tenantId = tenant.tenantId();
        String pool = before.searchTargets().getFirst();

        String searchAlias = IndexNames.tenantAlias(tenant, before.privacyLevel());
        String dedicated = IndexNames.firstIndex(searchAlias);
        String writeAlias = IndexNames.writeAlias(searchAlias);

        // ── Step 0: create the dedicated index (the template supplies mappings) ──
        client.indices().create(c -> c
                .index(dedicated)
                .aliases(searchAlias, a -> a)
                .aliases(writeAlias, a -> a.isWriteIndex(true)));

        // ── Step 1: flip writes; reads span pool + dedicated from this instant ──
        catalog.update(new TenantCatalog.Placement(tenant, TenantCatalog.Tier.DEDICATED, writeAlias,
                List.of(searchAlias, pool), false, TenantCatalog.MigrationState.BACKFILLING,
                before.privacyLevel()));

        // ── Step 2: backfill history from the pool ──
        //  - source query: the tenant filter (reindex source has no routing param; the term
        //    filter is sufficient for a one-off scan)
        //  - dest: 'discard' routing — the dedicated index uses default _id routing
        //  - conflicts=PROCEED: documents already (re)written since step 1 are newer; version
        //    conflicts on them are EXPECTED and must be skipped, not fatal
        client.reindex(r -> r
                .source(s -> s
                        .index(pool)
                        .query(q -> q.term(t -> t
                                .field(TenantDocument.TENANT_ID_FIELD)
                                .value(v -> v.stringValue(tenantId)))))
                .dest(d -> d
                        .index(writeAlias)
                        .routing("discard")
                        .versionType(VersionType.External))
                .conflicts(Conflicts.Proceed)
                .waitForCompletion(true));   // large tenants: false + poll _tasks
        client.indices().refresh(rf -> rf.index(dedicated));

        // ── Step 2b: verify before touching anything ──
        long inPool = count(pool, tenantId, tenantId);          // routed count
        long inDedicated = count(searchAlias, tenantId, null);
        if (inDedicated < inPool) {
            throw new IllegalStateException("backfill incomplete for tenant '%s' in domain '%s': pool=%d dedicated=%d"
                    .formatted(tenantId, tenant.domain(), inPool, inDedicated));   // dual-read still safe; retry
        }

        // ── Step 3: reads → dedicated only; then confine cleanup to the tenant's shard ──
        catalog.update(new TenantCatalog.Placement(tenant, TenantCatalog.Tier.DEDICATED, writeAlias,
                List.of(searchAlias), false, TenantCatalog.MigrationState.STABLE,
                before.privacyLevel()));

        client.deleteByQuery(d -> d
                .index(pool)
                .routing(tenantId)          // deletion work stays on the tenant's shard(s)
                .query(q -> q.term(t -> t
                        .field(TenantDocument.TENANT_ID_FIELD)
                        .value(v -> v.stringValue(tenantId))))
                .waitForCompletion(true));
    }

    private long count(String index, String tenantId, String routing) throws IOException {
        var req = CountRequest.of(c -> {
            c.index(index).query(q -> q.term(t -> t
                    .field(TenantDocument.TENANT_ID_FIELD).value(v -> v.stringValue(tenantId))));
            if (routing != null) {
                c.routing(routing);
            }
            return c;
        });
        return client.count(req).count();
    }
}
