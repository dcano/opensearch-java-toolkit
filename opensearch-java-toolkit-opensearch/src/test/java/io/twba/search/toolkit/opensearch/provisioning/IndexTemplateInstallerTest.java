package io.twba.search.toolkit.opensearch.provisioning;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.DynamicMapping;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


import static io.twba.search.toolkit.PrivacyLevel.HIGH;
import static io.twba.search.toolkit.PrivacyLevel.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The secure family's promise — "no readable sensitive value lives in this index" — is not kept by
 * the adapter. It is kept by this template: a mapping that is {@code strict} and that defines no
 * property for the plaintext field, so the cluster itself rejects a document carrying one. Every
 * assertion below is about a way that promise can be lost without anything failing.
 *
 * <p>The losses are all silent. A pattern that does not match the family means the template never
 * applies and the first write creates a dynamically-mapped index instead — dynamic, therefore
 * accepting a readable name. A {@code dynamic} setting the application was allowed to relax means
 * the same thing with the template applied. A priority that ties means OpenSearch rejects one of the
 * two templates, and whichever survives maps the other family. A hash array mapped as {@code text}
 * gets analyzed, and equality on a base64 hash stops working with no error anywhere.
 *
 * <p>The domain and the fields here are deliberately not the kata's: the toolkit must not know an
 * application's vocabulary, and a fixture that used it would make that impossible to see.
 */
class IndexTemplateInstallerTest {

    private static final SearchDomain CASE_FILES = new SearchDomain("case-files");

    private static final SecureFieldSpec SUBJECT = new SecureFieldSpec("subjectName");
    private static final SecureFieldSpec HANDLE = new SecureFieldSpec("handleName");

    /** The application's own non-sensitive fields, including the one field the toolkit enforces. */
    private static final List<String> SHARED_FIELDS =
            List.of(TenantDocument.TENANT_ID_FIELD, "openedOn", "caseStatus");

    private static final int SHARDS = 3;
    private static final int REPLICAS = 1;

    private final ProvisioningOpenSearchTransport transport = new ProvisioningOpenSearchTransport();
    private final OpenSearchClient client = new OpenSearchClient(transport);

    // ------------------------------------------------------------------ the name, pattern, priority

    @Nested
    @DisplayName("the name, the pattern and the priority are the toolkit's, derived from the domain")
    class NamePatternAndPriority {

        @Test
        @DisplayName("the NORMAL family installs <domain>-template over <domain>-* at priority 100")
        void normalTemplateIsNamedAndPatternedForItsFamily() throws IOException {
            install(NORMAL);

            PutIndexTemplateRequest request = transport.onlyTemplate();
            assertThat(request.name()).isEqualTo("case-files-template");
            assertThat(request.indexPatterns()).containsExactly("case-files-*");
            assertThat(request.priority()).isEqualTo(IndexTemplateInstaller.BASE_PRIORITY).isEqualTo(100);
        }

        @Test
        @DisplayName("the HIGH family installs <domain>-secure-template over <domain>-secure-* at priority 200")
        void secureTemplateIsNamedAndPatternedForItsFamily() throws IOException {
            install(HIGH);

            PutIndexTemplateRequest request = transport.onlyTemplate();
            assertThat(request.name()).isEqualTo("case-files-secure-template");
            assertThat(request.indexPatterns()).containsExactly("case-files-secure-*");
            assertThat(request.priority()).isEqualTo(IndexTemplateInstaller.SECURE_PRIORITY).isEqualTo(200);
        }

        @Test
        @DisplayName("the NORMAL pattern also claims the secure family, which is what the priority is for")
        void theBasePatternOverlapsTheSecureFamilyOnPurpose() throws IOException {
            install(NORMAL);

            // If this ever stopped being true the priority arithmetic would be dead code, and a
            // reader would "simplify" it away. The overlap is the reason it exists.
            assertThat(matchesGlob("case-files-secure-pool-0-000001", transport.onlyTemplate().indexPatterns().getFirst()))
                    .as("the base pattern matching a secure index")
                    .isTrue();
        }

