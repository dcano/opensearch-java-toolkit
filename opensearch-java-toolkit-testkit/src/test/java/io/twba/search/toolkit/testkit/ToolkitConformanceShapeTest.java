package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.dp.SecureDocument;
import io.twba.search.toolkit.opensearch.dp.SecureDocumentMapper;
import io.twba.search.toolkit.opensearch.dp.WriteDocuments;
import jakarta.json.JsonException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the suite offers a domain module, and whether the offer is worth anything.
 *
 * <p>The first half is shape: a domain with sensitive fields gets six named checks, a plaintext-only
 * domain gets three, and the third of those three is an explicit statement that the {@code HIGH}
 * checks do not apply. That last one is not decoration — a suite that silently returned two checks
 * for a plaintext domain would be indistinguishable, in a CI log, from a suite whose {@code HIGH}
 * checks all passed.
 *
 * <p>The second half runs the two checks that need no cluster against deliberately broken subjects.
 * A conformance suite is a certificate, and a certificate nobody has ever tried to forge is not
 * evidence. Each test names the wiring fault it plants and asserts that the check notices.
 *
 * <p>The faults are chosen to be the ones a green check would hide: a keyless tenant that turns out
 * to be keyed, a refusal that came from somewhere other than the key, and a sealed payload shape the
 * suite has to narrow before it can read it.
 */
class ToolkitConformanceShapeTest {

    private final OpenSearchClient client = SyntheticWiring.clusterFreeClient();
    private final TenantIndexResolver resolver = SyntheticWiring.resolver();

    @Nested
    @DisplayName("the checks a subject is given")
    class PublishedChecks {

        @Test
        @DisplayName("a domain with a sensitive field gets all six, in a stable order")
        void sensitiveDomainGetsSixChecks() {
            ConformanceSubject<SyntheticDocument> subject = wellWiredSubject();

            assertThat(ChecksUnderTest.namesOf(subject)).containsExactly(
                    ChecksUnderTest.TENANT_ISOLATION,
                    ChecksUnderTest.TELEMETRY,
                    ChecksUnderTest.KEYLESS_WRITE,
                    ChecksUnderTest.KEYLESS_READ,
                    ChecksUnderTest.NO_READABLE_VALUE_STORED,
                    ChecksUnderTest.CLUSTER_REFUSES);
        }

        @Test
        @DisplayName("a plaintext-only domain gets three, the third saying the HIGH checks do not apply")
        void plaintextOnlyDomainGetsThreeChecks() {
            // Silently skipping would read, in a CI log, exactly like the HIGH checks passing. The
            // suite says what it did not test instead.
            ConformanceSubject<SyntheticDocument> subject = plaintextOnlySubject();

            assertThat(ChecksUnderTest.namesOf(subject)).containsExactly(
                    ChecksUnderTest.TENANT_ISOLATION,
                    ChecksUnderTest.TELEMETRY,
                    ChecksUnderTest.NO_SENSITIVE_FIELDS);
        }

