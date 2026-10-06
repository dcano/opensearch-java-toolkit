package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.cp.InMemoryTenantCatalog;
import io.twba.search.toolkit.opensearch.dp.CatalogTenantIndexResolver;
import io.twba.search.toolkit.opensearch.dp.SecureDocument;
import io.twba.search.toolkit.opensearch.dp.SecureDocumentMapper;
import io.twba.search.toolkit.opensearch.provisioning.DomainMapping;
import io.twba.search.toolkit.opensearch.provisioning.IndexTemplateInstaller;
import io.twba.search.toolkit.opensearch.provisioning.PoolProvisioner;
import io.twba.search.toolkit.opensearch.provisioning.SensitiveFieldMapping;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The conformance suite, executed. <strong>Needs Docker.</strong>
 *
 * <p>Until this file existed the suite had only ever compiled, which is evidence of nothing. Group
 * 10 is about to point a real domain at it, and any defect in the suite would surface there as a
 * confusing failure of that domain's wiring rather than of the suite's. So this stands up the
 * fixture's own container, provisions a synthetic domain end to end with the real toolkit parts —
 * catalog, resolver, both index templates, the pools, the spec-driven mapper, the write path — and
 * runs the checks for real.
 *
 * <p>The {@code @TestFactory} below is exactly what a domain module writes: one method, six checks,
 * each reported on its own. If one of them goes red here, the suite is wrong, not the domain.
 *
 * <p>The second half is the more useful one. A conformance suite is a certificate, and a certificate
 * nobody has tried to forge is not evidence, so {@link TheSuiteBites} plants a specific wiring fault
 * and asserts the relevant check notices. Two faults are not reachable from a subject and are named
 * in {@link TheSuiteBites} rather than faked.
 */
@Testcontainers
@DisplayName("the toolkit conformance suite, run against a real cluster")
class ToolkitConformanceSelfTest {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = ToolkitOpenSearchCluster.container();

    private static OpenSearchClient client;

    @BeforeAll
    static void provisionTheSyntheticDomain() throws IOException {
        client = ToolkitOpenSearchCluster.clientFor(OPENSEARCH);
        install(client, SyntheticWiring.DOMAIN, SyntheticWiring.mapping(), SyntheticWiring.POOL_COUNT);
        install(client, PorousDomain.DOMAIN, PorousDomain.mapping(), PorousDomain.POOL_COUNT);
    }

    /** Templates before pools: a pool created first would be mapped by whatever was there before. */
    private static void install(OpenSearchClient client, SearchDomain domain, DomainMapping mapping, int pools)
            throws IOException {
        for (PrivacyLevel level : PrivacyLevel.values()) {
            new IndexTemplateInstaller(client, domain, level, mapping).install();
        }
        new PoolProvisioner(client).provision(domain, pools);
    }

    @TestFactory
    @DisplayName("every check passes for a correctly wired domain")
    Stream<DynamicTest> conformance() {
        return ToolkitConformance.tests(SyntheticWiring
                .subject(client, SyntheticWiring.resolver(),
                        SyntheticWiring.secureMapper(SyntheticWiring.keys()))
                .build());
    }

    @Nested
    @DisplayName("the suite bites")
    class TheSuiteBites {

        @Test
        @DisplayName("a sensitive value copied into an ordinary mapped field is caught")
        void aLeakIntoAnOrdinaryFieldIsCaught() {
            // The leak a key-by-key check would miss entirely. The mapper below seals correctly —
            // every derived component is right and the declared field is gone — and then copies the
            // readable value into 'note', which the secure template maps as text and the cluster
            // therefore accepts without complaint. Only the check that renders the whole stored
            // source and hunts for the value finds it.
            ConformanceSubject<SyntheticDocument> leaking = SyntheticWiring
                    .subject(client, SyntheticWiring.resolver(), new LeakingSecureMapper())
                    .build();

            assertThatThrownBy(
                    ChecksUnderTest.check(leaking, ChecksUnderTest.NO_READABLE_VALUE_STORED)::execute)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("no readable sensitive value anywhere in the stored source");
        }

        @Test
        @DisplayName("a secure template that maps the sensitive field is caught")
        void aSecureTemplateWithAPlaintextFieldIsCaught() {
            // The whole point of the cluster-side check: the guarantee is a mapping, not a
            // convention, and it must hold for a curl or a migration script that never went through
            // the toolkit. The 'porous' domain contributes its sensitive field as a shared property,
            // so it lands in the secure mapping too and the cluster accepts a readable value.
            ConformanceSubject<SyntheticDocument> porous = PorousDomain.subject(client);

            assertThatThrownBy(ChecksUnderTest.check(porous, ChecksUnderTest.CLUSTER_REFUSES)::execute)
                    .isInstanceOf(AssertionError.class)
                    // The check reports "expecting code to raise a throwable": the cluster accepted
                    // the readable value instead of refusing it. The stack trace names which of the
                    // six checks said so, which the message alone does not.
                    .hasStackTraceContaining("clusterRefusesReadableValue");
        }