        @Test
        @DisplayName("the secure priority is strictly greater than the base priority")
        void theSecurePriorityOutranksTheBaseOne() {
            // Both patterns match a secure index name and OpenSearch applies exactly one composable
            // template. Equal, the cluster rejects the pair as a conflict; inverted, a secure index
            // is created with the plaintext mapping — which defines the readable field.
            assertThat(IndexTemplateInstaller.priority(HIGH))
                    .isGreaterThan(IndexTemplateInstaller.priority(NORMAL));
        }

        @Test
        @DisplayName("no two privacy levels share a priority, so a tie is never resolved here")
        void everyLevelHasItsOwnPriority() {
            // The requirement is that a tie fails loudly at the cluster rather than being decided by
            // the toolkit. The toolkit's side of that bargain is simply never to emit two templates
            // at the same priority — a third level added with a copy-pasted constant breaks this.
            List<Integer> priorities = Arrays.stream(PrivacyLevel.values())
                    .map(IndexTemplateInstaller::priority)
                    .toList();

            assertThat(priorities).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a null client, domain, level or mapping is refused at construction")
        void nullsAreRefused() {
            DomainMapping mapping = mapping(SUBJECT, HANDLE);

            assertThatNullPointerException()
                    .isThrownBy(() -> new IndexTemplateInstaller(null, CASE_FILES, NORMAL, mapping));
            assertThatNullPointerException()
                    .isThrownBy(() -> new IndexTemplateInstaller(client, null, NORMAL, mapping));
            assertThatNullPointerException()
                    .isThrownBy(() -> new IndexTemplateInstaller(client, CASE_FILES, null, mapping));
            assertThatNullPointerException()
                    .isThrownBy(() -> new IndexTemplateInstaller(client, CASE_FILES, NORMAL, null));
        }
    }

    // ------------------------------------------------------------------ the secure mapping

    @Nested
    @DisplayName("the secure mapping defines every written component and nothing else")
    class SecureMapping {

        @Test
        @DisplayName("exactly the six derived properties per spec, beside the shared ones")
        void exactlySixDerivedPropertiesPerSpec() throws IOException {
            install(HIGH);

            List<String> expected = new ArrayList<>(SHARED_FIELDS);
            expected.addAll(SUBJECT.derivedFields());
            expected.addAll(HANDLE.derivedFields());

            // "Exactly": a seventh property is a field nothing writes, and a fifth is a component
            // the mapper writes that strict mapping will reject at the cluster.
            assertThat(properties(HIGH).keySet()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(expected).hasSize(SHARED_FIELDS.size() + 12);
        }

        @Test
        @DisplayName("the hash arrays are keyword, so they are matched byte for byte and never analyzed")
        void hashArraysAreUnanalyzedKeywords() throws IOException {
            install(HIGH);
            Map<String, Property> properties = properties(HIGH);

            for (String hashed : List.of(SUBJECT.tokensField(), SUBJECT.prefixesField(),
                    HANDLE.tokensField(), HANDLE.prefixesField())) {
                Property property = properties.get(hashed);
                assertThat(property).as("property for %s", hashed).isNotNull();
                // Anything analyzed would stem, fold or split a base64 hash, and equality — the only
                // operation these fields exist for — would stop matching with no error anywhere.
                assertThat(property.isKeyword()).as("%s mapped as keyword", hashed).isTrue();
                assertThat(property.keyword().index()).as("%s left searchable", hashed).isNotEqualTo(false);
                assertThat(property.keyword().normalizer()).as("%s left unnormalized", hashed).isNull();
            }
        }

        @Test
        @DisplayName("the four envelope properties are neither indexed nor doc-valued")
        void envelopePropertiesAreCarriedNotSearched() throws IOException {
            install(HIGH);
            Map<String, Property> properties = properties(HIGH);

            List<String> carried = new ArrayList<>(SUBJECT.envelopeFields());
            carried.addAll(HANDLE.envelopeFields());
            assertThat(carried).hasSize(8);

            for (String field : carried) {
                Property property = properties.get(field);
                assertThat(property).as("property for %s", field).isNotNull();
                assertThat(property.isKeyword()).as("%s mapped as keyword", field).isTrue();
                // Indexing ciphertext buys nothing and costs a per-document term; doc values on it
                // would let an aggregation count distinct sealed values, which is a small leak.
                assertThat(property.keyword().index()).as("%s indexed", field).isFalse();
                assertThat(property.keyword().docValues()).as("%s doc-valued", field).isFalse();
            }
        }

        @Test
        @DisplayName("the secure mapping defines no property for any declared sensitive field")
        void theSecureMappingOmitsThePlaintextField() throws IOException {
            install(HIGH);

            assertThat(properties(HIGH))
                    .doesNotContainKeys(SUBJECT.fieldName(), HANDLE.fieldName());
        }

        @Test
        @DisplayName("the NORMAL mapping does define them, with the application's own property")
        void theBaseMappingKeepsThePlaintextField() throws IOException {
            install(NORMAL);
            Map<String, Property> properties = properties(NORMAL);

            // The omission above is only meaningful if the same contribution produces the field on
            // the other family: otherwise a mapping that dropped every sensitive field would pass.
            assertThat(properties).containsKeys(SUBJECT.fieldName(), HANDLE.fieldName());
            assertThat(properties.get(SUBJECT.fieldName()).isSearchAsYouType())
                    .as("the application's own plaintext property survived")
                    .isTrue();
            // And none of the sealed components: a NORMAL index has nothing to put in them.
            assertThat(properties).doesNotContainKeys(SUBJECT.derivedFields().toArray(String[]::new));
        }

        @Test
        @DisplayName("a domain with no sensitive fields installs the shared properties alone")
        void aPlaintextOnlyDomainMapsOnlyItsSharedFields() throws IOException {
            DomainMapping plaintextOnly =
                    DomainMapping.plaintextOnly(IndexTemplateInstallerTest::settings,
                            IndexTemplateInstallerTest::sharedProperties);
            new IndexTemplateInstaller(client, CASE_FILES, HIGH, plaintextOnly).install();

            assertThat(properties(HIGH).keySet()).containsExactlyInAnyOrderElementsOf(SHARED_FIELDS);
        }
    }

