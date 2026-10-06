package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.dp.SecureDocumentMapper;
import io.twba.search.toolkit.opensearch.dp.WriteDocuments;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * What a domain module has to get right before the conformance suite will run at all.
 *
 * <p>The subject is a ten-value builder, four of whose values are {@link TenantRef}s and two of
 * which are functions over the same document type. Every refusal below is for a mistake that has no
 * other symptom — a suite handed the same tenant twice would assert that a tenant cannot see itself
 * and pass for any implementation; a suite handed a tenant from another domain would search an index
 * this subject does not describe; a domain declaring a sensitive field with no mapper would install
 * its templates, start cleanly, and fail months later on the first {@code HIGH} write.
 *
 * <p>Every test here builds a subject with no cluster behind it. Nothing in construction sends a
 * request, and {@link UnusedOpenSearchTransport} is what keeps that honest.
 */
class ConformanceSubjectTest {

    private static final SearchDomain OTHER_DOMAIN = new SearchDomain("other-domain");

    private final OpenSearchClient client = SyntheticWiring.clusterFreeClient();
    private final TenantIndexResolver resolver = SyntheticWiring.resolver();
    private final SecureDocumentMapper<SyntheticDocument> mapper =
            SyntheticWiring.secureMapper(SyntheticWiring.keys());

    private ConformanceSubject.Builder<SyntheticDocument> wellWired() {
        return SyntheticWiring.subject(client, resolver, mapper);
    }

    @Nested
    @DisplayName("a correctly described domain")
    class WellWired {

        @Test
        @DisplayName("builds, and hands back exactly what it was given")
        void buildsAndExposesItsWiring() {
            ConformanceSubject<SyntheticDocument> subject = wellWired().build();

            assertThat(subject.domain()).isEqualTo(SyntheticWiring.DOMAIN);
            assertThat(subject.specs()).containsExactly(SyntheticWiring.SECRET_LABEL);
            assertThat(subject.documentType()).isEqualTo(SyntheticDocument.class);
            assertThat(subject.normalTenant()).isEqualTo(SyntheticWiring.NORMAL);
            assertThat(subject.otherNormalTenant()).isEqualTo(SyntheticWiring.OTHER_NORMAL);
            assertThat(subject.highTenant()).isEqualTo(SyntheticWiring.HIGH);
            assertThat(subject.keylessHighTenant()).isEqualTo(SyntheticWiring.KEYLESS_HIGH);
        }

        @Test
        @DisplayName("its document factory and value reader agree, which the suite relies on")
        void documentFactoryAndValueReaderAgree() {
            // The suite plants a sentinel through document() and hunts for it afterwards. A subject
            // whose two functions disagreed would make every leak check hunt for a value that was
            // never written, and every one of them would pass.
            ConformanceSubject<SyntheticDocument> subject = wellWired().build();

            SyntheticDocument document = subject.document(SyntheticWiring.HIGH, "a-planted-sentinel");

            assertThat(document.tenantId()).isEqualTo(SyntheticWiring.HIGH.tenantId());
            assertThat(subject.sensitiveValueOf(document)).isEqualTo("a-planted-sentinel");
        }

        @Test
        @DisplayName("a domain with no sensitive fields may omit the secure mapper entirely")
        void plaintextOnlyDomainNeedsNoMapper() {
            assertThatNoException().isThrownBy(() -> plaintextOnlySubject().build());
        }
    }

    @Nested
    @DisplayName("a domain that declares a sensitive field but wires no mapper")
    class MisconfiguredDomain {

        @Test
        @DisplayName("is refused, and the refusal names the domain")
        void isRefusedByName() {
            // The spec scenario: "a domain registers a secure field but wires no secure mapper" must
            // fail conformance "with an error naming the domain". The name is the load-bearing part
            // — a CI log showing a refusal for an unnamed domain, in a build running several, is a
            // failure nobody can act on.
            assertThatIllegalStateException()
                    .isThrownBy(() -> wellWired().secureFields(SyntheticWiring.SPECS, null).build())
                    .withMessageContaining(SyntheticWiring.DOMAIN.name())
                    .withMessageContaining(SyntheticWiring.SECRET_LABEL.fieldName());
        }

        @Test
        @DisplayName("is refused whichever domain declared it, so the name is not a constant")
        void namesWhicheverDomainDeclaredIt() {
            // Guards against a message that happens to contain the right word. Build the same
            // mistake under a second domain and the message must follow it.
            assertThatIllegalStateException()
                    .isThrownBy(() -> ConformanceSubject.<SyntheticDocument>forDomain(OTHER_DOMAIN)
                            .client(client)
                            .resolver(resolver)
                            .documents(SyntheticDocument.class, SyntheticWiring.documents(),
                                    SyntheticWiring.sensitiveValue())
                            .writeDocuments(WriteDocuments.plaintextOnly(resolver))
                            .secureFields(SyntheticWiring.SPECS, null)
                            .tenants(TenantRef.of(OTHER_DOMAIN, "tenant-alpha"),
                                    TenantRef.of(OTHER_DOMAIN, "tenant-beta"),
                                    TenantRef.of(OTHER_DOMAIN, "tenant-high"),
                                    TenantRef.of(OTHER_DOMAIN, "tenant-keyless"))
                            .build())
                    .withMessageContaining(OTHER_DOMAIN.name())
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .doesNotContain(SyntheticWiring.DOMAIN.name()));
        }

        @Test
        @DisplayName("declaring no field and wiring no mapper is a legitimate plaintext-only domain")
        void noFieldsAndNoMapperIsFine() {
            assertThatNoException()
                    .isThrownBy(() -> wellWired().secureFields(List.of(), null).build());
        }

