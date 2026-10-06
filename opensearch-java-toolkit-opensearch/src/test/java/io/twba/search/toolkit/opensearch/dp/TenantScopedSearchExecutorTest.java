package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.ErrorSearchingDataException;
import io.twba.search.toolkit.IdentifierProbeThrottledException;
import io.twba.search.toolkit.MatchKind;
import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchDomains;
import io.twba.search.toolkit.SearchHit;
import io.twba.search.toolkit.SearchResults;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.BlindIndexer;
import io.twba.search.toolkit.crypto.SealedFieldCipher;
import io.twba.search.toolkit.crypto.TenantKeyProvider;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.controls.CallerIdentity;
import io.twba.search.toolkit.opensearch.controls.IdentifierSearchAudit;
import io.twba.search.toolkit.opensearch.controls.IdentifierSearchLimiter;
import io.twba.search.toolkit.opensearch.controls.LoggingIdentifierSearchAudit;
import io.twba.search.toolkit.opensearch.controls.RecordingIdentifierSearchAudit;
import io.twba.search.toolkit.opensearch.cp.InMemoryTenantCatalog;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.MigrationState;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Placement;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Tier;
import io.twba.search.toolkit.opensearch.obs.OpenSearchObservations;
import io.twba.search.toolkit.opensearch.obs.RecordingTelemetry;
import io.twba.search.toolkit.opensearch.testlog.RecordedLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.search.Hit;

import java.io.IOException;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The one component every read in the system goes through, driven over a hand-written transport so
 * that what it sent and what it returned are both observable.
 *
 * <p>The properties asserted here are the ones an integration test cannot see. A Testcontainers run
 * proves that a search returns the right documents for the tenant it asked about — but it cannot
 * prove that the tenant clause was in <em>filter</em> context rather than scoring context, that the
 * caller's shaping function could not quietly widen the search, that the budget was claimed
 * <em>before</em> the cluster was touched rather than after, or that the query text stayed out of
 * every meter, span and log line. All of those pass an end-to-end test while being wrong.
 *
 * <p>Two document shapes are in play, both of them the <em>same</em> application type. That is the
 * point of the "callers see one result shape" requirement: a {@code NORMAL} hit arrives already
 * deserialized, a {@code HIGH} hit arrives as a map of sealed components and is opened on the way
 * out, and the caller cannot tell from the return type which happened. The {@code HIGH} half runs
 * against the real {@link SpecDrivenSecureDocumentMapper} with real crypto, because a fake mapper
 * would make "sealed fields are opened before results are returned" an assertion about the fake.
 */
class TenantScopedSearchExecutorTest {

    private static final SearchDomain LAB = new SearchDomain("lab-results");
    private static final SearchDomain CLAIMS = new SearchDomain("claims");
    private static final SearchDomains DOMAINS = SearchDomains.of(LAB, CLAIMS);

    private static final String NORMAL_TENANT = "clinic-a";
    private static final String HIGH_TENANT = "clinic-guarded";
    private static final String DEDICATED_TENANT = "clinic-solo";

    private static final TenantRef LAB_NORMAL = TenantRef.of(LAB, NORMAL_TENANT);
    private static final TenantRef LAB_HIGH = TenantRef.of(LAB, HIGH_TENANT);
    private static final TenantRef LAB_DEDICATED = TenantRef.of(LAB, DEDICATED_TENANT);
    /** The same organisation, in another domain: two placements, two budgets, two key sets. */
    private static final TenantRef CLAIMS_NORMAL = TenantRef.of(CLAIMS, NORMAL_TENANT);

    private static final String NORMAL_INDEX = "lab-results-pool-1";
    private static final String HIGH_INDEX = "lab-results-secure-pool-2";
    private static final String DEDICATED_INDEX = "lab-results-clinic-solo";
    private static final String CLAIMS_INDEX = "claims-pool-1";

    private static final String OPERATION = "search-notes";
    private static final String PROBE_OPERATION = "search-by-subject";
    private static final String CALLER = "test-caller";

    /** Obviously fake, and unlike anything a tag, meter name or index name legitimately contains. */
    private static final String QUERY_SENTINEL = "Ziphius-Cavirostris";
    private static final String VALUE_SENTINEL = "Nyx-Pellucida";

    private static final UnaryOperator<SearchRequest.Builder> NO_SHAPE = shape -> shape;

    private final SearchingOpenSearchTransport transport = new SearchingOpenSearchTransport();
    private final OpenSearchClient client = new OpenSearchClient(transport);
    private final TenantIndexResolver resolver = new CatalogTenantIndexResolver(catalog(), DOMAINS);

    private final TenantKeyProvider keys = DerivedTenantKeys.forTenants(HIGH_TENANT);
    private final BlindIndexer blindIndexer = new BlindIndexer(keys);
    private final SealedFieldCipher cipher = new SealedFieldCipher(keys);
    private final SpecDrivenSecureDocumentMapper<FakeSealedDocument> secureMapper =
            new SpecDrivenSecureDocumentMapper<>(FakeSealedDocument.SPECS, FakeSealedDocument::plain,
                    FakeSealedDocument::fromPlain, blindIndexer, cipher);

    private final RecordingTelemetry telemetry = new RecordingTelemetry();
    private final RecordingIdentifierSearchAudit audit = new RecordingIdentifierSearchAudit();
    private final IdentifierSearchLimiter limiter = new IdentifierSearchLimiter(3, Duration.ofMinutes(1));

    @BeforeEach
    void clearLogs() {
        RecordedLogs.clear();
    }

    @Nested
    @DisplayName("every search carries the tenant filter")
    class TenantScoping {

