package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexResponse;

import java.io.IOException;
import java.util.Objects;

/**
 * Indexes one document at a time.
 *
 * <p>The domain is bound here, once, at construction; the tenant comes from each document. That is
 * why {@link TenantDocument} guarantees both accessors: the id becomes the {@code _id}, which is
 * what makes re-ingesting the same source message overwrite rather than duplicate.
 */
public class DocumentIndexer<T extends TenantDocument> {

    private final OpenSearchClient client;
    private final SearchDomain domain;
    private final TenantIndexResolver resolver;
    private final WriteDocuments<T> documents;

    /** {@code NORMAL}-only: {@code HIGH} tenants are refused rather than written in plaintext. */
    public DocumentIndexer(OpenSearchClient client, SearchDomain domain, TenantIndexResolver resolver) {
        this(client, domain, resolver, WriteDocuments.plaintextOnly(resolver));
    }

    public DocumentIndexer(OpenSearchClient client, SearchDomain domain, TenantIndexResolver resolver,
                           WriteDocuments<T> documents) {
        this.client = Objects.requireNonNull(client, "client");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    /** @return {@code "created"} or {@code "updated"} */
    public String index(T document) throws IOException {
        TenantRef tenant = TenantRef.of(domain, document.tenantId());
        // Sealed before the request is built, so a tenant with no key material leaves nothing
        // behind: the exception escapes with no call made rather than half a document written.
        Object payload = documents.forWrite(tenant, document);
        IndexResponse response = client.index(i -> i
                .index(resolver.writeIndex(tenant))
                .id(document.documentId())                        // idempotent: re-sends do not duplicate
                .routing(resolver.routing(tenant).orElse(null))
                .document(payload));
        return response.result().jsonValue();
    }
}
