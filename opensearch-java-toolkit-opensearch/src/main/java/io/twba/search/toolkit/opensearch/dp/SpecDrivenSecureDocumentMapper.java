package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.BlindIndexer;
import io.twba.search.toolkit.crypto.KeyUnavailableException;
import io.twba.search.toolkit.crypto.SealedFieldCipher;
import io.twba.search.toolkit.crypto.SealedValue;
import io.twba.search.toolkit.crypto.SealedValueAuthenticationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The one place a sensitive value is converted for storage, for any application.
 *
 * <p>Driven by {@link SecureFieldSpec}: the application says which fields are sensitive, and the
 * six storage component names per field are derived, never typed. The hashes come from the same
 * {@link BlindIndexer} methods the query builder calls, so index-time and query-time derivation
 * cannot drift apart — the failure that makes blind-index systems quietly stop matching, with no
 * error anywhere.
 *
 * <p>The application supplies two functions and no field names:
 *
 * <ul>
 *   <li>{@code plain} — its document as a map of field name to value, <em>including</em> the
 *       sensitive fields. It must include them: a missing key is an application bug that would
 *       otherwise write a document with no searchable value and no envelope, silently.</li>
 *   <li>{@code fromPlain} — its document rebuilt from the non-sensitive fields and the opened
 *       sensitive ones, by code that knows its own constructor.</li>
 * </ul>
 *
 * <p><strong>The scrub is unconditional.</strong> Every declared sensitive field is removed from the
 * extractor's output after its components are derived, whether or not the application included it
 * and whatever it contained. An extractor that returns the plaintext value still cannot write it.
 * That is a test, not a convention.
 *
 * <p>It fails closed. Both the blind indexer and the cipher reach for the tenant's keys before
 * producing anything, so a {@code HIGH} tenant with no key material raises
 * {@link KeyUnavailableException} here — before a request is built, let alone sent. No path through
 * this class yields a document with a readable value, a partially sealed one, or an unkeyed hash.
 */
public final class SpecDrivenSecureDocumentMapper<T extends TenantDocument> implements SecureDocumentMapper<T> {

    private final List<SecureFieldSpec> specs;
    private final Function<T, Map<String, Object>> plain;
    private final BiFunction<Map<String, Object>, Map<String, String>, T> fromPlain;
    private final BlindIndexer blindIndexer;
    private final SealedFieldCipher cipher;
    private final Set<String> derivedNames;

    public SpecDrivenSecureDocumentMapper(List<SecureFieldSpec> specs,
                                          Function<T, Map<String, Object>> plain,
                                          BiFunction<Map<String, Object>, Map<String, String>, T> fromPlain,
                                          BlindIndexer blindIndexer,
                                          SealedFieldCipher cipher) {
        this.specs = List.copyOf(Objects.requireNonNull(specs, "specs"));
        this.plain = Objects.requireNonNull(plain, "plain");
        this.fromPlain = Objects.requireNonNull(fromPlain, "fromPlain");
        this.blindIndexer = Objects.requireNonNull(blindIndexer, "blindIndexer");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        if (this.specs.isEmpty()) {
            throw new IllegalArgumentException("a secure mapper needs at least one sensitive field spec");
        }
        this.derivedNames = derivedNames(this.specs);
    }

    /**
     * Every storage name this mapper owns, checked for collisions between specs. Two sensitive
     * fields whose derived names overlap would seal into each other's components, and the second
     * one written would win.
     */
    private static Set<String> derivedNames(List<SecureFieldSpec> specs) {
        // One implementation, in core, so the template installer and this mapper cannot come to
        // different conclusions about which lists of specs are usable.
        return SecureFieldSpec.derivedNamesOf(specs);
    }

    /**
     * @throws KeyUnavailableException if the tenant has no usable key material — nothing is written,
     *                                 because nothing has been built yet
     */
    @Override
    public SecureDocument toDocument(TenantRef tenant, T source) {
        Objects.requireNonNull(tenant, "tenant");
        Map<String, Object> fields = new LinkedHashMap<>(plain.apply(source));
        String tenantId = tenant.tenantId();

        // Against the extractor's output, before anything is derived: afterwards the map holds
        // every derived name by construction, and this check would reject its own work.
        for (String name : fields.keySet()) {
            if (derivedNames.contains(name)) {
                throw new IllegalStateException(
                        ("the field extractor produced '%s', which is a storage component derived from a declared "
                                + "sensitive field; refusing to overwrite it").formatted(name));
            }
        }

        for (SecureFieldSpec spec : specs) {
            if (!fields.containsKey(spec.fieldName())) {
                throw new IllegalStateException(
                        ("the field extractor produced no entry for declared sensitive field '%s'; it must be "
                                + "present, with a null value when the document has none — otherwise a document "
                                + "would be written with no searchable value and no envelope")
                                .formatted(spec.fieldName()));
            }
            // Read then remove, unconditionally. What the extractor put here never reaches the
            // cluster; only what is derived from it does.
            Object value = fields.remove(spec.fieldName());
            if (value != null && !(value instanceof String)) {
                throw new IllegalStateException(
                        "sensitive field '%s' must be a String or null, was %s"
                                .formatted(spec.fieldName(), value.getClass().getSimpleName()));
            }
            fields.putAll(derive(tenantId, source.documentId(), spec, (String) value));
        }

        return new SecureDocument(fields);
    }