        @Test
        @DisplayName("a null spec list is read as 'no sensitive fields', not as a missing value")
        void nullSpecListMeansNoSensitiveFields() {
            ConformanceSubject<SyntheticDocument> subject =
                    wellWired().secureFields(null, null).build();

            assertThat(subject.specs()).isEmpty();
        }
    }

    @Nested
    @DisplayName("tenants the suite cannot use")
    class UnusableTenants {

        @Test
        @DisplayName("a tenant from another domain is refused, naming both domains and the setter")
        void tenantFromAnotherDomainIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> wellWired()
                            .tenants(TenantRef.of(OTHER_DOMAIN, "tenant-alpha"), SyntheticWiring.OTHER_NORMAL,
                                    SyntheticWiring.HIGH, SyntheticWiring.KEYLESS_HIGH)
                            .build())
                    .withMessageContaining("normalTenant")
                    .withMessageContaining(OTHER_DOMAIN.name())
                    .withMessageContaining(SyntheticWiring.DOMAIN.name());
        }

        @Test
        @DisplayName("the check covers the HIGH tenants too, not just the first one")
        void foreignHighTenantIsRefused() {
            // Four TenantRefs of the same type: a guard applied to the first and forgotten on the
            // rest is exactly the bug this builder exists to make impossible.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> wellWired()
                            .tenants(SyntheticWiring.NORMAL, SyntheticWiring.OTHER_NORMAL,
                                    SyntheticWiring.HIGH, TenantRef.of(OTHER_DOMAIN, "tenant-keyless"))
                            .build())
                    .withMessageContaining("keylessHighTenant");
        }

        @Test
        @DisplayName("the same tenant given twice for the isolation check is refused")
        void sameTenantTwiceIsRefused() {
            // The refusal that saves a conformance run from being worthless: searching one tenant and
            // asserting it cannot see its own documents passes for every implementation ever written,
            // including one with no tenant filter at all.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> wellWired()
                            .tenants(SyntheticWiring.NORMAL, SyntheticWiring.NORMAL,
                                    SyntheticWiring.HIGH, SyntheticWiring.KEYLESS_HIGH)
                            .build())
                    .withMessageContaining(SyntheticWiring.DOMAIN.name())
                    .withMessageContaining("same tenant twice");
        }
    }

    @Nested
    @DisplayName("values the suite cannot run without")
    class MissingValues {

        @Test
        @DisplayName("no domain at all is refused before a builder exists")
        void nullDomainIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ConformanceSubject.<SyntheticDocument>forDomain(null))
                    .withMessageContaining("domain");
        }

        @Test
        @DisplayName("a missing client is named")
        void missingClientIsNamed() {
            assertThatNullPointerException()
                    .isThrownBy(() -> wellWired().client(null).build())
                    .withMessageContaining("client");
        }

        @Test
        @DisplayName("a missing resolver is named")
        void missingResolverIsNamed() {
            assertThatNullPointerException()
                    .isThrownBy(() -> wellWired().resolver(null).build())
                    .withMessageContaining("resolver");
        }

        @Test
        @DisplayName("a missing write path is named")
        void missingWriteDocumentsIsNamed() {
            assertThatNullPointerException()
                    .isThrownBy(() -> wellWired().writeDocuments(null).build())
                    .withMessageContaining("writeDocuments");
        }

        @Test
        @DisplayName("a missing document type is named")
        void missingDocumentTypeIsNamed() {
            assertThatNullPointerException()
                    .isThrownBy(() -> wellWired()
                            .documents(null, SyntheticWiring.documents(), SyntheticWiring.sensitiveValue())
                            .build())
                    .withMessageContaining("documentType");
        }

        @Test
        @DisplayName("a missing document factory is named")
        void missingDocumentFactoryIsNamed() {
            assertThatNullPointerException()
                    .isThrownBy(() -> wellWired()
                            .documents(SyntheticDocument.class, null, SyntheticWiring.sensitiveValue())
                            .build())
                    .withMessageContaining("documents");
        }

        @Test
        @DisplayName("a missing sensitive-value reader is named")
        void missingSensitiveValueReaderIsNamed() {
            assertThatNullPointerException()
                    .isThrownBy(() -> wellWired()
                            .documents(SyntheticDocument.class, SyntheticWiring.documents(), null)
                            .build())
                    .withMessageContaining("sensitiveValue");
        }

        @Test
        @DisplayName("tenants that were never supplied are named rather than reported as a foreign domain")
        void missingTenantsAreNamed() {
            // The domain guard dereferences the tenant. Reaching it with a null would produce an NPE
            // with no name in it, which is the least useful possible message for a ten-value builder.
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ConformanceSubject.<SyntheticDocument>forDomain(SyntheticWiring.DOMAIN)
                            .client(client)
                            .resolver(resolver)
                            .documents(SyntheticDocument.class, SyntheticWiring.documents(),
                                    SyntheticWiring.sensitiveValue())
                            .writeDocuments(WriteDocuments.plaintextOnly(resolver))
                            .build())
                    .withMessageContaining("normalTenant");
        }
    }

    private ConformanceSubject.Builder<SyntheticDocument> plaintextOnlySubject() {
        return ConformanceSubject.<SyntheticDocument>forDomain(SyntheticWiring.DOMAIN)
                .client(client)
                .resolver(resolver)
                .documents(SyntheticDocument.class, SyntheticWiring.documents(), SyntheticWiring.sensitiveValue())
                .writeDocuments(WriteDocuments.plaintextOnly(resolver))
                .tenants(SyntheticWiring.NORMAL, SyntheticWiring.OTHER_NORMAL,
                        SyntheticWiring.HIGH, SyntheticWiring.KEYLESS_HIGH);
    }
}
