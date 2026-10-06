package io.twba.search.toolkit.opensearch.provisioning;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.BlindIndexer;
import io.twba.search.toolkit.crypto.KeyUnavailableException;
import io.twba.search.toolkit.crypto.SealedFieldCipher;
import io.twba.search.toolkit.crypto.TenantKeyProvider;
import io.twba.search.toolkit.opensearch.dp.SecureDocument;
import io.twba.search.toolkit.opensearch.dp.SensitiveFieldQueries;
import io.twba.search.toolkit.opensearch.dp.SpecDrivenSecureDocumentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.indices.IndexSettings;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim the whole spec-driven design exists to make, checked across the three classes that make
 * it: <em>rename a declared sensitive field and the template, the document and the query all move,
 * with no edit to the installer, the mapper or the query builder.</em>
 *
 * <p>The implementation this generalizes wrote those six names out by hand in three places and its
 * own docs admitted they agreed "by discipline". The failure that produces is the worst one
 * available in search: a query that probes a field nothing fills matches nothing and reports
 * success, which is indistinguishable from a tenant that genuinely has no such record.
 *
 * <p>So the assertions below are deliberately not "the template contains {@code xBidxTokens}". They
 * are set relations between what the template <em>defines</em>, what the mapper <em>writes</em> and
 * what the query <em>probes</em>, evaluated for several unrelated field names. Any one of the three
 * reverting to a literal breaks the relation for every name but one.
 */
class SpecDrivenNamesTest {

    private static final SearchDomain CASE_FILES = new SearchDomain("case-files");

    private static final String TENANT_ID = "tenant-guarded";
    private static final TenantRef TENANT = TenantRef.of(CASE_FILES, TENANT_ID);

    /** Obviously fake, and carrying the two things text handling gets wrong: diacritics and a hyphen. */
    private static final String VALUE = "Marisol Quintanilla-Berra";

    private static final List<String> SHARED_FIELDS = List.of(TenantDocument.TENANT_ID_FIELD, "note");

    private final TenantKeyProvider keys = new FixedTenantKey(TENANT_ID);
    private final BlindIndexer blindIndexer = new BlindIndexer(keys);
    private final SealedFieldCipher cipher = new SealedFieldCipher(keys);

    @ParameterizedTest(name = "a field declared as \"{0}\"")
    @ValueSource(strings = {"x", "y", "subjectName", "caseSubject"})
    @DisplayName("template, document and query all name the components derived from the declared field")
    void allThreeConsumersFollowTheDeclaredName(String fieldName) throws IOException {
        SecureFieldSpec spec = new SecureFieldSpec(fieldName);

        Set<String> defined = templateProperties(spec);
        Set<String> written = documentKeys(spec);
        Set<String> probed = probedFields(spec);

        // 1. The template defines every component the mapper writes. A property the mapper writes
        //    and the template omits is rejected outright by the strict mapping: the whole write
        //    fails at the cluster, per document, at runtime.
        assertThat(defined).as("template properties for '%s'", fieldName).containsAll(written);

        // 2. The mapper writes every field the query probes, and the template defines them. A probe
        //    on a field nothing fills matches nothing and reports success.
        assertThat(written).as("document keys for '%s'", fieldName).containsAll(probed);
        assertThat(defined).containsAll(probed);

        // 3. And the names are the derived ones, not the declared one: no readable value anywhere.
        assertThat(defined).doesNotContain(fieldName);
        assertThat(written).doesNotContain(fieldName);
        assertThat(probed).containsExactlyInAnyOrderElementsOf(spec.hashedFields());
    }

    @Test
    @DisplayName("renaming the field changes every derived name and nothing else")
    void renamingTheFieldMovesAllThreeAndLeavesTheRestAlone() throws IOException {
        SecureFieldSpec before = new SecureFieldSpec("x");
        SecureFieldSpec after = new SecureFieldSpec("y");

        Set<String> definedBefore = templateProperties(before);
        Set<String> definedAfter = templateProperties(after);

        // The shared half is untouched by the rename — so the test cannot pass by the mapping
        // having become empty or wholesale different.
        assertThat(definedBefore).containsAll(SHARED_FIELDS);
        assertThat(definedAfter).containsAll(SHARED_FIELDS);
        // And the sensitive half moved completely: no name survives the rename.
        assertThat(definedBefore).containsAll(before.derivedFields());
        assertThat(definedAfter).containsAll(after.derivedFields());
        assertThat(definedAfter).doesNotContainAnyElementsOf(before.derivedFields());
        assertThat(documentKeys(after)).doesNotContainAnyElementsOf(before.derivedFields());
        assertThat(probedFields(after)).doesNotContainAnyElementsOf(before.derivedFields());
    }

