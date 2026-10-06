package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;

import java.util.Map;

/**
 * Turns an application's document into the sealed shape a {@code HIGH} tenant's family stores.
 *
 * <p>A port, so the write path can be written and tested without the crypto, and so an application
 * that has no sensitive fields simply has no implementation of it. The toolkit ships the
 * spec-driven implementation; nothing stops an application supplying its own, and nothing about
 * this interface lets it skip sealing — a mapper that returned the plaintext document would be
 * caught by the secure family's strict mapping, which defines no plaintext field to put it in.
 *
 * <p>The write return type is {@code Object} because that is what the client is handed: the shape
 * differs per privacy level and only the serializer needs to know it.
 *
 * <p>It maps both ways, and the reverse direction is what lets an application's port keep returning
 * its own document type whatever the tenant's posture — nothing above the adapter ever learns that
 * this tenant's values were sealed.
 */
public interface SecureDocumentMapper<T extends TenantDocument> {

    /**
     * @param tenant the addressed tenant, whose key material seals the sensitive fields
     * @param source the application's document
     * @return the document to index in its place
     */
    Object toDocument(TenantRef tenant, T source);

    /**
     * The reverse: a stored document back into the application's type, with its sensitive fields
     * opened.
     *
     * <p>Failure is total, never partial. A hit whose envelope does not authenticate raises rather
     * than returning a document with a blank value, because a blank is indistinguishable from a
     * record that never had one — a privacy incident that reads as a data-entry problem.
     *
     * @param tenant     the addressed tenant, whose key material opens the envelopes
     * @param documentId the hit's {@code _id}, so a corrupt record can be found
     * @param source     the hit's {@code _source}
     */
    T fromDocument(TenantRef tenant, String documentId, Map<String, Object> source);
}