    // ------------------------------------------------------------------ strictness

    @Nested
    @DisplayName("dynamic: strict is the installer's, not the application's")
    class Strictness {

        @ParameterizedTest(name = "{0} family")
        @EnumSource(PrivacyLevel.class)
        @DisplayName("both families are installed strict")
        void bothFamiliesAreStrict(PrivacyLevel level) throws IOException {
            install(level);

            assertThat(mappings(level).dynamic()).isEqualTo(DynamicMapping.Strict);
        }

        @ParameterizedTest(name = "a contribution asking for dynamic: {0}")
        @EnumSource(value = DynamicMapping.class, names = {"True", "False", "StrictAllowTemplates"})
        @DisplayName("a contribution that relaxes dynamic is overwritten, not obeyed")
        void anApplicationCannotRelaxStrictness(DynamicMapping relaxed) throws IOException {
            // This is the mechanism, and the only mechanism, by which the secure family rejects a
            // document carrying a readable sensitive value. An application that could set
            // dynamic: true here would turn the guarantee into a promise about the adapter.
            DomainMapping relaxing = new DomainMapping(
                    IndexTemplateInstallerTest::settings,
                    m -> sharedProperties(m).dynamic(relaxed),
                    List.of(sensitive(SUBJECT)));
            new IndexTemplateInstaller(client, CASE_FILES, HIGH, relaxing).install();

            assertThat(mappings(HIGH).dynamic()).isEqualTo(DynamicMapping.Strict);
        }
    }

    // ------------------------------------------------------------------ settings

    @Nested
    @DisplayName("settings are the application's and identical on both families")
    class Settings {

