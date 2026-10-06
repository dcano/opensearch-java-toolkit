package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.BlindIndexer;
import io.twba.search.toolkit.crypto.SealedFieldCipher;
import io.twba.search.toolkit.crypto.TenantKeyProvider;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.cp.InMemoryTenantCatalog;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog;
import io.twba.search.toolkit.opensearch.dp.CatalogTenantIndexResolver;
import io.twba.search.toolkit.opensearch.dp.SecureDocumentMapper;
import io.twba.search.toolkit.opensearch.dp.SpecDrivenSecureDocumentMapper;
import io.twba.search.toolkit.opensearch.dp.WriteDocuments;
import io.twba.search.toolkit.opensearch.provisioning.DomainMapping;
import io.twba.search.toolkit.opensearch.provisioning.SensitiveFieldMapping;
import org.opensearch.client.opensearch.OpenSearchClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A whole synthetic domain, assembled from toolkit parts exactly the way a real domain module will.
 *
 * <p>This fixture is the point of the testkit's own suite. The conformance checks have never been
 * executed against anything; a suite that has only ever compiled is evidence of nothing, and the
 * first domain to wire it would meet any defect in it as a confusing failure of its own. So the
 * pieces below are the real ones — catalog, resolver, template installer, pool provisioner,
 * spec-driven mapper, write path — with nothing stubbed but the application.
 *
 * <p>Every method hands back a fresh instance rather than a shared constant, so a test that needs a
 * deliberately broken variant can substitute one piece without any other test inheriting it.
 */
final class SyntheticWiring {

    static final SearchDomain DOMAIN = new SearchDomain("conformance");
    static final SearchDomains DOMAINS = SearchDomains.of(DOMAIN);

    /** Two, so the isolation check can involve two tenants that may or may not share a pool. */
    static final int POOL_COUNT = 2;

    static final TenantRef NORMAL = TenantRef.of(DOMAIN, "tenant-alpha");
    static final TenantRef OTHER_NORMAL = TenantRef.of(DOMAIN, "tenant-beta");
    static final TenantRef HIGH = TenantRef.of(DOMAIN, "tenant-high");
    static final TenantRef KEYLESS_HIGH = TenantRef.of(DOMAIN, "tenant-keyless");

    static final SecureFieldSpec SECRET_LABEL = new SecureFieldSpec("secretLabel");
    static final List<SecureFieldSpec> SPECS = List.of(SECRET_LABEL);

    static final String ORDINARY_NOTE = "an ordinary field that crosses over untouched";

    private SyntheticWiring() {
    }

    /**
     * Key material for every tenant but the keyless one, built the way a domain module would: name
     * them all, then take one away. Modelling "never provisioned" and "key destroyed" identically is
     * the whole reason {@code withoutKeysFor} exists.
     */
    static ConformanceTenantKeys keys() {
        return ConformanceTenantKeys
                .forTenants(NORMAL.tenantId(), OTHER_NORMAL.tenantId(), HIGH.tenantId(), KEYLESS_HIGH.tenantId())
                .withoutKeysFor(KEYLESS_HIGH.tenantId());
    }

    /**
     * The two {@code HIGH} tenants are seeded as {@code HIGH}; the two {@code NORMAL} ones are left
     * to the catalog's lazy default. An unseeded pair is {@code POOLED} and {@code NORMAL}, which is
     * exactly what the isolation check wants and exactly the wrong thing to get for a secure tenant.
     */
    static TenantCatalog catalog() {
        return new InMemoryTenantCatalog(DOMAINS, domain -> POOL_COUNT, List.of(
                InMemoryTenantCatalog.pooled(HIGH, PrivacyLevel.HIGH, POOL_COUNT),
                InMemoryTenantCatalog.pooled(KEYLESS_HIGH, PrivacyLevel.HIGH, POOL_COUNT)));
    }

    static TenantIndexResolver resolver() {
        return new CatalogTenantIndexResolver(catalog(), DOMAINS);
    }

    static SecureDocumentMapper<SyntheticDocument> secureMapper(TenantKeyProvider keys) {
        return new SpecDrivenSecureDocumentMapper<>(
                SPECS, SyntheticWiring::plain, SyntheticWiring::fromPlain,
                new BlindIndexer(keys), new SealedFieldCipher(keys));
    }

    static WriteDocuments<SyntheticDocument> writeDocuments(TenantIndexResolver resolver,
                                                            SecureDocumentMapper<SyntheticDocument> mapper) {
        return new WriteDocuments<>(resolver, mapper);
    }

    /** Includes the sensitive field, as the mapper demands: the scrub is the mapper's job, not ours. */
    static Map<String, Object> plain(SyntheticDocument document) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tenantId", document.tenantId());
        fields.put("documentId", document.documentId());
        fields.put("note", document.note());
        fields.put("secretLabel", document.secretLabel());
        return fields;
    }

    static SyntheticDocument fromPlain(Map<String, Object> fields, Map<String, String> opened) {
        return new SyntheticDocument(
                (String) fields.get("tenantId"),
                (String) fields.get("documentId"),
                opened.get("secretLabel"),
                (String) fields.get("note"));
    }

    /**
     * A fresh document id per call. The conformance suite indexes for the same tenant several times
     * and then asserts that one tenant's id is absent from another's results, which only means
     * anything while the two documents are distinguishable.
     */
    static SyntheticDocument document(TenantRef tenant, String sensitiveValue) {
        return new SyntheticDocument(tenant.tenantId(), "doc-" + UUID.randomUUID(), sensitiveValue, ORDINARY_NOTE);
    }

    static String sensitiveValueOf(SyntheticDocument document) {
        return document.secretLabel();
    }

    /**
     * The application's contribution to both templates. {@code tenantId} is a keyword because the
     * mandatory tenant filter is a term query on it; a text mapping would analyse the id and the
     * filter would match nothing while reporting success.
     */
    static DomainMapping mapping() {
        return new DomainMapping(
                settings -> settings.numberOfShards(1).numberOfReplicas(0),
                m -> m.properties("tenantId", p -> p.keyword(k -> k))
                        .properties("documentId", p -> p.keyword(k -> k))
                        .properties("note", p -> p.text(t -> t)),
                List.of(new SensitiveFieldMapping(SECRET_LABEL, p -> p.text(t -> t))));
    }

    /** The subject a correctly wired domain module hands to {@link ToolkitConformance}. */
    static ConformanceSubject.Builder<SyntheticDocument> subject(OpenSearchClient client,
                                                                 TenantIndexResolver resolver,
                                                                 SecureDocumentMapper<SyntheticDocument> mapper) {
        return ConformanceSubject.<SyntheticDocument>forDomain(DOMAIN)
                .client(client)
                .resolver(resolver)
                .documents(SyntheticDocument.class, documents(), SyntheticWiring::sensitiveValueOf)
                .writeDocuments(writeDocuments(resolver, mapper))
                .secureFields(SPECS, mapper)
                .tenants(NORMAL, OTHER_NORMAL, HIGH, KEYLESS_HIGH);
    }

    static BiFunction<TenantRef, String, SyntheticDocument> documents() {
        return SyntheticWiring::document;
    }

    static Function<SyntheticDocument, String> sensitiveValue() {
        return SyntheticWiring::sensitiveValueOf;
    }

    /** A client wired to a transport that refuses every request: for checks that touch no cluster. */
    static OpenSearchClient clusterFreeClient() {
        return new OpenSearchClient(new UnusedOpenSearchTransport());
    }
}