        @Test
        @DisplayName("a document reachable under another tenant's search is caught")
        void aDocumentThatCrossesTenantsIsCaught() {
            // The tenant filter itself has no seam: TenantScopedSearchExecutor is final, builds its
            // own query, and the suite constructs it rather than accepting one. So what can be
            // planted from a subject is the other half of the same failure — a document that ends up
            // reachable under a tenant it does not belong to. The factory below stamps every
            // document with the first tenant's id whoever it was asked for, which is what a
            // mis-wired ingestion path does.
            ConformanceSubject<SyntheticDocument> crossed = SyntheticWiring
                    .subject(client, SyntheticWiring.resolver(),
                            SyntheticWiring.secureMapper(SyntheticWiring.keys()))
                    .documents(SyntheticDocument.class,
                            (tenant, value) -> SyntheticWiring.document(SyntheticWiring.NORMAL, value),
                            SyntheticWiring::sensitiveValueOf)
                    .build();

            assertThatThrownBy(ChecksUnderTest.check(crossed, ChecksUnderTest.TENANT_ISOLATION)::execute)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("must not be reachable");
        }
    }

    @Nested
    @DisplayName("what the checks actually exercise")
    class WhatTheChecksExercise {

        @Test
        @DisplayName("the telemetry check opens a sealed value before declaring telemetry clean")
        void theTelemetryCheckOpensASealedValue() {
            // The half of "no sensitive value in telemetry" that a NORMAL-only run can never reach:
            // a NORMAL search opens nothing, because nothing was ever sealed. If the HIGH leg were
            // dropped the check would still pass, and the claim it is named for would be untested.
            // The recording mapper is the only place that can observe an envelope being opened.
            RecordingSecureMapper mapper = new RecordingSecureMapper();
            ConformanceSubject<SyntheticDocument> subject =
                    SyntheticWiring.subject(client, SyntheticWiring.resolver(), mapper).build();

            ChecksUnderTest.run(subject, ChecksUnderTest.TELEMETRY);

            assertThat(mapper.opened())
                    .as("the check must have opened at least one sealed value to have tested anything")
                    .isNotEmpty();
        }
    }

    @Nested
    @DisplayName("the cluster the fixture stands up")
    class TheCluster {

        @Test
        @DisplayName("answers over HTTPS with basic auth, which is what the checks are run against")
        void answersOverHttpsWithBasicAuth() throws IOException {
            // A conformance run against an unsecured cluster proves less than it looks: two of the
            // six checks turn on the cluster enforcing something, and a cluster with the plugin
            // disabled is a different cluster.
            assertThat(client.cluster().health().numberOfNodes()).isEqualTo(1);
        }

        @Test
        @DisplayName("holds both families of the synthetic domain, provisioned and aliased")
        void holdsBothFamilies() throws IOException {
            for (int pool = 0; pool < SyntheticWiring.POOL_COUNT; pool++) {
                String plaintext = "conformance-pool-" + pool + "-000001";
                String secure = "conformance-secure-pool-" + pool + "-000001";
                assertThat(client.indices().exists(e -> e.index(plaintext)).value())
                        .as("plaintext pool %d", pool).isTrue();
                assertThat(client.indices().exists(e -> e.index(secure)).value())
                        .as("secure pool %d", pool).isTrue();
            }
        }

        @Test
        @DisplayName("refuses an unmapped field with an error that names it")
        void refusesAnUnmappedFieldByName() {
            // What ToolkitConformance's cluster-side check depends on being true of a real cluster:
            // strict_dynamic_mapping_exception must name the offending field. If a future OpenSearch
            // changed that wording, this test says why the conformance check broke, rather than
            // leaving a domain module to guess.
            String secureWriteAlias = SyntheticWiring.resolver().writeIndex(SyntheticWiring.HIGH);

            assertThatThrownBy(() -> client.index(i -> i
                    .index(secureWriteAlias)
                    .id("conformance-probe-" + UUID.randomUUID())
                    .document(Map.of(
                            "tenantId", SyntheticWiring.HIGH.tenantId(),
                            SyntheticWiring.SECRET_LABEL.fieldName(), "a-readable-value"))))
                    .hasMessageContaining("strict_dynamic_mapping_exception")
                    .hasMessageContaining(SyntheticWiring.SECRET_LABEL.fieldName());
        }

        @Test
        @DisplayName("maps no plaintext sensitive field in the secure family")
        void secureFamilyMapsNoPlaintextField() throws IOException {
            // Asserted directly as well as through the suite, because this is the one guarantee the
            // suite's own cluster check depends on being true for the fixture.
            var properties = client.indices()
                    .getMapping(m -> m.index("conformance-secure-pool-0-000001"))
                    .result().get("conformance-secure-pool-0-000001").mappings().properties();

            assertThat(properties).doesNotContainKey(SyntheticWiring.SECRET_LABEL.fieldName());
            assertThat(properties).containsKeys(
                    SyntheticWiring.SECRET_LABEL.tokensField(),
                    SyntheticWiring.SECRET_LABEL.prefixesField(),
                    SyntheticWiring.SECRET_LABEL.cipherField());
        }
    }

