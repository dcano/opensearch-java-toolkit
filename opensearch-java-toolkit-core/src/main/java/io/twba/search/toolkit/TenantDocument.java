package io.twba.search.toolkit;

/**
 * What generic write-path machinery is permitted to know about an application's document.
 *
 * <p>Everything else about the document — its fields, its meaning, how it should be queried —
 * stays the application's business. Bulk indexing, backpressure, chunk shrinking and the privacy
 * branch need only these two answers, which is why they can be written once for every domain.
 *
 * <p>Two accessors, no {@code get} prefix, matching the record idiom used throughout. The document
 * id is part of the contract rather than an extra argument because the indexers need both: the id
 * becomes the {@code _id}, and that is what makes re-ingestion of the same source message
 * idempotent instead of duplicating. An interface guaranteeing only the tenant would push the id
 * into an id-extractor function passed alongside — the same contract, with a place to forget it.
 */
public interface TenantDocument {

    /**
     * The stored field holding {@link #tenantId()}. Every tenant filter, reindex source query and
     * census count names this field, so it is written down once: a second spelling somewhere is a
     * filter that matches nothing and a migration that copies nothing, both reporting success.
     * It is the accessor's own name, because that is what a record serializes it as.
     */
    String TENANT_ID_FIELD = "tenantId";

    /** The tenant this document belongs to. Decides the index family, the shape and the keys. */
    String tenantId();

    /** The stable identity of this document, used as the {@code _id} so re-ingestion overwrites. */
    String documentId();
}
