package io.twba.search.toolkit.opensearch.provisioning;

import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.IndexSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.twba.search.toolkit.PrivacyLevel.HIGH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The application's whole contribution to its own templates, in one value. What it is allowed to
 * decide, what it is not, and what it is refused for getting wrong at declaration time rather than
 * at the cluster.
 */
class DomainMappingTest {

    private static final SecureFieldSpec SUBJECT = new SecureFieldSpec("subjectName");
    private static final SecureFieldSpec HANDLE = new SecureFieldSpec("handleName");

    @Nested
    @DisplayName("a sensitive field cannot be declared twice")
    class Duplicates {

        @Test
        @DisplayName("the same field name twice is refused, naming the field")
        void aRepeatedFieldNameIsRefused() {
            // Two declarations of one field means two plaintext property contributions racing for
            // one name on the NORMAL family — last one wins, silently, and which one is last is an
            // ordering detail of the application's own configuration.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DomainMapping(
                            DomainMappingTest::settings,
                            DomainMappingTest::sharedProperties,
                            List.of(sensitive(SUBJECT), sensitive(new SecureFieldSpec("subjectName")))))
                    .withMessageContaining("subjectName");
        }

        @Test
        @DisplayName("two different fields are accepted, in the order they were declared")
        void distinctFieldsAreKeptInOrder() {
            DomainMapping mapping = mapping(SUBJECT, HANDLE);

            assertThat(mapping.specs()).containsExactly(SUBJECT, HANDLE);
        }
    }

    @Nested
    @DisplayName("the declared list is the mapping's own")
    class Immutability {

        @Test
        @DisplayName("mutating the list afterwards does not change the mapping")
        void theListIsCopied() {
            List<SensitiveFieldMapping> declared = new ArrayList<>(List.of(sensitive(SUBJECT)));
            DomainMapping mapping = new DomainMapping(
                    DomainMappingTest::settings, DomainMappingTest::sharedProperties, declared);

            declared.add(sensitive(HANDLE));

            // A caller that kept its builder list could otherwise add a sensitive field after the
            // templates were installed: a field the mapper seals into components no mapping defines.
            assertThat(mapping.sensitiveFields()).hasSize(1);
            assertThat(mapping.specs()).containsExactly(SUBJECT);
        }

        @Test
        @DisplayName("the exposed list cannot be added to")
        void theExposedListIsUnmodifiable() {
            DomainMapping mapping = mapping(SUBJECT);

            assertThatThrownBy(() -> mapping.sensitiveFields().add(sensitive(HANDLE)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("a domain may declare no sensitive fields at all")
    class PlaintextOnly {

        @Test
        @DisplayName("plaintextOnly declares an empty field list and no specs")
        void plaintextOnlyHasNoFields() {
            DomainMapping mapping = DomainMapping.plaintextOnly(
                    DomainMappingTest::settings, DomainMappingTest::sharedProperties);

            assertThat(mapping.sensitiveFields()).isEmpty();
            assertThat(mapping.specs()).isEmpty();
        }
    }

    @Nested
    @DisplayName("nulls are refused rather than carried into a template")
    class Nulls {

        @Test
        @DisplayName("a null settings, shared-properties or field list is refused")
        void nullsAreRefused() {
            assertThatNullPointerException().isThrownBy(() -> new DomainMapping(
                    null, DomainMappingTest::sharedProperties, List.of()));
            assertThatNullPointerException().isThrownBy(() -> new DomainMapping(
                    DomainMappingTest::settings, null, List.of()));
            assertThatNullPointerException().isThrownBy(() -> new DomainMapping(
                    DomainMappingTest::settings, DomainMappingTest::sharedProperties, null));
        }

        @Test
        @DisplayName("a SensitiveFieldMapping with no spec or no plaintext property is refused")
        void aSensitiveFieldMappingNeedsBothHalves() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SensitiveFieldMapping(null, p -> p.keyword(k -> k)));
            assertThatNullPointerException()
                    .isThrownBy(() -> new SensitiveFieldMapping(SUBJECT, null));
        }

        @Test
        @DisplayName("a field mapping's name is its spec's name, so the two cannot disagree")
        void theFieldNameComesFromTheSpec() {
            assertThat(sensitive(SUBJECT).fieldName()).isEqualTo(SUBJECT.fieldName());
        }
    }

    @Nested
    @DisplayName("specs whose derived names overlap are refused where the fields were declared")
    class OverlappingDerivedNames {

        @Test
        @DisplayName("two distinct fields deriving one storage name are refused, naming that name")
        void aDerivedNameCollisionIsRefused() {
            // Distinct declared names, one storage field: "value" derives valueDekIv for its DEK's
            // IV, and "valueDek" derives valueDekIv for its own. Nothing downstream reports it —
            // the installer would map one property fewer than it was given and look successful, and
            // the mapper would only notice at the first write — so it has to be caught here.
            SecureFieldSpec value = new SecureFieldSpec("value");
            SecureFieldSpec valueDek = new SecureFieldSpec("valueDek");
            assertThat(value.derivedFields()).contains("valueDekIv");
            assertThat(valueDek.derivedFields()).contains("valueDekIv");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> mapping(value, valueDek))
                    .withMessageContaining("valueDekIv");
        }

        @Test
        @DisplayName("a declared field equal to another's derived name is refused, naming the field")
        void aDeclaredNameCollidingWithADerivedOneIsRefused() {
            // "a" derives aCipher; declaring aCipher as a sensitive field of its own means the
            // NORMAL family maps it as plaintext while the HIGH family fills it with ciphertext.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> mapping(new SecureFieldSpec("a"), new SecureFieldSpec("aCipher")))
                    .withMessageContaining("aCipher");
        }

        @Test
        @DisplayName("a usable pair is still accepted, so the refusals are not simply blanket")
        void disjointFieldsAreStillAccepted() throws IOException {
            ProvisioningOpenSearchTransport transport = new ProvisioningOpenSearchTransport();
            new IndexTemplateInstaller(new OpenSearchClient(transport),
                    new SearchDomain("case-files"), HIGH, mapping(SUBJECT, HANDLE)).install();

            Map<String, Property> properties =
                    transport.onlyTemplate().template().mappings().properties();
            List<String> everyComponent = new ArrayList<>(SUBJECT.derivedFields());
            everyComponent.addAll(HANDLE.derivedFields());
            assertThat(everyComponent).doesNotHaveDuplicates();
            assertThat(properties.keySet()).containsAll(everyComponent);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static DomainMapping mapping(SecureFieldSpec... specs) {
        return new DomainMapping(
                DomainMappingTest::settings,
                DomainMappingTest::sharedProperties,
                java.util.Arrays.stream(specs).map(DomainMappingTest::sensitive).toList());
    }

    private static SensitiveFieldMapping sensitive(SecureFieldSpec spec) {
        return new SensitiveFieldMapping(spec, p -> p.searchAsYouType(s -> s.maxShingleSize(3)));
    }

    private static IndexSettings.Builder settings(IndexSettings.Builder builder) {
        return builder.numberOfShards(1).numberOfReplicas(0);
    }

    private static TypeMapping.Builder sharedProperties(TypeMapping.Builder builder) {
        return builder.properties(TenantDocument.TENANT_ID_FIELD, p -> p.keyword(k -> k));
    }
}