        @Test
        @DisplayName("the contributed settings reach both templates unchanged")
        void bothFamiliesCarryTheSameSettings() throws IOException {
            install(NORMAL);
            IndexSettings base = transport.onlyTemplate().template().settings();

            ProvisioningOpenSearchTransport secureTransport = new ProvisioningOpenSearchTransport();
            new IndexTemplateInstaller(new OpenSearchClient(secureTransport), CASE_FILES, HIGH,
                    mapping(SUBJECT, HANDLE)).install();
            IndexSettings secure = secureTransport.onlyTemplate().template().settings();

            // A tenant's free-text search must behave the same whatever its privacy posture; two
            // shard counts would make relevance scores differ between two tenants of one domain.
            assertThat(base.numberOfShards()).isEqualTo(SHARDS);
            assertThat(base.numberOfReplicas()).isEqualTo(REPLICAS);
            assertThat(secure.numberOfShards()).isEqualTo(base.numberOfShards());
            assertThat(secure.numberOfReplicas()).isEqualTo(base.numberOfReplicas());
        }
    }

    // ------------------------------------------------------------------ idempotency

    @Nested
    @DisplayName("installing twice leaves the cluster in the same state")
    class Idempotency {

        @Test
        @DisplayName("a second install sends the same full-state PUT and reads nothing first")
        void reinstallingSendsTheSameRequest() throws IOException {
            install(HIGH);
            install(HIGH);

            List<PutIndexTemplateRequest> requests = transport.templateRequests();
            assertThat(requests).hasSize(2);
            // A full-state PUT is idempotent by construction. A read-then-compare would have to
            // decide what "the same" means across the cluster's own normalization, and the cost of
            // getting that wrong is skipping an install that was needed.
            assertThat(requests.get(1).name()).isEqualTo(requests.get(0).name());
            assertThat(requests.get(1).indexPatterns()).isEqualTo(requests.get(0).indexPatterns());
            assertThat(requests.get(1).priority()).isEqualTo(requests.get(0).priority());
            assertThat(requests.get(1).template().mappings().properties().keySet())
                    .isEqualTo(requests.get(0).template().mappings().properties().keySet());
            assertThat(transport.probedIndices()).as("state read before writing").isEmpty();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void install(PrivacyLevel level) throws IOException {
        new IndexTemplateInstaller(client, CASE_FILES, level, mapping(SUBJECT, HANDLE)).install();
    }

    /** The mapping of the template most recently installed; every test here installs exactly one. */
    private TypeMapping mappings(PrivacyLevel level) {
        List<PutIndexTemplateRequest> requests = transport.templateRequests();
        assertThat(requests).as("templates installed for %s", level).isNotEmpty();
        PutIndexTemplateRequest last = requests.getLast();
        assertThat(last.priority())
                .as("the installed template belongs to the %s family", level)
                .isEqualTo(IndexTemplateInstaller.priority(level));
        return last.template().mappings();
    }

    private Map<String, Property> properties(PrivacyLevel level) {
        return mappings(level).properties();
    }

    private static DomainMapping mapping(SecureFieldSpec... specs) {
        return new DomainMapping(
                IndexTemplateInstallerTest::settings,
                IndexTemplateInstallerTest::sharedProperties,
                Arrays.stream(specs).map(IndexTemplateInstallerTest::sensitive).toList());
    }

    /** How this application chooses to store the plaintext half — a relevance decision, not ours. */
    private static SensitiveFieldMapping sensitive(SecureFieldSpec spec) {
        return new SensitiveFieldMapping(spec, p -> p.searchAsYouType(s -> s.maxShingleSize(3)));
    }

    private static IndexSettings.Builder settings(IndexSettings.Builder builder) {
        return builder.numberOfShards(SHARDS).numberOfReplicas(REPLICAS);
    }

    private static TypeMapping.Builder sharedProperties(TypeMapping.Builder builder) {
        return builder
                .properties(TenantDocument.TENANT_ID_FIELD, p -> p.keyword(k -> k))
                .properties("openedOn", p -> p.date(d -> d))
                .properties("caseStatus", p -> p.keyword(k -> k));
    }

    /** OpenSearch index patterns are globs, and only {@code *} is meaningful in a family prefix. */
    private static boolean matchesGlob(String name, String glob) {
        String regex = Arrays.stream(glob.split("\\*", -1))
                .map(java.util.regex.Pattern::quote)
                .reduce((left, right) -> left + ".*" + right)
                .orElseThrow();
        return name.matches(regex);
    }
}