        @Test
        @DisplayName("the tenant clause is a filter, and the caller's query is what is scored")
        void theTenantClauseIsInFilterContext() {
            Query userQuery = noteQuery("hemolyzed");

            executor().search(LAB_NORMAL, OPERATION, userQuery, NO_SHAPE);

            BoolQuery scoped = transport.onlySearch().query().bool();
            // filter, not must: a tenant clause in scoring context skips the filter cache and makes
            // a document's rank depend on how many documents its tenant owns.
            assertThat(scoped.filter()).singleElement().satisfies(clause -> {
                assertThat(clause.term().field()).isEqualTo(TenantDocument.TENANT_ID_FIELD);
                assertThat(clause.term().value().stringValue()).isEqualTo(NORMAL_TENANT);
            });
            assertThat(scoped.must()).containsExactly(userQuery);
            assertThat(scoped.should()).isEmpty();
        }

        @Test
        @DisplayName("a HIGH tenant's search is filtered the same way, on the same field")
        void theHighPathIsScopedToo() throws Exception {
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.noHits());

            highExecutor().search(LAB_HIGH, OPERATION, noteQuery("anything"), NO_SHAPE);

            assertThat(transport.onlySearch().query().bool().filter()).singleElement()
                    .satisfies(clause -> assertThat(clause.term().value().stringValue()).isEqualTo(HIGH_TENANT));
        }

        @Test
        @DisplayName("the index and the routing value come from the resolver, never from the caller")
        void topologyComesFromTheResolver() {
            executor().search(LAB_NORMAL, OPERATION, noteQuery("x"), NO_SHAPE);

            assertThat(transport.onlySearch().index()).containsExactly(NORMAL_INDEX);
            assertThat(transport.onlySearch().routing()).isEqualTo(NORMAL_TENANT);
        }

        @Test
        @DisplayName("an unrouted (dedicated) tenant sends no routing value at all")
        void anUnroutedTenantSendsNoRouting() {
            executor().search(LAB_DEDICATED, OPERATION, noteQuery("x"), NO_SHAPE);

            // Dropping routing costs fan-out and never correctness; inventing one would send the
            // search to a single wrong shard.
            assertThat(transport.onlySearch().routing()).isNull();
            assertThat(transport.onlySearch().index()).containsExactly(DEDICATED_INDEX);
        }

        @Test
        @DisplayName("privacy level alone picks the index family")
        void privacyLevelPicksTheIndexFamily() throws Exception {
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.noHits());

            highExecutor().search(LAB_HIGH, OPERATION, noteQuery("x"), NO_SHAPE);

