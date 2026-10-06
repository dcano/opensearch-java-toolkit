package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.TenantDocument;

/**
 * A stand-in application document for the write-path tests.
 *
 * <p>Obviously fake, and deliberately not shaped like the kata's {@code LabResult}: the point of the
 * generalized write path is that it knows nothing about an application's fields, so the fixture that
 * exercises it should not resemble any real domain. {@code note} exists purely so a test can plant a
 * recognisable sentinel string and then check that it did not leak into a meter, span or request it
 * had no business reaching.
 */
record FakeDocument(String tenantId, String documentId, String note) implements TenantDocument {

    static FakeDocument of(String tenantId, String documentId) {
        return new FakeDocument(tenantId, documentId, "note-" + documentId);
    }
}
