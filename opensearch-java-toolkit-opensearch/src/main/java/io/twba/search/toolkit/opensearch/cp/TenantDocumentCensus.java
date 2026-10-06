package io.twba.search.toolkit.opensearch.cp;

import io.twba.search.toolkit.TenantRef;

/**
 * Answers one control-plane question: does this tenant already have documents in the family it is
 * currently placed in, within this domain?
 *
 * <p>Only {@link TenantCatalog#update} asks, and only to decide whether a privacy-level flip would
 * strand data. Counted per {@code (domain, tenant)} rather than rolled up across domains: a flip
 * moves one domain's documents between that domain's two families, so documents the same tenant
 * holds in another domain are irrelevant to whether this flip strands anything.
 *
 * <p>Deliberately a port rather than a count against the cluster: the catalog is control plane and
 * must stay compilable, testable and runnable without a client.
 */
@FunctionalInterface
public interface TenantDocumentCensus {

    boolean hasDocuments(TenantRef tenant);

    /**
     * The safe default. A catalog that cannot prove a tenant is empty must assume it is not, so an
     * unguarded flip is refused rather than allowed on an optimistic guess.
     */
    static TenantDocumentCensus assumeOccupied() {
        return tenant -> true;
    }

    /**
     * Greenfield provisioning and tests only — asserts that no tenant has data anywhere, which makes
     * every privacy-level flip a catalog edit. Never wire this against a live cluster.
     */
    static TenantDocumentCensus assumeEmpty() {
        return tenant -> false;
    }
}