    /**
     * A document with no value for the field is a real case. Sealing null would throw; sealing
     * {@code ""} would make it indistinguishable from a value that opens to empty. Absent stays
     * absent, on both sides of the seal: empty hash arrays, and no envelope keys at all.
     */
    private Map<String, Object> derive(String tenantId, String documentId, SecureFieldSpec spec, String value) {
        Map<String, Object> derived = new LinkedHashMap<>();
        if (value == null) {
            derived.put(spec.tokensField(), List.of());
            derived.put(spec.prefixesField(), List.of());
            return derived;
        }
        derived.put(spec.tokensField(), blindIndexer.tokenIndex(tenantId, value));
        derived.put(spec.prefixesField(), blindIndexer.prefixIndex(tenantId, value));
        // Bound to this tenant, this document and this field: an envelope lifted onto another
        // field's names, or another document, does not open.
        SealedValue sealed = cipher.seal(tenantId, documentId, spec.fieldName(), value);
        derived.put(spec.cipherField(), sealed.cipher());
        derived.put(spec.ivField(), sealed.iv());
        derived.put(spec.dekWrappedField(), sealed.dekWrapped());
        derived.put(spec.dekIvField(), sealed.dekIv());
        return derived;
    }

    /**
     * @throws SealedValueAuthenticationException if an envelope was altered or is incomplete
     * @throws KeyUnavailableException            if the tenant's key is gone — cryptographic erasure,
     *                                            surfacing as an explicit error rather than empty results
     */
    @Override
    public T fromDocument(TenantRef tenant, String documentId, Map<String, Object> source) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(source, "source");
        Object storedTenant = source.get(TenantDocument.TENANT_ID_FIELD);
        if (storedTenant == null) {
            // Not a formality: the guard below is only worth having if it cannot be removed by
            // omitting one field, and a source-filtered read must fetch the tenant to be checkable.
            throw new IllegalStateException(
                    "document '%s' carries no '%s'; it cannot be attributed to a tenant"
                            .formatted(documentId, TenantDocument.TENANT_ID_FIELD));
        }
        if (!storedTenant.equals(tenant.tenantId())) {
            // Defence in depth behind the executor's tenant filter: opening another tenant's
            // document with this tenant's key would fail anyway, but saying so plainly is better
            // than an authentication error that reads like corruption.
            throw new IllegalStateException(
                    "document '%s' belongs to tenant '%s' but was read as '%s'"
                            .formatted(documentId, storedTenant, tenant));
        }

        Map<String, Object> fields = new LinkedHashMap<>(source);
        Map<String, String> opened = new LinkedHashMap<>();
        for (SecureFieldSpec spec : specs) {
            for (String derived : spec.derivedFields()) {
                fields.remove(derived);
            }
            opened.put(spec.fieldName(), open(tenant, documentId, spec, source));
        }
        return fromPlain.apply(fields, opened);
    }

    private String open(TenantRef tenant, String documentId, SecureFieldSpec spec, Map<String, Object> source) {
        // An absent value carries none of the four components; anything between none and all is
        // altered data. Using the ciphertext alone as the sentinel would read a document stripped
        // of just its ciphertext as "no value on file" — a blank derived from tampering, which is
        // the one outcome failing closed exists to prevent.
        List<String> missing = new ArrayList<>();
        int present = 0;
        for (String component : spec.envelopeFields()) {
            if (source.get(component) == null) {
                missing.add(component);
            } else {
                present++;
            }
        }
        if (present == 0) {
            return null;   // no value was on file when this was written; see derive()
        }
        if (!missing.isEmpty()) {
            throw new SealedValueAuthenticationException(
                    ("document '%s' carries an incomplete envelope for field '%s': %s absent")
                            .formatted(documentId, spec.fieldName(), missing), null);
        }
        try {
            return cipher.open(tenant.tenantId(), documentId, spec.fieldName(), new SealedValue(
                    String.valueOf(source.get(spec.cipherField())),
                    String.valueOf(source.get(spec.ivField())),
                    String.valueOf(source.get(spec.dekWrappedField())),
                    String.valueOf(source.get(spec.dekIvField()))));
        } catch (SealedValueAuthenticationException e) {
            // The document id travels so the corrupt record can be found. The ciphertext, the keys
            // and any opened value do not, because this message reaches logs.
            throw new SealedValueAuthenticationException(
                    ("document '%s' carries a value for field '%s' that failed authentication; it was altered "
                            + "or its envelope is incomplete").formatted(documentId, spec.fieldName()), e);
        }
    }
}
