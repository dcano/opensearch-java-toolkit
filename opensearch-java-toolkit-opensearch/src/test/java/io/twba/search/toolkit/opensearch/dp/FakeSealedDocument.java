package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stand-in application document with two sensitive fields, and the two functions an application
 * hands the spec-driven mapper.
 *
 * <p>Deliberately not shaped like any real domain: the point of the generalized secure mapper is
 * that it knows nothing of an application's fields, so the fixture proving it should not look like
 * the lab-result record it was generalized from. {@code note} is an ordinary field that must cross
 * over untouched; {@code subjectName} and {@code contactHandle} are declared sensitive, and two of
 * them rather than one because "each field seals independently" is a property a single field cannot
 * demonstrate.
 *
 * <p>{@link #plain} is written the way an application would write it — it <em>includes</em> the
 * sensitive values, because that is the mapper's documented contract and because a scrub that only
 * works when the extractor already withheld the value would prove nothing.
 */
record FakeSealedDocument(String tenantId, String documentId, String note,
                          String subjectName, String contactHandle) implements TenantDocument {

    static final SecureFieldSpec SUBJECT_NAME = new SecureFieldSpec("subjectName");
    static final SecureFieldSpec CONTACT_HANDLE = new SecureFieldSpec("contactHandle");
    static final List<SecureFieldSpec> SPECS = List.of(SUBJECT_NAME, CONTACT_HANDLE);

    static final String NOTE_FIELD = "note";
    static final String DOCUMENT_ID_FIELD = "documentId";

    /** The application's document as a map, sensitive values and all. */
    static Map<String, Object> plain(FakeSealedDocument document) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(TenantDocument.TENANT_ID_FIELD, document.tenantId());
        fields.put(DOCUMENT_ID_FIELD, document.documentId());
        fields.put(NOTE_FIELD, document.note());
        fields.put(SUBJECT_NAME.fieldName(), document.subjectName());
        fields.put(CONTACT_HANDLE.fieldName(), document.contactHandle());
        return fields;
    }

    /** The reverse, by code that knows its own constructor. */
    static FakeSealedDocument fromPlain(Map<String, Object> plain, Map<String, String> opened) {
        return new FakeSealedDocument(
                (String) plain.get(TenantDocument.TENANT_ID_FIELD),
                (String) plain.get(DOCUMENT_ID_FIELD),
                (String) plain.get(NOTE_FIELD),
                opened.get(SUBJECT_NAME.fieldName()),
                opened.get(CONTACT_HANDLE.fieldName()));
    }
}
