package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.SearchResults;
import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.KeyUnavailableException;
import io.twba.search.toolkit.opensearch.dp.DocumentIndexer;
import io.twba.search.toolkit.opensearch.dp.SecureDocument;
import io.twba.search.toolkit.opensearch.dp.TenantScopedSearchExecutor;
import io.twba.search.toolkit.opensearch.obs.OpenSearchObservations;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invariants every domain must hold, asserted against a live cluster, without the domain module
 * writing the assertions.
 *
 * <p>A domain module runs it as one {@code @TestFactory}:
 *
 * <pre>{@code
 * @TestFactory
 * Stream<DynamicTest> conformance() {
 *     return ToolkitConformance.tests(subject);
 * }
 * }</pre>
 *
 * <p>Dynamic tests rather than an abstract class the module extends. A base class would put the
 * suite's setup in the subclass's lifecycle, where a module can override a method and change what
 * conformance means — and a conformance suite a subject can weaken is a certificate the subject
 * issues to itself. Handing back tests gives per-check reporting for free and nothing to override.
 *
 * <p>What is asserted, and why these four:
 *
 * <ol>
 *   <li><strong>Tenant isolation.</strong> The failure everything else is arranged to prevent.</li>
 *   <li><strong>A keyless {@code HIGH} tenant fails closed.</strong> The dangerous half of a crypto
 *       misconfiguration is not the exception — it is the blank value that reads as a patient with no
 *       recorded name.</li>
 *   <li><strong>No readable sensitive value in the secure family.</strong> Asserted against the
 *       cluster, because the guarantee is a mapping, not a convention: it must hold against a
 *       hand-rolled write that never went through the toolkit at all.</li>
 *   <li><strong>Nothing sensitive in telemetry.</strong> The leak that survives every other control,
 *       because a meter tag and a span attribute are copies of the data outside the store that
 *       protects the original.</li>
 * </ol>
 */
public final class ToolkitConformance {

    /** The fixed-shape values the suite plants and then hunts for. Distinctive on purpose. */
    private static final String ISOLATED_VALUE = "conformance-isolated-value";
    private static final String SEALED_VALUE = "conformance-sealed-value";
    private static final String QUERY_SENTINEL = "conformance-query-sentinel";

    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> SOURCE_MAP =
            (Class<Map<String, Object>>) (Class<?>) Map.class;

    private ToolkitConformance() {
    }

    /**
     * That the failure was about the key, not about something else that happened to raise first.
     *
     * <p>Asserted by type rather than by "it threw", because every interesting bug here throws
     * something: a guard that fires too early, a payload shape the check cannot read, a typo in a
     * fixture. A conformance check that accepts any exception as proof of a fail-closed posture is a
     * check that passes for a system which never got near the key.
     */
    private static void becauseTheKeyIsMissing(Throwable thrown) {
        assertThat(causeChain(thrown))
                .as("the refusal must be about the missing key material, not an earlier guard")
                .hasAtLeastOneElementOfType(KeyUnavailableException.class);
    }

    /** No sensitive value in a message that will reach a log or an error response. */
    private static void withoutQuotingTheValue(Throwable thrown) {
        assertThat(causeChain(thrown))
                .allSatisfy(link -> assertThat(String.valueOf(link.getMessage()))
                        .as("no exception in the chain may quote the value it refused to handle")
                        .doesNotContain(SEALED_VALUE));
    }