            assertThat(transport.onlySearch().index()).containsExactly(HIGH_INDEX);
        }
    }

    /**
     * The three values a caller does not get to choose, and the two different ways the executor
     * keeps them.
     *
     * <p><strong>Discarded vs refused is not an inconsistency.</strong> {@code routing} and
     * {@code query} are plain setters on {@code SearchRequest.Builder}, so setting them after the
     * shaping function wins outright and the caller's value is simply gone — there is no residue to
     * detect afterwards and nothing to report. {@code index} is different in kind: the builder
     * <em>appends</em> ({@code _listAdd} / {@code _listAddAll}), so a target the caller named
     * survives alongside the resolved one and has to be detected and refused. Anyone tempted to
     * "make these consistent" by turning the first two into refusals should know they would be
     * trading a guarantee for a diagnostic: today a shaped query or routing value <em>cannot</em>
     * reach the cluster, whatever the caller does.
     */
    @Nested
    @DisplayName("the caller's shaping function cannot unscope the search")
    class HostileShaping {

        @Test
        @DisplayName("a shaping function that sets its own query has it discarded")
        void aShapeCannotReplaceTheScopedQuery() {
            Query userQuery = noteQuery("legitimate");
            Query hostile = noteQuery("everything");

            executor().search(LAB_NORMAL, OPERATION, userQuery, shape -> shape.query(hostile));

            // Discarded, not refused: .query(...) is a plain setter, so the scoped query set last
            // is the only one that exists by the time the request is built.
            BoolQuery scoped = transport.onlySearch().query().bool();
            assertThat(scoped.must()).containsExactly(userQuery);
            assertThat(scoped.filter()).hasSize(1);
        }

        @Test
        @DisplayName("a shaping function that sets its own routing has it discarded")
        void aShapeCannotRedirectRouting() {
            executor().search(LAB_NORMAL, OPERATION, noteQuery("x"), shape -> shape.routing("some-other-tenant"));

            // Same reason as the query above, and the same guarantee: a plain setter, set last.
            assertThat(transport.onlySearch().routing()).isEqualTo(NORMAL_TENANT);
        }

        @Test
        @DisplayName("a shaping function that names a target is refused, naming both targets")
        void aShapeThatNamesATargetIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> executor().search(LAB_NORMAL, OPERATION, noteQuery("x"),
                            shape -> shape.index(CLAIMS_INDEX)))
                    .withMessageContaining(OPERATION)
                    // Both targets: which one the tenant resolves to, and what the request would
                    // have searched. A refusal naming only one leaves the caller guessing which of
                    // its own call sites widened the search.
                    .withMessageContaining(NORMAL_INDEX)
                    .withMessageContaining(CLAIMS_INDEX);

            // The point of refusing at all: the widened search must never be issued, so this must
            // be zero rather than "one request that happened to be scoped".
            assertThat(transport.searchCount()).isZero();
        }

        @Test
        @DisplayName("the list form of index is refused too, for the same reason")
        void aShapeThatNamesTargetsAsAListIsRefused() {
            // index(List) is _listAddAll, the same appending semantics as index(String, String...),
            // and it is the form a caller reaches for when it has a collection in hand.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> executor().search(LAB_NORMAL, OPERATION, noteQuery("x"),
                            shape -> shape.index(List.of(CLAIMS_INDEX, "some-other-index"))))
                    .withMessageContaining(CLAIMS_INDEX);

            assertThat(transport.searchCount()).isZero();
        }

        @Test
        @DisplayName("even re-naming the resolved target is refused, because the caller does not own it")
        void aShapeThatRepeatsTheResolvedTargetIsRefused() {
            // The request would search ["lab-results-pool-1", "lab-results-pool-1"] — harmless
            // today, and still a call site that believes it chooses the target. It is refused on
            // the same ground as any other: the shaping function is for page size, highlighting and
            // source filtering, and topology is not negotiable.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> executor().search(LAB_NORMAL, OPERATION, noteQuery("x"),
                            shape -> shape.index(NORMAL_INDEX)));

            assertThat(transport.searchCount()).isZero();
        }

        @Test
        @DisplayName("a shaping function that returns null is refused rather than raising inside the executor")
        void aShapeThatReturnsNullIsRefused() {
            // The honest mistake: a lambda written as a statement block that shapes the builder and
            // forgets to return it. The message names the operation, so the caller can find it.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> executor().search(LAB_NORMAL, OPERATION, noteQuery("x"), shape -> null))
                    .withMessageContaining(OPERATION);

            assertThat(transport.searchCount()).isZero();
        }

        @Test
        @DisplayName("everything the shaping function is for survives untouched")
        void legitimateShapingIsApplied() {
            executor().search(LAB_NORMAL, OPERATION, noteQuery("x"),
                    shape -> shape.size(25).from(50).trackTotalHits(t -> t.enabled(true)));

            SearchRequest request = transport.onlySearch();
            assertThat(request.size()).isEqualTo(25);
            assertThat(request.from()).isEqualTo(50);
            assertThat(request.trackTotalHits()).isNotNull();
        }
    }

    @Nested
    @DisplayName("privacy level selects the wire type")
    class PrivacyBranch {

        @Test
        @DisplayName("a HIGH hit is deserialized as a source map and its sealed fields are opened")
        void highHitsAreOpened() throws Exception {
            FakeSealedDocument original = sealedDocument("doc-1", "Marisol Quintanilla");
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.hit("doc-1", seal(original), 2.5, List.of(BranchNames.CONTENT))));

            SearchResults<FakeSealedDocument> results =
                    highExecutor().search(LAB_HIGH, OPERATION, noteQuery("x"), NO_SHAPE);

            // The whole point of the HIGH path: what comes back is the application's document with
            // the sensitive values readable, not a bag of envelope components.
            assertThat(results.documents()).containsExactly(original);
            assertThat(results.hits()).singleElement()
                    .satisfies(hit -> assertThat(hit.document().subjectName()).isEqualTo("Marisol Quintanilla"));
        }

        @Test
        @DisplayName("NORMAL and HIGH return the same type with sensitive fields populated")
        void oneResultShapeForBothLevels() throws Exception {
            FakeSealedDocument normal = new FakeSealedDocument(NORMAL_TENANT, "doc-n", "note", "Ada Plaintext", "h-1");
            FakeSealedDocument high = sealedDocument("doc-h", "Bea Sealed");
            transport.answering(
                    request -> SearchingOpenSearchTransport.hits(
                            SearchingOpenSearchTransport.hit("doc-n", normal, 1.0, List.of())),
                    request -> SearchingOpenSearchTransport.hits(
                            SearchingOpenSearchTransport.hit("doc-h", seal(high), 1.0, List.of())));

            SearchResults<FakeSealedDocument> normalResults =
                    highExecutor().search(LAB_NORMAL, OPERATION, noteQuery("x"), NO_SHAPE);
            SearchResults<FakeSealedDocument> highResults =
                    highExecutor().search(LAB_HIGH, OPERATION, noteQuery("x"), NO_SHAPE);

            // Same call site, same declared type, both with a readable subject name: the caller
            // passes no privacy-level parameter and cannot tell which path ran.
            assertThat(normalResults.documents()).containsExactly(normal);
            assertThat(highResults.documents()).containsExactly(high);
            assertThat(highResults.documents().getFirst().subjectName()).isEqualTo("Bea Sealed");
        }

        @Test
        @DisplayName("a HIGH hit with no _source is refused rather than returned with blank fields")
        void aSourcelessHighHitFailsClosed() {
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.sourcelessHit("doc-9")));

            // A document with blank sensitive fields is indistinguishable from one that never had
            // any — a privacy incident that reads like a data-entry problem.
            assertThatExceptionOfType(ErrorSearchingDataException.class)
                    .isThrownBy(() -> highExecutor().search(LAB_HIGH, OPERATION, noteQuery("x"), NO_SHAPE))
                    .withMessageContaining("doc-9")
                    .withMessageContaining(HIGH_TENANT);
        }

        @Test
        @DisplayName("a HIGH hit whose envelope was altered raises rather than returning a blank value")
        void anAlteredEnvelopeFailsClosed() throws Exception {
            Map<String, Object> altered = new java.util.LinkedHashMap<>(seal(sealedDocument("doc-1", "Someone Real")));
            altered.put(FakeSealedDocument.SUBJECT_NAME.cipherField(),
                    "YWx0ZXJlZC1jaXBoZXJ0ZXh0LXRoYXQtZG9lcy1ub3Qtb3Blbg");
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.hit("doc-1", altered, 1.0, List.of())));

            // The executor catches IOException only, on purpose: an authentication failure is not
            // a transport problem and must not be flattened into one, let alone into a hit whose
            // subject name is empty.
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> highExecutor().search(LAB_HIGH, OPERATION, noteQuery("x"), NO_SHAPE))
                    .withMessageContaining("doc-1")
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain("Someone Real"));
        }

        @Test
        @DisplayName("a HIGH tenant with no secure mapper wired is refused before the cluster is touched")
        void aMissingSecureMapperIsRefused() {
            TenantScopedSearchExecutor<FakeSealedDocument> plaintextOnly = new TenantScopedSearchExecutor<>(
                    client, LAB, resolver, FakeSealedDocument.class);

            assertThatIllegalStateException()
                    .isThrownBy(() -> plaintextOnly.search(LAB_HIGH, OPERATION, noteQuery("x"), NO_SHAPE))
                    .withMessageContaining(HIGH_TENANT);
            // Not merely "it threw": a search that ran and then failed to open would have exposed
            // the probe to the cluster and recorded a round trip that produced nothing.
            assertThat(transport.searchCount()).isZero();
        }

        @Test
        @DisplayName("no entry point asks the caller for a privacy level")
        void theCallerNeverStatesThePrivacyLevel() {
            // Privacy level is decided in one place, from the tenant's placement. A parameter here
            // would be a second place to decide it, and the dangerous half of a disagreement writes
            // a plaintext query against the secure family and finds nothing.
            assertThat(TenantScopedSearchExecutor.class.getDeclaredMethods())
                    .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                    .allSatisfy(method -> assertThat(method.getParameterTypes())
                            .as("%s", method.getName()).doesNotContain(PrivacyLevel.class));
        }

        @Test
        @DisplayName("a NORMAL tenant in a plaintext-only deployment still searches")
        void aPlaintextOnlyDeploymentStillServesNormalTenants() {
            TenantScopedSearchExecutor<FakeSealedDocument> plaintextOnly = new TenantScopedSearchExecutor<>(
                    client, LAB, resolver, FakeSealedDocument.class);

            assertThatCode(() -> plaintextOnly.search(LAB_NORMAL, OPERATION, noteQuery("x"), NO_SHAPE))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("match provenance survives into the result")
    class Provenance {

        @Test
        @DisplayName("a hit that matched only the identifier branch reports an identifier match and no highlights")
        void identifierOnlyHitsCarryNoHighlights() {
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.hit("doc-1", document("doc-1"), 1.0,
                            List.of(BranchNames.IDENTIFIER))));

            SearchResults<FakeSealedDocument> results =
                    executor().search(LAB_NORMAL, OPERATION, noteQuery("x"), NO_SHAPE);

            SearchHit<FakeSealedDocument> hit = results.hits().getFirst();
            assertThat(hit.matchedIdentifier()).isTrue();
            assertThat(hit.matchedContent()).isFalse();
            // There is nothing to highlight on a hashed field, and a UI that highlighted anything
            // here would be highlighting the wrong hit.
            assertThat(hit.hasHighlights()).isFalse();
        }

        @Test
        @DisplayName("a hit that matched both branches reports both, with the content fragments")
        void bothBranchesAreReported() {
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.highlightedHit("doc-1", document("doc-1"), 3.0,
                            List.of(BranchNames.CONTENT, BranchNames.IDENTIFIER),
                            Map.of("note", List.of("a <em>hemolyzed</em> sample")))));

            SearchHit<FakeSealedDocument> hit =
                    executor().search(LAB_NORMAL, OPERATION, noteQuery("x"), NO_SHAPE).hits().getFirst();

            assertThat(hit.matchedOn()).containsExactlyInAnyOrder(MatchKind.CONTENT, MatchKind.IDENTIFIER);
            assertThat(hit.firstHighlight("note")).isEqualTo("a <em>hemolyzed</em> sample");
        }

        @Test
        @DisplayName("scores, ids and the cluster's took time cross over unchanged")
        void theResultCarriesTheClustersNumbers() {
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.hit("doc-1", document("doc-1"), 4.5, List.of()),
                    SearchingOpenSearchTransport.hit("doc-2", document("doc-2"), 1.5, List.of())));

            SearchResults<FakeSealedDocument> results =
                    executor().search(LAB_NORMAL, OPERATION, noteQuery("x"), NO_SHAPE);

            assertThat(results.hits()).extracting(SearchHit::documentId).containsExactly("doc-1", "doc-2");
            assertThat(results.hits().getFirst().score()).isEqualTo(4.5);
            assertThat(results.totalHits()).isEqualTo(2);
            assertThat(results.totalIsExact()).isTrue();
            assertThat(results.maxScore()).isEqualTo(4.5);
        }
    }

    @Nested
    @DisplayName("an executor serves exactly one domain")
    class DomainBinding {

        @Test
        @DisplayName("search refuses a tenant from another domain, naming both")
        void searchRefusesAForeignTenant() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> executor().search(CLAIMS_NORMAL, OPERATION, noteQuery("x"), NO_SHAPE))
                    .withMessageContaining(NORMAL_TENANT)
                    .withMessageContaining(CLAIMS.name())
                    .withMessageContaining(LAB.name());

            assertThat(transport.searchCount()).isZero();
        }

        @Test
        @DisplayName("identifierSearch refuses a foreign tenant before it spends any budget")
        void identifierSearchRefusesAForeignTenantWithoutSpendingBudget() {
            IdentifierSearchLimiter oneProbe = new IdentifierSearchLimiter(1, Duration.ofMinutes(1));
            TenantScopedSearchExecutor<FakeSealedDocument> executor = executor(LAB, oneProbe, audit);

            assertThatIllegalArgumentException().isThrownBy(
                    () -> executor.identifierSearch(CLAIMS_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE));

            // The budget is the tenant's, not the mistake's: a wrong-domain call that consumed a
            // probe would let a buggy caller throttle a tenant it cannot even search.
            assertThatCode(() -> executor.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE))
                    .doesNotThrowAnyException();
            assertThat(audit.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the executor reports the domain it was bound to")
        void theDomainIsBoundAtConstruction() {
            assertThat(executor().domain()).isEqualTo(LAB);
            assertThat(executor(CLAIMS, limiter, audit).domain()).isEqualTo(CLAIMS);
        }

        @Test
        @DisplayName("a null tenant, operation, query or shape is refused rather than defaulted")
        void nullArgumentsAreRefused() {
            TenantScopedSearchExecutor<FakeSealedDocument> executor = executor();

            assertThatNullPointerException()
                    .isThrownBy(() -> executor.search(null, OPERATION, noteQuery("x"), NO_SHAPE));
            assertThatNullPointerException()
                    .isThrownBy(() -> executor.search(LAB_NORMAL, null, noteQuery("x"), NO_SHAPE));
            assertThatNullPointerException()
                    .isThrownBy(() -> executor.search(LAB_NORMAL, OPERATION, null, NO_SHAPE));
            assertThatNullPointerException()
                    .isThrownBy(() -> executor.search(LAB_NORMAL, OPERATION, noteQuery("x"), null));
            assertThat(transport.searchCount()).isZero();
        }
    }

    @Nested
    @DisplayName("identifier probes are budgeted before the cluster and audited after")
    class IdentifierProbes {

        @Test
        @DisplayName("a throttled probe touches no cluster, and is both counted and audited")
        void aThrottledProbeTouchesNoCluster() {
            TenantScopedSearchExecutor<FakeSealedDocument> executor =
                    executor(LAB, new IdentifierSearchLimiter(1, Duration.ofMinutes(1)), audit);
            executor.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE);

            assertThatExceptionOfType(IdentifierProbeThrottledException.class).isThrownBy(
                    () -> executor.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE));

            // Refused before the round trip: a throttled caller learns nothing, not even how long
            // the query would have taken, which is itself a signal.
            assertThat(transport.searchCount()).isEqualTo(1);
            assertThat(telemetry.meters().get("opensearch.search.identifier.throttled")
                    .tags("domain", LAB.name(), "operation", PROBE_OPERATION).counter().count()).isEqualTo(1.0);
            assertThat(audit.recordsOf(RecordingIdentifierSearchAudit.THROTTLED)).singleElement()
                    .satisfies(record -> {
                        assertThat(record.caller()).isEqualTo(CALLER);
                        assertThat(record.tenant()).isEqualTo(LAB_NORMAL);
                        assertThat(record.operation()).isEqualTo(PROBE_OPERATION);
                    });
        }

        @Test
        @DisplayName("a probe that matched names the caller, the tenant reference, the operation and the ids")
        void aMatchedProbeIsAudited() {
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.hit("doc-1", document("doc-1"), 2.0,
                            List.of(BranchNames.IDENTIFIER)),
                    SearchingOpenSearchTransport.hit("doc-2", document("doc-2"), 1.0,
                            List.of(BranchNames.CONTENT))));

            executor().identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE);

            assertThat(audit.recordsOf(RecordingIdentifierSearchAudit.MATCHED)).singleElement()
                    .satisfies(record -> {
                        assertThat(record.caller()).isEqualTo(CALLER);
                        assertThat(record.tenant()).isEqualTo(LAB_NORMAL);
                        assertThat(record.operation()).isEqualTo(PROBE_OPERATION);
                        // Only the hit whose identifier branch fired: "whose records they reached"
                        // is the audit fact, and doc-2 was reached through its free text.
                        assertThat(record.matchedDocumentIds()).containsExactly("doc-1");
                    });
        }

        @Test
        @DisplayName("a HIGH tenant's probe is audited exactly as a NORMAL one is")
        void highAndNormalAreBothAudited() throws Exception {
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.hit("doc-h", seal(sealedDocument("doc-h", "Someone Sealed")), 2.0,
                            List.of(BranchNames.IDENTIFIER))));

            highExecutor().identifierSearch(LAB_HIGH, PROBE_OPERATION, noteQuery("x"), NO_SHAPE);

            // An audit trail with a hole in it where the NORMAL tenants are is not an audit trail;
            // the same is true the other way round, so both levels are pinned.
            assertThat(audit.recordsOf(RecordingIdentifierSearchAudit.MATCHED)).singleElement()
                    .satisfies(record -> assertThat(record.tenant()).isEqualTo(LAB_HIGH));
        }

        @Test
        @DisplayName("a probe that matched no identifier writes no record")
        void anUnmatchedProbeIsNotAudited() {
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.hit("doc-2", document("doc-2"), 1.0, List.of(BranchNames.CONTENT))));

            executor().identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE);

            // Nobody's record was reached through their name. A trail padded with empty lookups is
            // one nobody reads.
            assertThat(audit.records()).isEmpty();
        }

        @Test
        @DisplayName("an operation the application did not declare a probe claims no budget and writes no record")
        void plainSearchesAreNotBudgeted() {
            TenantScopedSearchExecutor<FakeSealedDocument> executor =
                    executor(LAB, new IdentifierSearchLimiter(1, Duration.ofMinutes(1)), audit);
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.hit("doc-1", document("doc-1"), 1.0,
                            List.of(BranchNames.IDENTIFIER))));

            for (int i = 0; i < 5; i++) {
                executor.search(LAB_NORMAL, OPERATION, noteQuery("x"), NO_SHAPE);
            }

            assertThat(transport.searchCount()).isEqualTo(5);
            assertThat(audit.records()).isEmpty();
            // The single probe in the budget is still there to be spent.
            assertThatCode(() -> executor.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an executor wired without controls still budgets probes rather than allowing unlimited ones")
        void theDefaultBudgetAppliesWhenNoLimiterIsWired() {
            // "No limiter configured" must mean the default budget, never no budget: an application
            // that wires observations and a mapper but forgets the controls would otherwise expose
            // an unmetered guessing oracle, and nothing about its behaviour would say so.
            TenantScopedSearchExecutor<FakeSealedDocument> defaults = new TenantScopedSearchExecutor<>(
                    client, LAB, resolver, FakeSealedDocument.class, telemetry.observations(), secureMapper);

            for (int probe = 0; probe < 30; probe++) {
                defaults.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE);
            }

            assertThatExceptionOfType(IdentifierProbeThrottledException.class).isThrownBy(
                    () -> defaults.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE));
        }

        @Test
        @DisplayName("budget is scoped per domain and tenant, not per tenant id")
        void budgetsDoNotCrossDomains() {
            IdentifierSearchLimiter shared = new IdentifierSearchLimiter(1, Duration.ofMinutes(1));
            TenantScopedSearchExecutor<FakeSealedDocument> lab = executor(LAB, shared, audit);
            TenantScopedSearchExecutor<FakeSealedDocument> claims = executor(CLAIMS, shared, audit);

            lab.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE);
            assertThatExceptionOfType(IdentifierProbeThrottledException.class).isThrownBy(
                    () -> lab.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE));

            // Keyed on the bare tenant id instead, traffic in one domain would exhaust the window
            // protecting another — the tenant would experience a security control as an outage.
            assertThatCode(() -> claims.identifierSearch(CLAIMS_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE))
                    .doesNotThrowAnyException();
            assertThat(transport.searchRequests()).last()
                    .satisfies(request -> assertThat(request.index()).containsExactly(CLAIMS_INDEX));
        }
    }

    /**
     * The probe path's ordering invariant: <em>everything that can fail locally happens before the
     * rate-limit claim, and only the cluster call happens after it.</em>
     *
     * <p>Every test here is the same shape, and the last line of each is the one that matters. A
     * caller bug — a null argument, a shaping function that names a target or forgets to return the
     * builder, a {@code HIGH} tenant with no mapper wired — must be refused <em>and</em> leave the
     * tenant's budget untouched. Asserting only the refusal would pass whether the probe was spent
     * or not, so each test follows the bad call with a good one and asserts it still goes through.
     * With a budget of one, a spent probe turns that second call into an
     * {@link IdentifierProbeThrottledException}.
     *
     * <p>Why it is worth pinning: the budget exists to make probes scarce, and a caller bug is not a
     * probe. A hot call site with the wrong shaping function would otherwise drain the window of a
     * tenant nobody was attacking, and the tenant would experience a security control as an outage.
     * The invariant is an ordering, which is exactly the kind of property a later refactor folding
     * the preparation back inside the execution would undo without changing a single assertion about
     * results.
     */
    @Nested
    @DisplayName("a caller bug spends no probe")
    class LocalFailuresSpendNoBudget {

        /** One probe, so a single wrongly-spent budget is immediately visible. */
        private final IdentifierSearchLimiter oneProbe = new IdentifierSearchLimiter(1, Duration.ofMinutes(1));
        private final TenantScopedSearchExecutor<FakeSealedDocument> executor = executor(LAB, oneProbe, audit);

        @Test
        @DisplayName("a probe whose shaping function names a target")
        void aShapedTargetSpendsNoProbe() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> executor.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"),
                            shape -> shape.index(CLAIMS_INDEX)))
                    .withMessageContaining(PROBE_OPERATION);

            assertNothingHappened();
            assertTheBudgetSurvivedFor(LAB_NORMAL, executor);
        }

        @Test
        @DisplayName("a probe whose shaping function returns null")
        void aNullShapeSpendsNoProbe() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> executor.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"),
                            shape -> null));

            assertNothingHappened();
            assertTheBudgetSurvivedFor(LAB_NORMAL, executor);
        }

        @Test
        @DisplayName("a probe for a HIGH tenant with no secure mapper wired")
        void aMissingSecureMapperSpendsNoProbe() {
            TenantScopedSearchExecutor<FakeSealedDocument> noMapper = new TenantScopedSearchExecutor<>(
                    client, LAB, resolver, FakeSealedDocument.class, telemetry.observations(), null,
                    oneProbe, audit, () -> CALLER);

            assertThatIllegalStateException()
                    .isThrownBy(() -> noMapper.identifierSearch(LAB_HIGH, PROBE_OPERATION, noteQuery("x"), NO_SHAPE))
                    .withMessageContaining(HIGH_TENANT);

            assertNothingHappened();
            // A wiring error, which is the least deserving of all of them: it fails on every call,
            // so a budget spent here drains the window without a single probe ever reaching the
            // cluster. Checked through a correctly wired executor sharing the same limiter, because
            // the budget belongs to the tenant, not to the executor that mis-spent it.
            assertTheBudgetSurvivedFor(LAB_HIGH, executor(LAB, oneProbe, audit));
        }

        @Test
        @DisplayName("a probe with a null operation, query or shaping function")
        void aNullArgumentSpendsNoProbe() {
            assertThatNullPointerException().isThrownBy(
                    () -> executor.identifierSearch(LAB_NORMAL, null, noteQuery("x"), NO_SHAPE));
            assertThatNullPointerException().isThrownBy(
                    () -> executor.identifierSearch(LAB_NORMAL, PROBE_OPERATION, null, NO_SHAPE));
            assertThatNullPointerException().isThrownBy(
                    () -> executor.identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), null));

            assertNothingHappened();
            // Three bugs, and the budget of one is still whole.
            assertTheBudgetSurvivedFor(LAB_NORMAL, executor);
        }

        /** No cluster call, and nothing in the trail: as far as the system is concerned it never ran. */
        private void assertNothingHappened() {
            assertThat(transport.searchCount()).isZero();
            assertThat(audit.records()).isEmpty();
        }

        private void assertTheBudgetSurvivedFor(TenantRef tenant,
                                                TenantScopedSearchExecutor<FakeSealedDocument> executor) {
            assertThatCode(() -> executor.identifierSearch(tenant, PROBE_OPERATION, noteQuery("x"), NO_SHAPE))
                    .as("the tenant's single probe is still there to be spent")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("a transport failure becomes a search error naming the operation and nothing else")
        void aTransportFailureNamesOnlyTheOperation() {
            transport.failingWith(new IOException("connection reset by peer"));

            assertThatExceptionOfType(ErrorSearchingDataException.class)
                    .isThrownBy(() -> executor().search(LAB_NORMAL, OPERATION, noteQuery(QUERY_SENTINEL), NO_SHAPE))
                    .withMessageContaining(OPERATION)
                    .withCauseInstanceOf(IOException.class)
                    // The query text is the one thing that must never travel into a message that
                    // reaches a log or an error response.
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain(QUERY_SENTINEL));
        }

        @Test
        @DisplayName("a failed identifier probe still spent its budget and wrote no match record")
        void aFailedProbeIsNotAudited() {
            transport.failingWith(new IOException("connection reset by peer"));

            assertThatExceptionOfType(ErrorSearchingDataException.class).isThrownBy(
                    () -> executor().identifierSearch(LAB_NORMAL, PROBE_OPERATION, noteQuery("x"), NO_SHAPE));

            assertThat(audit.records()).isEmpty();
        }
    }

    @Nested
    @DisplayName("observability carries the domain and nothing sensitive")
    class Observability {

        @Test
        @DisplayName("search meters carry domain, operation, index and privacy")
        void searchMetersCarryTheFourTags() {
            executor().search(LAB_NORMAL, OPERATION, noteQuery("x"), NO_SHAPE);

            assertThat(telemetry.meters().get("opensearch.search.took")
                    .tags("domain", LAB.name(), "operation", OPERATION,
                            "index", NORMAL_INDEX, "privacy", PrivacyLevel.NORMAL.name())
                    .timer().count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the tenant id is on the span and on no meter")
        void theTenantIdStaysOffMeters() {
            executor().search(LAB_NORMAL, OPERATION, noteQuery("x"), NO_SHAPE);

            assertThat(telemetry.keyValuesOf(OpenSearchObservations.SEARCH_OBSERVATION))
                    .anySatisfy(keyValue -> {
                        assertThat(keyValue.getKey()).isEqualTo("tenant.id");
                        assertThat(keyValue.getValue()).isEqualTo(NORMAL_TENANT);
                    });
            // A per-tenant meter tag is how a shared cluster grows a time series per tenant per
            // meter; on a span it is free and still queryable.
            assertThat(telemetry.everyTagValue()).doesNotContain(NORMAL_TENANT);
        }

        @Test
        @DisplayName("nothing derived from the query or the document reaches a meter, a span, a log line or an exception")
        void noSentinelEscapesIntoTelemetry() throws Exception {
            // Everything that can produce output, in one run: a HIGH search that matches and opens
            // a sealed value, a probe that is refused, and a round trip that fails outright.
            TenantScopedSearchExecutor<FakeSealedDocument> executor = new TenantScopedSearchExecutor<>(
                    client, LAB, resolver, FakeSealedDocument.class, telemetry.observations(), secureMapper,
                    new IdentifierSearchLimiter(1, Duration.ofMinutes(1)),
                    new LoggingIdentifierSearchAudit(), () -> CALLER);
            FakeSealedDocument sealed = sealedDocument("doc-1", VALUE_SENTINEL);
            transport.alwaysAnswering(request -> SearchingOpenSearchTransport.hits(
                    SearchingOpenSearchTransport.hit("doc-1", seal(sealed), 2.0, List.of(BranchNames.IDENTIFIER))));

            List<String> errorMessages = new ArrayList<>();
            SearchResults<FakeSealedDocument> matched =
                    executor.identifierSearch(LAB_HIGH, PROBE_OPERATION, noteQuery(QUERY_SENTINEL), NO_SHAPE);
            errorMessages.add(messageOf(() -> executor.identifierSearch(
                    LAB_HIGH, PROBE_OPERATION, noteQuery(QUERY_SENTINEL), NO_SHAPE)));
            transport.failingWith(new IOException("connection reset by peer"));
            errorMessages.add(messageOf(() -> executor.search(
                    LAB_HIGH, OPERATION, noteQuery(QUERY_SENTINEL), NO_SHAPE)));

            // The value really was opened, so the run had something to leak.
            assertThat(matched.documents().getFirst().subjectName()).isEqualTo(VALUE_SENTINEL);
            List<String> surface = new ArrayList<>();
            surface.addAll(telemetry.everyTagValue());
            surface.addAll(telemetry.everyKeyValue());
            surface.addAll(telemetry.meterNames());
            surface.addAll(RecordedLogs.messages());
            surface.addAll(errorMessages);
            assertThat(surface).isNotEmpty();
            assertThat(surface).allSatisfy(published -> assertThat(published)
                    .doesNotContainIgnoringCase(QUERY_SENTINEL)
                    .doesNotContainIgnoringCase(VALUE_SENTINEL)
                    // The canonical form and the hashes are derived from the same value and are
                    // just as disclosive; the sentinel's own words catch both spellings.
                    .doesNotContainIgnoringCase("ziphius")
                    .doesNotContainIgnoringCase("cavirostris")
                    .doesNotContainIgnoringCase("nyx")
                    .doesNotContainIgnoringCase("pellucida"));
        }
    }

    @Nested
    @DisplayName("the executor cannot be bypassed by inheritance")
    class NoInheritanceHook {

        @Test
        @DisplayName("the class is final")
        void theClassIsFinal() {
            // An abstract repository an application subclasses is the obvious alternative design,
            // and it fails for one reason: a subclass can override the method that applies the
            // tenant filter. A cross-tenant read is the worst outcome this codebase has.
            assertThat(Modifier.isFinal(TenantScopedSearchExecutor.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("it declares no protected or abstract member for a subclass to reach")
        void thereIsNoProtectedHook() {
            assertThat(declaredMembers())
                    .allSatisfy(member -> assertThat(Modifier.isProtected(member.getModifiers()))
                            .as("%s is protected", member.getName()).isFalse());
            assertThat(TenantScopedSearchExecutor.class.getDeclaredMethods())
                    .filteredOn(method -> !method.isSynthetic())
                    .allSatisfy(method -> assertThat(Modifier.isAbstract(method.getModifiers())).isFalse());
        }

        @Test
        @DisplayName("it hands out neither the client nor the resolver, so there is no way round it")
        void theCollaboratorsAreNotReachable() {
            assertThat(TenantScopedSearchExecutor.class.getDeclaredMethods())
                    .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                    .extracting(java.lang.reflect.Method::getReturnType)
                    .doesNotContain(OpenSearchClient.class, TenantIndexResolver.class,
                            SecureDocumentMapper.class, IdentifierSearchLimiter.class);
            assertThat(TenantScopedSearchExecutor.class.getDeclaredFields())
                    .allSatisfy(field -> assertThat(Modifier.isPrivate(field.getModifiers())).isTrue());
        }

        private List<Member> declaredMembers() {
            List<Member> members = new ArrayList<>();
            members.addAll(Arrays.asList(TenantScopedSearchExecutor.class.getDeclaredMethods()));
            members.addAll(Arrays.asList(TenantScopedSearchExecutor.class.getDeclaredFields()));
            members.addAll(Arrays.asList(TenantScopedSearchExecutor.class.getDeclaredConstructors()));
            return members;
        }
    }

    // ---------------------------------------------------------------- fixtures

    private TenantScopedSearchExecutor<FakeSealedDocument> executor() {
        return executor(LAB, limiter, audit);
    }

    /** With the real spec-driven secure mapper wired, for the {@code HIGH} half. */
    private TenantScopedSearchExecutor<FakeSealedDocument> highExecutor() {
        return new TenantScopedSearchExecutor<>(client, LAB, resolver, FakeSealedDocument.class,
                telemetry.observations(), secureMapper, limiter, audit, () -> CALLER);
    }

    private TenantScopedSearchExecutor<FakeSealedDocument> executor(
            SearchDomain domain, IdentifierSearchLimiter limiter, IdentifierSearchAudit audit) {
        return new TenantScopedSearchExecutor<>(client, domain, resolver, FakeSealedDocument.class,
                telemetry.observations(), secureMapper, limiter, audit, () -> CALLER);
    }

    /** The application's own query, over the application's own field: the toolkit builds none. */
    private static Query noteQuery(String text) {
        return Query.of(q -> q.term(t -> t.field("note").value(v -> v.stringValue(text))));
    }

    private static FakeSealedDocument document(String documentId) {
        return new FakeSealedDocument(NORMAL_TENANT, documentId, "note-" + documentId, "Ada Plaintext", "handle");
    }

    private static FakeSealedDocument sealedDocument(String documentId, String subjectName) {
        return new FakeSealedDocument(HIGH_TENANT, documentId, "note-" + documentId, subjectName, "handle");
    }

    /** The document as the cluster holds it: envelope components and hashes, nothing readable. */
    private Map<String, Object> seal(FakeSealedDocument document) {
        return secureMapper.toDocument(LAB_HIGH, document).fields();
    }

    private static String messageOf(Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected the call to fail");
        } catch (RuntimeException expected) {
            return String.valueOf(expected.getMessage());
        }
    }

    private static InMemoryTenantCatalog catalog() {
        return new InMemoryTenantCatalog(DOMAINS, domain -> 4, List.of(
                pooled(LAB_NORMAL, PrivacyLevel.NORMAL, NORMAL_INDEX),
                pooled(LAB_HIGH, PrivacyLevel.HIGH, HIGH_INDEX),
                dedicated(LAB_DEDICATED, PrivacyLevel.NORMAL, DEDICATED_INDEX),
                pooled(CLAIMS_NORMAL, PrivacyLevel.NORMAL, CLAIMS_INDEX)));
    }

    private static Placement pooled(TenantRef tenant, PrivacyLevel level, String pool) {
        return new Placement(tenant, Tier.POOLED, pool + "-write", List.of(pool),
                true, MigrationState.STABLE, level);
    }

    private static Placement dedicated(TenantRef tenant, PrivacyLevel level, String index) {
        return new Placement(tenant, Tier.DEDICATED, index + "-write", List.of(index),
                false, MigrationState.STABLE, level);
    }
}
