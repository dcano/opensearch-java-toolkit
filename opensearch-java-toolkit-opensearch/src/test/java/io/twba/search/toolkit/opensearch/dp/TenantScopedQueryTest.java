package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The mandatory tenant clause, on its own.
 *
 * <p>Two properties, both invisible to any test that only checks which documents came back: the
 * clause is in <em>filter</em> context, and the field it names is the one {@link TenantDocument}
 * declares rather than a string spelled out a second time here.
 */
class TenantScopedQueryTest {

    private static final TenantRef TENANT = TenantRef.of(new SearchDomain("lab-results"), "clinic-a");

    private static final Query USER_QUERY =
            Query.of(q -> q.term(t -> t.field("note").value(v -> v.stringValue("hemolyzed"))));

    @Test
    @DisplayName("the caller's query is scored and the tenant clause is not")
    void theTenantClauseIsAFilter() {
        BoolQuery scoped = TenantScopedQuery.scope(TENANT, USER_QUERY).bool();

        assertThat(scoped.must()).containsExactly(USER_QUERY);
        assertThat(scoped.filter()).singleElement().satisfies(clause -> {
            assertThat(clause.term().field()).isEqualTo(TenantDocument.TENANT_ID_FIELD);
            assertThat(clause.term().value().stringValue()).isEqualTo("clinic-a");
        });
    }

    @Test
    @DisplayName("only the tenant id reaches the cluster: the domain is already in the index name")
    void theDomainIsNotInTheClause() {
        String json = TenantScopedQuery.scope(TENANT, USER_QUERY).toJsonString();

        assertThat(json).contains("clinic-a").doesNotContain("lab-results");
    }

    @Test
    @DisplayName("a null tenant or query is refused rather than producing an unscoped search")
    void nullsAreRefused() {
        // The failure mode this prevents is the quiet one: a query with no filter clause is a
        // perfectly valid search across every tenant in the index.
        assertThatNullPointerException().isThrownBy(() -> TenantScopedQuery.scope(null, USER_QUERY));
        assertThatNullPointerException().isThrownBy(() -> TenantScopedQuery.scope(TENANT, null));
    }
}