        @Test
        @DisplayName("the 'does not apply' note is a check that runs and passes, not an empty shell")
        void theDoesNotApplyNoteActuallyRuns() {
            ConformanceSubject<SyntheticDocument> subject = plaintextOnlySubject();

            assertThatCode(ChecksUnderTest.check(subject, ChecksUnderTest.NO_SENSITIVE_FIELDS)::execute)
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the keyless write check")
    class KeylessWriteCheck {

        @Test
        @DisplayName("passes for a domain whose keyless HIGH tenant really has no key material")
        void passesWhenTheKeyIsGenuinelyAbsent() {
            ConformanceSubject<SyntheticDocument> subject = wellWiredSubject();

            assertThatCode(ChecksUnderTest.check(subject, ChecksUnderTest.KEYLESS_WRITE)::execute)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("fails when the supposedly keyless tenant turns out to have a key")
        void failsWhenTheKeylessTenantTurnsOutToHaveAKey() {
            // The forgery attempt: hand the suite a subject that cannot fail closed because there is
            // nothing to fail on. A check that could not tell the difference would let a domain pass
            // conformance by never testing the posture at all.
            ConformanceSubject<SyntheticDocument> subject = subjectWithKeysForEveryTenant();

            assertThatThrownBy(ChecksUnderTest.check(subject, ChecksUnderTest.KEYLESS_WRITE)::execute)
                    .isInstanceOf(AssertionError.class)
                    // "Expecting code to raise a throwable": the write produced a sealed document
                    // instead of refusing. The stack trace pins which check noticed.
                    .hasStackTraceContaining("keylessWriteFailsClosed");
        }
    }

    @Nested
    @DisplayName("the keyless read check")
    class KeylessReadCheck {

        @Test
        @DisplayName("passes for a domain whose keyless HIGH tenant really has no key material")
        void passesWhenTheKeyIsGenuinelyAbsent() {
            ConformanceSubject<SyntheticDocument> subject = wellWiredSubject();

            assertThatCode(ChecksUnderTest.check(subject, ChecksUnderTest.KEYLESS_READ)::execute)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("fails when the supposedly keyless tenant turns out to have a key")
        void failsWhenTheKeylessTenantTurnsOutToHaveAKey() {
            // The property the check's name claims and, until the restamp, could not deliver: it must
            // be sensitive to whether the key is there. With a key present the envelope is opened with
            // the wrong one and fails authentication — an exception, but not the one a destroyed key
            // produces, and the check now tells those two apart instead of accepting either.
            ConformanceSubject<SyntheticDocument> subject = subjectWithKeysForEveryTenant();

            assertThatThrownBy(ChecksUnderTest.check(subject, ChecksUnderTest.KEYLESS_READ)::execute)
                    .as("a wrong key is not a missing key, and the check must say so")
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("about the missing key material");
        }

        @Test
        @DisplayName("passes for a domain whose sealed shape is its own record, not a map")
        void passesForADomainWhoseSealedShapeIsARecord() {
            // SecureDocumentMapper exists so an application can supply its own, and the implementation
            // this toolkit generalizes returned an eighteen-component record from exactly that seam.
            // The check narrows the payload by serializing it, so any shape the client can write to
            // _source is a shape it can read back.
            ConformanceSubject<SyntheticDocument> subject = recordShapedSubject(new RecordShapedSecureMapper());

            assertThatCode(ChecksUnderTest.check(subject, ChecksUnderTest.KEYLESS_READ)::execute)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("hands the record's every field back to the mapper, losing none in the round trip")
        void aRecordPayloadRoundTripsWithoutLosingAField() {
            // The narrowing is a serialize-then-parse, so a component the serializer drops is a
            // component the mapper is asked to open without. That would surface as a fail-closed
            // refusal — the check would pass, for the wrong reason, on a domain whose read path is
            // actually fine.
            RecordShapedSecureMapper mapper = new RecordShapedSecureMapper();

            ChecksUnderTest.run(recordShapedSubject(mapper), ChecksUnderTest.KEYLESS_READ);

            assertThat(mapper.sourceSeen())
                    .as("every component of the record must survive into the _source the mapper reads")
                    .containsKeys(TenantDocument.TENANT_ID_FIELD, "documentId", "note",
                            SyntheticWiring.SECRET_LABEL.tokensField(),
                            SyntheticWiring.SECRET_LABEL.prefixesField(),
                            SyntheticWiring.SECRET_LABEL.cipherField(),
                            SyntheticWiring.SECRET_LABEL.ivField(),
                            SyntheticWiring.SECRET_LABEL.dekWrappedField(),
                            SyntheticWiring.SECRET_LABEL.dekIvField());
            assertThat(mapper.sourceSeen())
                    .as("and the tenant must be the keyless one, or the read never reaches the key")
                    .containsEntry(TenantDocument.TENANT_ID_FIELD, SyntheticWiring.KEYLESS_HIGH.tenantId());
        }

        @Test
        @DisplayName("does not pass for a payload the serializer cannot write at all")
        void doesNotPassOnAPayloadTheSerializerCannotWrite() {
            // The remaining unsupported shape, now that shape alone is no longer the question: an
            // object the client's own mapper cannot turn into JSON could never have reached _source
            // either, so it is a wiring fault. What matters is only that the check reports it rather
            // than counting the resulting exception as the refusal it was looking for — which is what
            // it did while the narrowing sat inside the assertion.
            ConformanceSubject<SyntheticDocument> subject =
                    recordShapedSubject(new UnserializableSecureMapper());

            assertThatThrownBy(ChecksUnderTest.check(subject, ChecksUnderTest.KEYLESS_READ)::execute)
                    .as("an unwritable payload must fail the check, never satisfy it")
                    .isInstanceOf(JsonException.class)
                    // From sourceOf, which is the whole point: the narrowing now sits outside the
                    // assertion, so its own failure escapes instead of being counted as the refusal.
                    .hasStackTraceContaining("sourceOf");
        }
    }

    @Nested
    @DisplayName("the reason a fail-closed check accepts")
    class TheReasonForTheRefusal {

        @Test
        @DisplayName("a write refusal that is not about the key fails the check")
        void aWriteRefusalFromAnEarlyGuardFailsTheCheck() {
            // The subtle forgery. A domain that validates before it seals refuses a keyless tenant's
            // write, loudly and for an entirely unrelated reason, and never goes near the key. Any
            // check that accepted "it threw" would issue that domain a certificate.
            ConformanceSubject<SyntheticDocument> subject = recordShapedSubject(new GuardsBeforeSealing());

            assertThatThrownBy(ChecksUnderTest.check(subject, ChecksUnderTest.KEYLESS_WRITE)::execute)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("about the missing key material");
        }

        @Test
        @DisplayName("a read refusal that is not about the key fails the check")
        void aReadRefusalFromAnEarlyGuardFailsTheCheck() {
            ConformanceSubject<SyntheticDocument> subject = recordShapedSubject(new GuardsBeforeOpening());

            assertThatThrownBy(ChecksUnderTest.check(subject, ChecksUnderTest.KEYLESS_READ)::execute)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("about the missing key material");
        }
    }

    // ------------------------------------------------------------------ subjects

    private ConformanceSubject<SyntheticDocument> wellWiredSubject() {
        return SyntheticWiring.subject(client, resolver, SyntheticWiring.secureMapper(SyntheticWiring.keys()))
                .build();
    }

    /** Every tenant keyed, including the one the suite is told is keyless. */
    private ConformanceSubject<SyntheticDocument> subjectWithKeysForEveryTenant() {
        return SyntheticWiring.subject(client, resolver, SyntheticWiring.secureMapper(
                        ConformanceTenantKeys.forTenants(
                                SyntheticWiring.NORMAL.tenantId(), SyntheticWiring.OTHER_NORMAL.tenantId(),
                                SyntheticWiring.HIGH.tenantId(), SyntheticWiring.KEYLESS_HIGH.tenantId())))
                .build();
    }

    private ConformanceSubject<SyntheticDocument> recordShapedSubject(
            SecureDocumentMapper<SyntheticDocument> mapper) {
        return SyntheticWiring.subject(client, resolver, mapper).build();
    }

    private ConformanceSubject<SyntheticDocument> plaintextOnlySubject() {
        return ConformanceSubject.<SyntheticDocument>forDomain(SyntheticWiring.DOMAIN)
                .client(client)
                .resolver(resolver)
                .documents(SyntheticDocument.class, SyntheticWiring.documents(), SyntheticWiring.sensitiveValue())
                .writeDocuments(WriteDocuments.plaintextOnly(resolver))
                .tenants(SyntheticWiring.NORMAL, SyntheticWiring.OTHER_NORMAL,
                        SyntheticWiring.HIGH, SyntheticWiring.KEYLESS_HIGH)
                .build();
    }

    // ------------------------------------------------------------------ mappers a domain might supply

    /**
     * An application-supplied mapper whose sealed shape is a record — the shape the implementation
     * this toolkit generalizes actually used, and the one a shape-matching narrowing would refuse.
     *
     * <p>It seals for real: the components come from the toolkit's own spec-driven mapper and are
     * merely carried in named record components instead of a map. That is what makes the round-trip
     * assertion meaningful — the record is a faithful alternative encoding of the same document, so
     * anything missing afterwards was lost by the narrowing.
     */
    private static final class RecordShapedSecureMapper implements SecureDocumentMapper<SyntheticDocument> {

        private final SecureDocumentMapper<SyntheticDocument> sealed =
                SyntheticWiring.secureMapper(SyntheticWiring.keys());

        private Map<String, Object> sourceSeen;

        /** The {@code _source} the suite handed back, for asserting the round trip lost nothing. */
        Map<String, Object> sourceSeen() {
            return sourceSeen;
        }

        @Override
        public Object toDocument(TenantRef tenant, SyntheticDocument source) {
            Map<String, Object> fields = ((SecureDocument) sealed.toDocument(tenant, source)).fields();
            SecureFieldSpec spec = SyntheticWiring.SECRET_LABEL;
            return new SealedShape(
                    (String) fields.get(TenantDocument.TENANT_ID_FIELD),
                    (String) fields.get("documentId"),
                    (String) fields.get("note"),
                    strings(fields.get(spec.tokensField())),
                    strings(fields.get(spec.prefixesField())),
                    (String) fields.get(spec.cipherField()),
                    (String) fields.get(spec.ivField()),
                    (String) fields.get(spec.dekWrappedField()),
                    (String) fields.get(spec.dekIvField()));
        }

        @Override
        public SyntheticDocument fromDocument(TenantRef tenant, String documentId, Map<String, Object> source) {
            this.sourceSeen = source;
            return sealed.fromDocument(tenant, documentId, source);
        }

        @SuppressWarnings("unchecked")
        private static List<String> strings(Object value) {
            return (List<String>) value;
        }

        /** Component names are the derived storage names, because that is what a record serializes. */
        private record SealedShape(String tenantId, String documentId, String note,
                                   List<String> secretLabelBidxTokens, List<String> secretLabelBidxPrefixes,
                                   String secretLabelCipher, String secretLabelIv,
                                   String secretLabelDekWrapped, String secretLabelDekIv) {
        }
    }

    /** A sealed shape the client's mapper cannot write: not a document at all. */
    private static final class UnserializableSecureMapper implements SecureDocumentMapper<SyntheticDocument> {

        @Override
        public Object toDocument(TenantRef tenant, SyntheticDocument source) {
            return new Object();
        }

        @Override
        public SyntheticDocument fromDocument(TenantRef tenant, String documentId, Map<String, Object> source) {
            throw new IllegalStateException("never reached: the payload cannot be written in the first place");
        }
    }

    /** Refuses before it would have asked for a key — loudly, and for the wrong reason. */
    private static final class GuardsBeforeSealing implements SecureDocumentMapper<SyntheticDocument> {

        @Override
        public Object toDocument(TenantRef tenant, SyntheticDocument source) {
            throw new IllegalStateException("this domain refuses the write for a reason of its own");
        }

        @Override
        public SyntheticDocument fromDocument(TenantRef tenant, String documentId, Map<String, Object> source) {
            throw new IllegalStateException("not reached");
        }
    }

    /** Seals correctly, then refuses the read before any key is consulted. */
    private static final class GuardsBeforeOpening implements SecureDocumentMapper<SyntheticDocument> {

        private final SecureDocumentMapper<SyntheticDocument> sealed =
                SyntheticWiring.secureMapper(SyntheticWiring.keys());

        @Override
        public Object toDocument(TenantRef tenant, SyntheticDocument source) {
            return sealed.toDocument(tenant, source);
        }

        @Override
        public SyntheticDocument fromDocument(TenantRef tenant, String documentId, Map<String, Object> source) {
            throw new IllegalStateException("this domain refuses the read for a reason of its own");
        }
    }
}
