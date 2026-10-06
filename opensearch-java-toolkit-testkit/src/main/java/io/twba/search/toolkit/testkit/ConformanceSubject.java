package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.dp.SecureDocumentMapper;
import io.twba.search.toolkit.opensearch.dp.WriteDocuments;
import org.opensearch.client.opensearch.OpenSearchClient;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A domain module's wiring, described well enough for the conformance suite to exercise it.
 *
 * <p>Built rather than constructed positionally. Ten values of which four are {@link TenantRef}s and
 * two are functions of the same document type is exactly the shape where arguments get supplied in
 * the wrong order and nothing complains — the hazard {@link TenantRef} itself exists to remove. Each
 * setter names what it takes.
 *
 * <p>The suite deliberately does <em>not</em> accept a ready-made indexer or executor. It builds
 * those itself from the pieces below, because two of the four invariants are about what the toolkit
 * emits while it works — whether a sensitive value reaches telemetry, whether a keyless tenant fails
 * closed — and a suite handed a finished executor could not attach its own recording registry without
 * asking the domain module to do it, which is the sort of thing a module gets subtly wrong in exactly
 * the run that was meant to catch it.
 *
 * @param <T> the application's document type
 */
public final class ConformanceSubject<T extends TenantDocument> {

    private final SearchDomain domain;
    private final OpenSearchClient client;
    private final TenantIndexResolver resolver;
    private final Class<T> documentType;
    private final WriteDocuments<T> writeDocuments;
    private final SecureDocumentMapper<T> secureMapper;
    private final List<SecureFieldSpec> specs;
    private final BiFunction<TenantRef, String, T> documents;
    private final Function<T, String> sensitiveValue;
    private final TenantRef normalTenant;
    private final TenantRef otherNormalTenant;
    private final TenantRef highTenant;
    private final TenantRef keylessHighTenant;

    private ConformanceSubject(Builder<T> builder) {
        this.domain = builder.domain;
        this.client = require(builder.client, "client");
        this.resolver = require(builder.resolver, "resolver");
        this.documentType = require(builder.documentType, "documentType");
        this.writeDocuments = require(builder.writeDocuments, "writeDocuments");
        this.secureMapper = builder.secureMapper;
        this.specs = List.copyOf(builder.specs);
        this.documents = require(builder.documents, "documents");
        this.sensitiveValue = require(builder.sensitiveValue, "sensitiveValue");
        this.normalTenant = requireDomain(builder.normalTenant, "normalTenant");
        this.otherNormalTenant = requireDomain(builder.otherNormalTenant, "otherNormalTenant");
        this.highTenant = requireDomain(builder.highTenant, "highTenant");
        this.keylessHighTenant = requireDomain(builder.keylessHighTenant, "keylessHighTenant");

        if (normalTenant.equals(otherNormalTenant)) {
            throw new IllegalArgumentException(
                    ("domain '%s' gave the same tenant twice for the isolation check: a suite that "
                            + "searched one tenant and asserted it could not see itself would pass for "
                            + "any implementation at all").formatted(domain.name()));
        }
        // 9.3: the misconfiguration that has no other symptom. A domain declaring a sensitive field
        // with no mapper to seal it does not fail at startup, does not fail its template install, and
        // fails only when a HIGH tenant is first written — which in a deployment with no HIGH tenants
        // yet is months later, by which time the wiring looks settled.
        if (!specs.isEmpty() && secureMapper == null) {
            throw new IllegalStateException(
                    ("domain '%s' declares %d sensitive field(s) %s but wires no SecureDocumentMapper: "
                            + "its HIGH tenants have nothing to seal their values, and the failure "
                            + "would otherwise surface only on the first HIGH write")
                            .formatted(domain.name(), specs.size(),
                                    specs.stream().map(SecureFieldSpec::fieldName).toList()));
        }
    }

    public static <T extends TenantDocument> Builder<T> forDomain(SearchDomain domain) {
        return new Builder<>(Objects.requireNonNull(domain, "domain"));
    }

    public SearchDomain domain() {
        return domain;
    }

    public OpenSearchClient client() {
        return client;
    }

    public TenantIndexResolver resolver() {
        return resolver;
    }

    public Class<T> documentType() {
        return documentType;
    }

