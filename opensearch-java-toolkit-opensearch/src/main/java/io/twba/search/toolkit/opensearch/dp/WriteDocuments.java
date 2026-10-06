package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;

import java.util.Objects;

/**
 * Chooses the shape of the document to write, from the tenant's privacy posture.
 *
 * <p>One place makes this decision so that both indexers — single and bulk — cannot answer it
 * differently, and so the branch is small enough to read in full:
 *
 * <ul>
 *   <li>{@code NORMAL} writes the application's own document, exactly as it is. Not an equivalent
 *       copy, not a re-serialization through a new type: the same object reaches the same client
 *       call, which is what makes "the plaintext path is unchanged" a fact about the code rather
 *       than an assertion in a test.</li>
 *   <li>{@code HIGH} writes what the secure mapper produces, sealed and hashed.</li>
 * </ul>
 *
 * <p>A deployment with no secure mapper wired is a {@code NORMAL}-only deployment, and it refuses
 * {@code HIGH} tenants outright. The alternative — quietly writing the plaintext document — is the
 * one outcome this whole posture exists to prevent, and it would look like success from every angle
 * except the index.
 */
public final class WriteDocuments<T extends TenantDocument> {

    private final TenantIndexResolver resolver;
    private final SecureDocumentMapper<T> secureMapper;

    public WriteDocuments(TenantIndexResolver resolver, SecureDocumentMapper<T> secureMapper) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.secureMapper = secureMapper;   // null is meaningful: a NORMAL-only deployment
    }

    /** For deployments with no {@code HIGH} tenants: any encountered is refused, not downgraded. */
    public static <T extends TenantDocument> WriteDocuments<T> plaintextOnly(TenantIndexResolver resolver) {
        return new WriteDocuments<>(resolver, null);
    }

    /**
     * @param tenant   the addressed tenant; its domain decides nothing here, but its privacy level
     *                 decides everything
     * @param document the application's document, whose tenant id must be the one addressed
     * @return the payload to index — the document itself, or its sealed counterpart
     */
    public Object forWrite(TenantRef tenant, T document) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(document, "document");
        if (!tenant.tenantId().equals(document.tenantId())) {
            // The indexers build the reference from the document, so this cannot happen by accident;
            // it is here because the one thing worse than a wrong index is a document sealed with
            // one tenant's keys and written into another tenant's.
            throw new IllegalArgumentException(
                    "document belongs to tenant '%s' but was addressed as '%s'"
                            .formatted(document.tenantId(), tenant));
        }
        return switch (resolver.privacyLevel(tenant)) {
            case NORMAL -> document;
            case HIGH -> secure(tenant, document);
        };
    }

    private Object secure(TenantRef tenant, T document) {
        if (secureMapper == null) {
            throw new IllegalStateException(
                    ("tenant '%s' in domain '%s' is HIGH privacy but no SecureDocumentMapper is wired: refusing "
                            + "to write a plaintext document to the secure family. Wire the mapper and the tenant's "
                            + "key material, or set the tenant back to NORMAL in the catalog.")
                            .formatted(tenant.tenantId(), tenant.domain()));
        }
        return secureMapper.toDocument(tenant, document);
    }
}