    @Test
    @DisplayName("the query probes only the two searchable components, never an envelope one")
    void theQueryNeverProbesACarriedField() {
        SecureFieldSpec spec = new SecureFieldSpec("x");

        // The envelope properties are mapped index:false. A query that named one would match
        // nothing at best and raise at worst, and either way the identifier branch would be dead.
        assertThat(probedFields(spec)).doesNotContainAnyElementsOf(spec.envelopeFields());
    }

    // ------------------------------------------------------------------ the three consumers

    /** What the secure template defines for a domain declaring exactly this one sensitive field. */
    private Set<String> templateProperties(SecureFieldSpec spec) throws IOException {
        ProvisioningOpenSearchTransport transport = new ProvisioningOpenSearchTransport();
        DomainMapping mapping = new DomainMapping(
                SpecDrivenNamesTest::settings,
                SpecDrivenNamesTest::sharedProperties,
                List.of(new SensitiveFieldMapping(spec, p -> p.searchAsYouType(s -> s.maxShingleSize(3)))));
        new IndexTemplateInstaller(new OpenSearchClient(transport), CASE_FILES, PrivacyLevel.HIGH, mapping)
                .install();

        return transport.onlyTemplate().template().mappings().properties().keySet();
    }

    /** What the secure mapper writes for a document whose sensitive field holds a value. */
    private Set<String> documentKeys(SecureFieldSpec spec) {
        SpecDrivenSecureDocumentMapper<Subject> mapper = new SpecDrivenSecureDocumentMapper<>(
                List.of(spec),
                subject -> {
                    Map<String, Object> plain = new LinkedHashMap<>();
                    plain.put(TenantDocument.TENANT_ID_FIELD, subject.tenantId());
                    plain.put("note", subject.note());
                    plain.put(spec.fieldName(), subject.value());
                    return plain;
                },
                (fields, opened) -> new Subject(
                        String.valueOf(fields.get(TenantDocument.TENANT_ID_FIELD)),
                        "doc-0001",
                        String.valueOf(fields.get("note")),
                        opened.get(spec.fieldName())),
                blindIndexer,
                cipher);

        SecureDocument document = (SecureDocument) mapper.toDocument(
                TENANT, new Subject(TENANT_ID, "doc-0001", "an ordinary field", VALUE));
        return document.fields().keySet();
    }

    /** Which fields the identifier branch names, read out of the JSON that would go to the cluster. */
    private Set<String> probedFields(SecureFieldSpec spec) {
        SensitiveFieldQueries queries = new SensitiveFieldQueries(
                spec, List.of(spec.fieldName()), blindIndexer);
        Query branch = queries.identifierBranch(PrivacyLevel.HIGH, TENANT, VALUE).orElseThrow();
        String json = branch.toJsonString();

        // The full derived vocabulary is the search space; whatever of it appears in the request is
        // what the query probes. Nothing here is spelled out, so a rename moves it too.
        return spec.derivedFields().stream()
                .filter(field -> json.contains("\"" + field + "\""))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ------------------------------------------------------------------ fixtures

    private record Subject(String tenantId, String documentId, String note, String value)
            implements TenantDocument {
    }

    private static IndexSettings.Builder settings(IndexSettings.Builder builder) {
        return builder.numberOfShards(1).numberOfReplicas(0);
    }

    private static TypeMapping.Builder sharedProperties(TypeMapping.Builder builder) {
        return builder
                .properties(TenantDocument.TENANT_ID_FIELD, p -> p.keyword(k -> k))
                .properties("note", p -> p.text(t -> t));
    }

    /**
     * One tenant, one deterministic key pair derived from its id. Worthless cryptographically and
     * exactly right here: nothing in this file asserts anything about key strength, only about
     * which <em>names</em> the derived values are stored and searched under. Not a real key.
     */
    private record FixedTenantKey(String tenantId) implements TenantKeyProvider {

        @Override
        public SecretKey kek(String tenant) {
            return keyFor(tenant, "kek");
        }

        @Override
        public SecretKey hmacKey(String tenant) {
            return keyFor(tenant, "hmac");
        }

        private SecretKey keyFor(String tenant, String purpose) {
            if (!tenantId.equals(tenant)) {
                throw new KeyUnavailableException(tenant, "no test key is configured for this tenant");
            }
            byte[] seed = (tenant + ':' + purpose).getBytes(StandardCharsets.UTF_8);
            byte[] material = new byte[32];
            for (int i = 0; i < material.length; i++) {
                material[i] = seed[i % seed.length];
            }
            return new SecretKeySpec(material, "AES");
        }
    }
}