    public WriteDocuments<T> writeDocuments() {
        return writeDocuments;
    }

    public SecureDocumentMapper<T> secureMapper() {
        return secureMapper;
    }

    public List<SecureFieldSpec> specs() {
        return specs;
    }

    /** A document for this tenant carrying this sensitive value. */
    public T document(TenantRef tenant, String value) {
        return documents.apply(tenant, value);
    }

    /** Reads back what {@link #document} put in, so a round trip can be compared. */
    public String sensitiveValueOf(T document) {
        return sensitiveValue.apply(document);
    }

    public TenantRef normalTenant() {
        return normalTenant;
    }

    public TenantRef otherNormalTenant() {
        return otherNormalTenant;
    }

    public TenantRef highTenant() {
        return highTenant;
    }

    public TenantRef keylessHighTenant() {
        return keylessHighTenant;
    }

    private static <V> V require(V value, String name) {
        return Objects.requireNonNull(value, () ->
                "conformance subject is missing '%s'; every value is needed to run the suite".formatted(name));
    }

    private TenantRef requireDomain(TenantRef tenant, String name) {
        require(tenant, name);
        if (!domain.equals(tenant.domain())) {
            throw new IllegalArgumentException(
                    ("'%s' names tenant '%s' in domain '%s', but this subject describes domain '%s'")
                            .formatted(name, tenant.tenantId(), tenant.domain().name(), domain.name()));
        }
        return tenant;
    }

    /** @param <T> the application's document type */
    public static final class Builder<T extends TenantDocument> {

        private final SearchDomain domain;
        private OpenSearchClient client;
        private TenantIndexResolver resolver;
        private Class<T> documentType;
        private WriteDocuments<T> writeDocuments;
        private SecureDocumentMapper<T> secureMapper;
        private List<SecureFieldSpec> specs = List.of();
        private BiFunction<TenantRef, String, T> documents;
        private Function<T, String> sensitiveValue;
        private TenantRef normalTenant;
        private TenantRef otherNormalTenant;
        private TenantRef highTenant;
        private TenantRef keylessHighTenant;

        private Builder(SearchDomain domain) {
            this.domain = domain;
        }

        public Builder<T> client(OpenSearchClient client) {
            this.client = client;
            return this;
        }

        /** The one component that knows this domain's topology and each tenant's privacy posture. */
        public Builder<T> resolver(TenantIndexResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        /**
         * @param documents      builds a document for a tenant carrying a given sensitive value. The
         *                       suite supplies values it can recognise afterwards, so this must put
         *                       the value where {@code sensitiveValue} reads it from
         * @param sensitiveValue reads that value back out of a document
         */
        public Builder<T> documents(Class<T> documentType,
                                    BiFunction<TenantRef, String, T> documents,
                                    Function<T, String> sensitiveValue) {
            this.documentType = documentType;
            this.documents = documents;
            this.sensitiveValue = sensitiveValue;
            return this;
        }

        public Builder<T> writeDocuments(WriteDocuments<T> writeDocuments) {
            this.writeDocuments = writeDocuments;
            return this;
        }

        /** Omitted only by a domain that declares no sensitive field; see the constructor's refusal. */
        public Builder<T> secureFields(List<SecureFieldSpec> specs, SecureDocumentMapper<T> secureMapper) {
            this.specs = specs == null ? List.of() : specs;
            this.secureMapper = secureMapper;
            return this;
        }

        /**
         * @param normal      a {@code NORMAL} tenant
         * @param otherNormal a second, different {@code NORMAL} tenant, so isolation is asserted
         *                    between two tenants rather than against a tenant and itself
         * @param high        a {@code HIGH} tenant whose key material is available
         * @param keylessHigh a {@code HIGH} tenant whose key material is <em>not</em> — never
         *                    provisioned, or destroyed; the toolkit must not be able to tell
         */
        public Builder<T> tenants(TenantRef normal, TenantRef otherNormal,
                                  TenantRef high, TenantRef keylessHigh) {
            this.normalTenant = normal;
            this.otherNormalTenant = otherNormal;
            this.highTenant = high;
            this.keylessHighTenant = keylessHigh;
            return this;
        }

        public ConformanceSubject<T> build() {
            return new ConformanceSubject<>(this);
        }
    }
}
