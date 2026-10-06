package io.twba.search.toolkit.opensearch.dp;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@code HIGH} tenant's document looks like on disk: the application's non-sensitive fields,
 * plus the derived components of every sensitive one.
 *
 * <p>A map, not a record, and that is the load-bearing decision of this design. The implementation
 * this generalizes used an 18-component record, which cannot be written once for {@code N}
 * application fields and {@code M} sensitive ones. The alternatives were an application-written
 * secure record — handing back the six-component envelope contract this exists to own — or
 * reflection over the domain type, which is fragile, invisible in a stack trace, and puts a
 * reflective read of a sensitive value in the hot path.
 *
 * <p>What made the record safe was never the record. It was {@code dynamic: strict} on the template:
 * any field the mapping does not define is rejected by the cluster at write time. A map is checked
 * by the same mechanism, on the same request, with the same failure. What is lost is compile-time
 * field naming inside the toolkit; what replaces it is that field names are typed in exactly one
 * place per application, and a wrong one fails loudly the first time it is written.
 *
 * <p>Serializes as the map itself, so the document that reaches the cluster is a plain object with
 * no wrapper. {@code toString} deliberately does not print the contents: they are not plaintext, but
 * a ciphertext in a log line still outlives its retention policy and still correlates records.
 */
public final class SecureDocument {

    private final Map<String, Object> fields;

    /**
     * Null <em>values</em> are kept, because an application record with an optional field is an
     * ordinary case and refusing it would make such a document unwritable. Keeping them also keeps
     * this document closer to the record it generalizes, which serialized explicit nulls: the only
     * remaining difference is the envelope components of an absent sensitive value, which are not
     * written at all. Insertion order is preserved so the written document is deterministic.
     */
    public SecureDocument(Map<String, Object> fields) {
        Objects.requireNonNull(fields, "fields");
        Map<String, Object> copy = new LinkedHashMap<>();
        fields.forEach((name, value) -> copy.put(Objects.requireNonNull(name, "field name"), value));
        this.fields = Collections.unmodifiableMap(copy);
    }

    /** The fields as written. Jackson serializes this in place of the wrapper. */
    @JsonValue
    public Map<String, Object> fields() {
        return fields;
    }

    public Object get(String field) {
        return fields.get(field);
    }

    public boolean has(String field) {
        return fields.containsKey(field);
    }

    /** A mutable copy, for callers assembling a document field by field. */
    public Map<String, Object> toMutableMap() {
        return new LinkedHashMap<>(fields);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SecureDocument other && fields.equals(other.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        return "SecureDocument[" + fields.size() + " fields, sealed]";
    }
}
