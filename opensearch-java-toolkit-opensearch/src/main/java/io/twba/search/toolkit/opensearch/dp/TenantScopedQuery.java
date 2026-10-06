package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import org.opensearch.client.opensearch._types.query_dsl.Query;

import java.util.Objects;

/**
 * Wraps any caller-built query in the mandatory tenant filter.
 *
 * <p>{@code filter}, never {@code must}: the clause decides membership, not relevance, so it skips
 * scoring and uses the filter cache. A tenant clause in scoring context would also make a document's
 * rank depend on how many documents its tenant owns, which is both meaningless and a slow leak of
 * one tenant's size to another.
 *
 * <p>The field name comes from {@link TenantDocument}, so the clause a search filters on and the
 * value the write path stored are the same string by construction rather than by discipline.
 */
public final class TenantScopedQuery {

    private TenantScopedQuery() {
    }

    /**
     * @param tenant the addressed tenant; only its id reaches the cluster, because the domain is
     *               already expressed by the index the request is sent to
     */
    public static Query scope(TenantRef tenant, Query userQuery) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(userQuery, "userQuery");
        return Query.of(q -> q.bool(b -> b
                .must(userQuery)
                .filter(f -> f.term(t -> t
                        .field(TenantDocument.TENANT_ID_FIELD)
                        .value(v -> v.stringValue(tenant.tenantId()))))));
    }
}
