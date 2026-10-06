package io.twba.search.toolkit.opensearch.provisioning;

import io.twba.search.toolkit.IndexNames;
import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * Creates a domain's shared pools, on both privacy families.
 *
 * <p>Both families, always, whether or not the domain currently has a {@code HIGH} tenant. A pool
 * that does not exist when the first one arrives means the first secure write lands on a
 * dynamically-mapped index created by the write itself — which is the one index in the system that
 * must never be created that way, because a dynamic mapping has no {@code dynamic: strict} and
 * therefore no refusal to store a readable value. Provisioning the family up front costs an empty
 * index per pool and removes that path entirely.
 *
 * <p>Each pool is one alias trio: a search alias, a write alias marked as the write index, and a
 * first backing index named for generation one. Writers target the write alias so rollover can move
 * it; readers target the search alias so it can span generations. The names come from
 * {@link IndexNames} rather than being assembled here, which is what keeps them equal to the ones the
 * catalog hands out — a provisioner and a resolver that each built names would agree until one of
 * them was edited.
 *
 * <p>Idempotent by asking whether the pool's <em>write alias</em> exists, not whether its first
 * backing index does. The implementation this generalizes probed the index, which is correct exactly
 * until the first rollover: after one, generation {@code 000001} has been rolled past and eventually
 * deleted while the aliases live on, so the probe answers "no" for a pool that is perfectly healthy.
 * Re-creating it then fails outright — the new index would claim a write alias that already has a
 * write index — and a cluster that had been running long enough to roll over could not be restarted.
 * The alias is the thing that means "this pool is provisioned", and it is the thing that survives.
 */
public class PoolProvisioner {

    private static final Logger log = LoggerFactory.getLogger(PoolProvisioner.class);

    private final OpenSearchClient client;

    public PoolProvisioner(OpenSearchClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * @param poolCount how many shared pools the domain has, numbered from zero, matching the range
     *                  the catalog hashes tenants into. A count that disagreed with the catalog's
     *                  would place tenants in pools nothing created.
     */
    public void provision(SearchDomain domain, int poolCount) throws IOException {
        Objects.requireNonNull(domain, "domain");
        if (poolCount <= 0) {
            throw new IllegalArgumentException(
                    "pool count for domain '%s' must be positive, was %d".formatted(domain, poolCount));
        }
        for (PrivacyLevel level : PrivacyLevel.values()) {
            for (int pool = 0; pool < poolCount; pool++) {
                provisionPool(domain, level, pool);
            }
        }
    }

    private void provisionPool(SearchDomain domain, PrivacyLevel level, int pool) throws IOException {
        String searchAlias = IndexNames.poolAlias(domain, level, pool);
        String writeAlias = IndexNames.writeAlias(searchAlias);
        String index = IndexNames.firstIndex(searchAlias);

        // The write alias rather than the search alias: both are created together, but the write alias
        // is the one whose duplication is fatal, so it is the one worth asking about.
        if (client.indices().existsAlias(e -> e.name(writeAlias)).value()) {
            return;
        }
        client.indices().create(c -> c
                .index(index)
                .aliases(searchAlias, a -> a)
                .aliases(writeAlias, a -> a.isWriteIndex(true)));
        log.info("Provisioned pool {} of domain {} at {} privacy: index {} behind aliases {} and {}",
                pool, domain, level, index, searchAlias, writeAlias);
    }
}