    /** Seals and opens for real, and remembers every value it opened. */
    private static final class RecordingSecureMapper implements SecureDocumentMapper<SyntheticDocument> {

        private final SecureDocumentMapper<SyntheticDocument> sealed =
                SyntheticWiring.secureMapper(SyntheticWiring.keys());
        private final List<String> opened = new ArrayList<>();

        List<String> opened() {
            return opened;
        }

        @Override
        public Object toDocument(TenantRef tenant, SyntheticDocument source) {
            return sealed.toDocument(tenant, source);
        }

        @Override
        public SyntheticDocument fromDocument(TenantRef tenant, String documentId, Map<String, Object> source) {
            SyntheticDocument document = sealed.fromDocument(tenant, documentId, source);
            opened.add(SyntheticWiring.sensitiveValueOf(document));
            return document;
        }
    }

    /**
     * Seals correctly, then copies the readable value into an ordinary mapped field.
     *
     * <p>The interesting kind of leak: nothing about the declared sensitive field is wrong, the
     * cluster's strict mapping is satisfied, and a check that only looked for the declared key would
     * report the document clean.
     */
    private static final class LeakingSecureMapper implements SecureDocumentMapper<SyntheticDocument> {

        private final SecureDocumentMapper<SyntheticDocument> sealed =
                SyntheticWiring.secureMapper(SyntheticWiring.keys());

        @Override
        public Object toDocument(TenantRef tenant, SyntheticDocument source) {
            Map<String, Object> fields = ((SecureDocument) sealed.toDocument(tenant, source)).toMutableMap();
            fields.put("note", source.secretLabel());
            return new SecureDocument(fields);
        }

        @Override
        public SyntheticDocument fromDocument(TenantRef tenant, String documentId, Map<String, Object> source) {
            return sealed.fromDocument(tenant, documentId, source);
        }
    }

    /**
     * A second domain whose secure template does define its sensitive field, because the field was
     * contributed as a shared property rather than as a {@link SensitiveFieldMapping}.
     *
     * <p>A real and easy mistake: the installer only omits the plaintext property for fields it was
     * told are sensitive, so a field listed in both places is mapped in both families. The cluster
     * then accepts a readable value into the secure family, which is exactly what the suite's
     * cluster-side check exists to refuse.
     */
    private static final class PorousDomain {

        static final SearchDomain DOMAIN = new SearchDomain("porous");
        static final SearchDomains DOMAINS = SearchDomains.of(DOMAIN);
        static final int POOL_COUNT = 1;

        static final TenantRef NORMAL = TenantRef.of(DOMAIN, "tenant-alpha");
        static final TenantRef OTHER_NORMAL = TenantRef.of(DOMAIN, "tenant-beta");
        static final TenantRef HIGH = TenantRef.of(DOMAIN, "tenant-high");
        static final TenantRef KEYLESS_HIGH = TenantRef.of(DOMAIN, "tenant-keyless");

        private PorousDomain() {
        }

        static DomainMapping mapping() {
            return new DomainMapping(
                    settings -> settings.numberOfShards(1).numberOfReplicas(0),
                    m -> m.properties("tenantId", p -> p.keyword(k -> k))
                            .properties("documentId", p -> p.keyword(k -> k))
                            .properties("note", p -> p.text(t -> t))
                            // The mistake: contributed to every family, secure one included.
                            .properties(SyntheticWiring.SECRET_LABEL.fieldName(), p -> p.text(t -> t)),
                    List.of(new SensitiveFieldMapping(SyntheticWiring.SECRET_LABEL, p -> p.text(t -> t))));
        }

        static ConformanceSubject<SyntheticDocument> subject(OpenSearchClient client) {
            TenantIndexResolver resolver = new CatalogTenantIndexResolver(
                    new InMemoryTenantCatalog(DOMAINS, domain -> POOL_COUNT, List.of(
                            InMemoryTenantCatalog.pooled(HIGH, PrivacyLevel.HIGH, POOL_COUNT),
                            InMemoryTenantCatalog.pooled(KEYLESS_HIGH, PrivacyLevel.HIGH, POOL_COUNT))),
                    DOMAINS);
            SecureDocumentMapper<SyntheticDocument> mapper = SyntheticWiring.secureMapper(
                    ConformanceTenantKeys.forTenants(NORMAL.tenantId(), OTHER_NORMAL.tenantId(), HIGH.tenantId()));
            return ConformanceSubject.<SyntheticDocument>forDomain(DOMAIN)
                    .client(client)
                    .resolver(resolver)
                    .documents(SyntheticDocument.class, SyntheticWiring.documents(),
                            SyntheticWiring::sensitiveValueOf)
                    .writeDocuments(SyntheticWiring.writeDocuments(resolver, mapper))
                    .secureFields(List.<SecureFieldSpec>of(SyntheticWiring.SECRET_LABEL), mapper)
                    .tenants(NORMAL, OTHER_NORMAL, HIGH, KEYLESS_HIGH)
                    .build();
        }
    }
}
