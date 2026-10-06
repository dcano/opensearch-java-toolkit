package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.TenantDocument;

/**
 * The document the testkit's own suite runs the conformance checks against.
 *
 * <p>Obviously fake, and deliberately not shaped like any real domain. The testkit's whole claim is
 * that it knows nothing about an application, so the fixture that proves the claim must not resemble
 * one: four components, of which exactly one is declared sensitive and one is an ordinary field that
 * crosses over untouched.
 *
 * <p>{@code note} earns its place: it is a mapped, readable field, which is where a leaking mapper
 * would put a sensitive value that a key-by-key check would never notice.
 */
record SyntheticDocument(String tenantId, String documentId, String secretLabel, String note)
        implements TenantDocument {
}