    private static List<Throwable> causeChain(Throwable thrown) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable link = thrown; link != null && !chain.contains(link); link = link.getCause()) {
            chain.add(link);
        }
        return chain;
    }

    public static <T extends TenantDocument> Stream<DynamicTest> tests(ConformanceSubject<T> subject) {
        Runner<T> runner = new Runner<>(subject);
        Stream<DynamicTest> always = Stream.of(
                DynamicTest.dynamicTest(
                        "a tenant's search returns no other tenant's documents", runner::tenantIsolation),
                DynamicTest.dynamicTest(
                        "nothing sensitive reaches meters or spans", runner::nothingSensitiveInTelemetry));
        if (subject.specs().isEmpty()) {
            // A domain with no sensitive fields has no secure family to check and no key material to
            // withhold. Silently skipping would be indistinguishable from the checks passing, so the
            // run says so.
            return Stream.concat(always, Stream.of(DynamicTest.dynamicTest(
                    "this domain declares no sensitive fields, so the HIGH checks do not apply",
                    () -> assertThat(subject.specs()).isEmpty())));
        }
        return Stream.concat(always, Stream.of(
                DynamicTest.dynamicTest(
                        "a HIGH tenant with no key material fails closed on write", runner::keylessWriteFailsClosed),
                DynamicTest.dynamicTest(
                        "a HIGH tenant with no key material fails closed on read", runner::keylessReadFailsClosed),
                DynamicTest.dynamicTest(
                        "the secure family stores no readable sensitive value", runner::noReadableValueStored),
                DynamicTest.dynamicTest(
                        "the cluster refuses a readable sensitive value", runner::clusterRefusesReadableValue)));
    }

    /** Holds the wiring the suite builds for itself, so each check reads as its assertion. */
    private static final class Runner<T extends TenantDocument> {

        private final ConformanceSubject<T> subject;
        private final RecordedTelemetry telemetry = new RecordedTelemetry();
        private final DocumentIndexer<T> indexer;
        private final TenantScopedSearchExecutor<T> executor;

        private Runner(ConformanceSubject<T> subject) {
            this.subject = subject;
            this.indexer = new DocumentIndexer<>(subject.client(), subject.domain(),
                    subject.resolver(), subject.writeDocuments());
            this.executor = new TenantScopedSearchExecutor<>(subject.client(), subject.domain(),
                    subject.resolver(), subject.documentType(), telemetry.observations(),
                    subject.secureMapper());
        }

        void tenantIsolation() throws IOException {
            TenantRef mine = subject.normalTenant();
            TenantRef theirs = subject.otherNormalTenant();
            String theirDocumentId = index(theirs, ISOLATED_VALUE);
            index(mine, ISOLATED_VALUE);
            refresh(theirs, mine);

            SearchResults<T> results = executor.search(mine, "conformanceIsolation",
                    matchAll(), everything());

            assertThat(results.hits())
                    .as("a search for '%s' must return only its own documents", mine)
                    .isNotEmpty()
                    .allSatisfy(hit -> assertThat(hit.document().tenantId()).isEqualTo(mine.tenantId()));
            assertThat(results.hits().stream().map(io.twba.search.toolkit.SearchHit::documentId))
                    .as("the other tenant's document must not be reachable")
                    .doesNotContain(theirDocumentId);
        }

        void keylessWriteFailsClosed() {
            TenantRef keyless = subject.keylessHighTenant();
            T document = subject.document(keyless, SEALED_VALUE);

            // forWrite, not index: the point is that the payload is never shaped, so nothing reaches
            // the cluster at all. Asserting on the index call would pass for an implementation that
            // built a plaintext payload and happened to be refused by the mapping.
            assertThatThrownBy(() -> subject.writeDocuments().forWrite(keyless, document))
                    .as("a HIGH tenant with no key material must raise rather than fall back")
                    .satisfies(ToolkitConformance::becauseTheKeyIsMissing)
                    .satisfies(ToolkitConformance::withoutQuotingTheValue);
        }

        void keylessReadFailsClosed() {
            TenantRef sealedFor = subject.highTenant();
            TenantRef keyless = subject.keylessHighTenant();
            T document = subject.document(sealedFor, SEALED_VALUE);

            // Sealed for a tenant that has a key, then restamped as the keyless one — which is what a
            // destroyed key actually leaves behind: the documents are still there, still stamped with
            // the tenant they belong to, and the material that opens them is gone.
            //
            // The restamp is load-bearing. The mapper checks the stored tenant id against the reading
            // tenant before it touches any key material, so reading another tenant's document raises
            // on that guard and never reaches the key at all. A check written that way passes
            // identically whether the key is there or not, which is to say it checks nothing.
            Map<String, Object> source = new LinkedHashMap<>(sourceOf(subject.writeDocuments()
                    .forWrite(sealedFor, document)));
            source.put(TenantDocument.TENANT_ID_FIELD, keyless.tenantId());

            assertThatThrownBy(() -> subject.secureMapper()
                    .fromDocument(keyless, document.documentId(), source))
                    .as("a sealed value whose key is gone must raise, never open to a blank")
                    .satisfies(ToolkitConformance::becauseTheKeyIsMissing)
                    .satisfies(ToolkitConformance::withoutQuotingTheValue);
        }

        void noReadableValueStored() throws IOException {
            TenantRef tenant = subject.highTenant();
            String documentId = index(tenant, SEALED_VALUE);
            refresh(tenant);

            Map<String, Object> stored = fetchSource(tenant, documentId);
            assertThat(stored).as("the sealed document must come back").isNotNull();

            for (SecureFieldSpec spec : subject.specs()) {
                assertThat(stored)
                        .as("the secure family must define no property for '%s'", spec.fieldName())
                        .doesNotContainKey(spec.fieldName());
            }
            // The whole source, not just the declared keys: a value copied into some other field by a
            // leaking extractor would pass a key-by-key check and still be readable.
            assertThat(String.valueOf(stored).toLowerCase(Locale.ROOT))
                    .as("no readable sensitive value anywhere in the stored source")
                    .doesNotContain(SEALED_VALUE.toLowerCase(Locale.ROOT));
        }

        void clusterRefusesReadableValue() {
            TenantRef tenant = subject.highTenant();
            String field = subject.specs().getFirst().fieldName();
            Map<String, Object> readable = Map.of(
                    TenantDocument.TENANT_ID_FIELD, tenant.tenantId(),
                    field, SEALED_VALUE);

            // Straight at the cluster, bypassing the toolkit entirely. This is the assertion that the
            // guarantee is structural: it must hold for a curl, a migration script, or an adapter bug.
            assertThatThrownBy(() -> subject.client().index(i -> i
                    .index(subject.resolver().writeIndex(tenant))
                    .id("conformance-readable-" + UUID.randomUUID())
                    .routing(subject.resolver().routing(tenant).orElse(null))
                    .document(readable)))
                    .as("the secure family's strict mapping must refuse '%s'", field)
                    .isInstanceOf(Exception.class)
                    .satisfies(thrown -> assertThat(String.valueOf(thrown.getMessage()))
                            .as("the cluster's refusal should name the offending field")
                            .contains(field));
        }

        void nothingSensitiveInTelemetry() throws IOException {
            TenantRef tenant = subject.normalTenant();
            index(tenant, ISOLATED_VALUE);
            refresh(tenant);

            // A search whose query text carries a sentinel, on the one field the toolkit is allowed to
            // name, and a search that returns real documents.
            executor.search(tenant, "conformanceSentinel", Query.of(q -> q.term(t -> t
                    .field(TenantDocument.TENANT_ID_FIELD)
                    .value(v -> v.stringValue(QUERY_SENTINEL)))), everything());
            executor.search(tenant, "conformanceTelemetry", matchAll(), everything());

            // And, where the domain has sealed fields, a search that actually opens one. Without this
            // leg the "no opened value in telemetry" half of the claim is never exercised, because a
            // NORMAL search opens nothing: the values were never sealed.
            if (!subject.specs().isEmpty()) {
                TenantRef sealed = subject.highTenant();
                index(sealed, SEALED_VALUE);
                refresh(sealed);
                SearchResults<T> opened = executor.search(sealed, "conformanceOpened", matchAll(), everything());
                assertThat(opened.documents())
                        .as("the HIGH leg must actually open a sealed value, or it proves nothing")
                        .anySatisfy(document ->
                                assertThat(subject.sensitiveValueOf(document)).isEqualTo(SEALED_VALUE));
                assertThat(telemetry.everythingPublished())
                        .as("an opened value must not reach telemetry")
                        .noneSatisfy(value -> assertThat(value).contains(SEALED_VALUE));
            }

            List<String> published = telemetry.everythingPublished();
            assertThat(published)
                    .as("the query text must not reach telemetry")
                    .noneSatisfy(value -> assertThat(value).contains(QUERY_SENTINEL));
            assertThat(published)
                    .as("no stored or opened value may reach telemetry")
                    .noneSatisfy(value -> assertThat(value).contains(ISOLATED_VALUE));
            assertThat(telemetry.everyMeterTagValue())
                    .as("a tenant id is a span attribute, never a meter tag")
                    .doesNotContain(tenant.tenantId());
            assertThat(telemetry.keyValuesOf(OpenSearchObservations.SEARCH_OBSERVATION))
                    .as("the tenant id does belong on the span")
                    .contains(tenant.tenantId());
        }

        // ------------------------------------------------------------------ helpers

        private String index(TenantRef tenant, String value) throws IOException {
            T document = subject.document(tenant, value);
            assertThat(subject.sensitiveValueOf(document))
                    .as("the subject's document factory and value reader must agree")
                    .isEqualTo(value);
            indexer.index(document);
            return document.documentId();
        }

        private void refresh(TenantRef... tenants) throws IOException {
            for (TenantRef tenant : tenants) {
                String target = subject.resolver().writeIndex(tenant);
                subject.client().indices().refresh(r -> r.index(target));
            }
        }

        private Map<String, Object> fetchSource(TenantRef tenant, String documentId) throws IOException {
            return subject.client().get(g -> g
                    .index(subject.resolver().searchIndex(tenant))
                    .id(documentId)
                    .routing(subject.resolver().routing(tenant).orElse(null)), SOURCE_MAP)
                    .source();
        }

        /**
         * The sealed payload as the {@code _source} map the mapper's read side expects.
         *
         * <p>The write side returns {@code Object} on purpose — only the serializer needs to know the
         * shape — so this check has to narrow it back. It does so by serializing the payload with the
         * client's own mapper and reading the result as a map, rather than by testing for the shapes
         * the toolkit happens to produce today. {@code SecureDocumentMapper} exists so an application
         * can supply its own implementation, and the very implementation this toolkit generalizes
         * returned an eighteen-component record from that seam; a check that understood only
         * {@link SecureDocument} and {@link Map} would refuse the first realistic one it met.
         *
         * <p>Round-tripping through the client is also the more honest question. What ends up in
         * {@code _source} is whatever the serializer writes, so that is the thing worth inspecting.
         */
        private Map<String, Object> sourceOf(Object payload) {
            JsonpMapper mapper = subject.client()._transport().jsonpMapper();
            StringWriter json = new StringWriter();
            try (JsonGenerator generator = mapper.jsonProvider().createGenerator(json)) {
                mapper.serialize(payload, generator);
            }
            try (JsonParser parser = mapper.jsonProvider().createParser(new StringReader(json.toString()))) {
                return mapper.deserialize(parser, SOURCE_MAP);
            }
        }

        private static Query matchAll() {
            return Query.of(q -> q.matchAll(m -> m));
        }

        private static java.util.function.UnaryOperator<SearchRequest.Builder> everything() {
            return s -> s.size(50);
        }
    }

}
