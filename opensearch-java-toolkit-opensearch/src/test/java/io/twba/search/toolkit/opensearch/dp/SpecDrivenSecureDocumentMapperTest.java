package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.BlindIndexer;
import io.twba.search.toolkit.crypto.KeyUnavailableException;
import io.twba.search.toolkit.crypto.SealedFieldCipher;
import io.twba.search.toolkit.crypto.SealedValueAuthenticationException;
import io.twba.search.toolkit.crypto.TenantKeyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The one property this whole design rests on: <em>nothing readable reaches the map</em>.
 *
 * <p>The implementation this generalizes stored a {@code HIGH} tenant's document as a record with
 * eighteen named components, six of them hand-written per sensitive field. A record cannot be
 * written once for {@code N} application fields and {@code M} sensitive ones, so the toolkit uses a
 * map — and in doing so gives up the one thing a reader instinctively trusts about the record: that
 * you can see, in the type, that no component holds a plaintext name.
 *
 * <p>What actually made the record safe was never the record. It was {@code dynamic: strict} on the
 * template, which rejects any field the mapping does not define, and that mechanism protects a map
 * identically. But strictness lives on the cluster, one network hop away and out of scope for a unit
 * test. What has to replace the lost compile-time naming <em>here</em> is this file. So the central
 * test below does not check that the mapper removed a key; it renders every value the map carries
 * and asserts that none of them contains the subject's name in any spelling. An implementation that
 * scrubbed the declared field but copied it somewhere else, or hashed it but forgot to remove it,
 * or removed it only when the extractor omitted it anyway, fails that assertion.
 *
 * <p>The second theme is failing closed, on both sides of the seal. Every failure mode here — no key
 * material, an altered envelope, a missing envelope part, a destroyed key, a document read as the
 * wrong tenant — must raise. The alternative outcome is not an error, it is a blank value, and a
 * blank value is indistinguishable from a record that never had one: a privacy incident that reads
 * like a data-entry problem and is filed as one.
 */
class SpecDrivenSecureDocumentMapperTest {

    private static final SearchDomain DOMAIN = new SearchDomain("case-files");

    private static final String HIGH_TENANT = "tenant-high";
    private static final String OTHER_TENANT = "tenant-other";
    private static final String KEYLESS_TENANT = "tenant-keyless";

    private static final TenantRef HIGH = TenantRef.of(DOMAIN, HIGH_TENANT);
    private static final TenantRef OTHER = TenantRef.of(DOMAIN, OTHER_TENANT);
    private static final TenantRef KEYLESS = TenantRef.of(DOMAIN, KEYLESS_TENANT);

    private static final String DOCUMENT_ID = "doc-0001";
    private static final String OTHER_DOCUMENT_ID = "doc-0002";
    private static final String NOTE = "an ordinary field that crosses over untouched";

    /** Obviously fake, and carrying the two things text handling gets wrong: diacritics and a hyphen. */
    private static final String SUBJECT = "María García-López";
    private static final String HANDLE = "Barandilla Esmeralda";

    /**
     * Every spelling of the subject that would betray it in {@code _source}: as typed, and the
     * canonical form the hashes are derived from — the spelling that would leak if anything stored
     * a canonicalized value unhashed. Whole words only: a four-character fragment would eventually
     * collide with random base64 and turn this into a test that fails once a year for no reason.
     */
    private static final List<String> READABLE_SPELLINGS = List.of(
            "María", "Maria", "maria", "García", "Garcia", "garcia", "López", "Lopez", "lopez",
            "Barandilla", "barandilla", "Esmeralda", "esmeralda");

    private final TenantKeyProvider keys = DerivedTenantKeys.forTenants(HIGH_TENANT, OTHER_TENANT);
    private final BlindIndexer blindIndexer = new BlindIndexer(keys);
    private final SealedFieldCipher cipher = new SealedFieldCipher(keys);
    private final SpecDrivenSecureDocumentMapper<FakeSealedDocument> mapper = mapperExtractingWith(FakeSealedDocument::plain);

    private SpecDrivenSecureDocumentMapper<FakeSealedDocument> mapperExtractingWith(
            Function<FakeSealedDocument, Map<String, Object>> plain) {
        return new SpecDrivenSecureDocumentMapper<>(
                FakeSealedDocument.SPECS, plain, FakeSealedDocument::fromPlain, blindIndexer, cipher);
    }

    // ------------------------------------------------------------------ construction

    @Nested
    @DisplayName("collisions between declared fields")
    class Collisions {

        /**
         * {@code a} derives {@code aDekIv}; so does {@code aDek}. One would seal into the other's
         * components and the second written would win — the direction that loses a sealed value and
         * reports success.
         */
        @Test
        @DisplayName("two specs deriving the same storage field are refused at construction")
        void twoSpecsDerivingOneStorageFieldAreRefused() {
            List<SecureFieldSpec> colliding = List.of(new SecureFieldSpec("a"), new SecureFieldSpec("aDek"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SpecDrivenSecureDocumentMapper<>(
                            colliding, FakeSealedDocument::plain, FakeSealedDocument::fromPlain, blindIndexer, cipher))
                    .withMessageContaining("aDekIv");
        }

        /**
         * {@code a} derives {@code aCipher}, which is also a plaintext field name here. Declaring
         * both means one field's ciphertext lands where another field's plaintext was scrubbed from.
         */
        @Test
        @DisplayName("a spec whose own name is another spec's storage field is refused")
        void aSpecNamedLikeAnothersStorageFieldIsRefused() {
            List<SecureFieldSpec> colliding = List.of(new SecureFieldSpec("a"), new SecureFieldSpec("aCipher"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SpecDrivenSecureDocumentMapper<>(
                            colliding, FakeSealedDocument::plain, FakeSealedDocument::fromPlain, blindIndexer, cipher))
                    .withMessageContaining("aCipher");
        }

        @Test
        @DisplayName("the same field declared twice is refused")
        void theSameFieldDeclaredTwiceIsRefused() {
            List<SecureFieldSpec> duplicated =
                    List.of(new SecureFieldSpec("subjectName"), new SecureFieldSpec("subjectName"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SpecDrivenSecureDocumentMapper<>(
                            duplicated, FakeSealedDocument::plain, FakeSealedDocument::fromPlain, blindIndexer, cipher))
                    .withMessageContaining("subjectName");
        }

        /**
         * A secure mapper with nothing to seal would write the application's document verbatim into
         * the secure family — the exact downgrade the privacy branch exists to prevent.
         */
        @Test
        @DisplayName("a mapper with no sensitive field at all is refused")
        void aMapperWithNoSensitiveFieldIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SpecDrivenSecureDocumentMapper<>(
                            List.of(), FakeSealedDocument::plain, FakeSealedDocument::fromPlain, blindIndexer, cipher));
        }

        /**
         * The construction-time check cannot see an extractor's output, so the same collision has to
         * be caught again at mapping time. Here the extractor emits a field literally named like a
         * derived component; without this check the derived value would overwrite it silently, and
         * with the values reversed the application's field would overwrite the sealed one.
         */
        @Test
        @DisplayName("an extracted field named like a derived component is refused at mapping time")
        void anExtractedFieldNamedLikeADerivedComponentIsRefused() {
            SpecDrivenSecureDocumentMapper<FakeSealedDocument> leaking = mapperExtractingWith(document -> {
                Map<String, Object> fields = FakeSealedDocument.plain(document);
                fields.put(FakeSealedDocument.SUBJECT_NAME.cipherField(), "an application's own value");
                return fields;
            });

            assertThatIllegalStateException()
                    .isThrownBy(() -> leaking.toDocument(HIGH, document(HIGH_TENANT)))
                    .withMessageContaining(FakeSealedDocument.SUBJECT_NAME.cipherField());
        }

        /**
         * The hash arrays collide the same way, and are the worse half: overwriting a token hash
         * array does not fail anything, it just makes the document unfindable.
         */
        @Test
        @DisplayName("an extracted field named like a hash component is refused too")
        void anExtractedFieldNamedLikeAHashComponentIsRefused() {
            SpecDrivenSecureDocumentMapper<FakeSealedDocument> leaking = mapperExtractingWith(document -> {
                Map<String, Object> fields = FakeSealedDocument.plain(document);
                fields.put(FakeSealedDocument.CONTACT_HANDLE.tokensField(), List.of("not a hash"));
                return fields;
            });

            assertThatIllegalStateException()
                    .isThrownBy(() -> leaking.toDocument(HIGH, document(HIGH_TENANT)))
                    .withMessageContaining(FakeSealedDocument.CONTACT_HANDLE.tokensField());
        }
    }

    // ------------------------------------------------------------------ the scrub

    @Nested
    @DisplayName("the unconditional scrub")
    class Scrub {

        /**
         * The invariant that replaced the record. The extractor here does what every application's
         * extractor does — it hands over the plaintext, because the mapper's contract requires it —
         * and no rendering of the written map may contain it afterwards. This asserts over every
         * value in the map rather than over the declared key, because the failure worth catching is
         * not "forgot to remove" but "removed here and kept there".
         */
        @Test
        @DisplayName("no value written for a HIGH tenant contains the subject in any readable spelling")
        void nothingWrittenIsReadable() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));

            assertThat(renderEverything(written))
                    .doesNotContain(READABLE_SPELLINGS.toArray(String[]::new))
                    .doesNotContain(SUBJECT, HANDLE);
        }

        @Test
        @DisplayName("the declared sensitive field is not a key of the written map")
        void theDeclaredFieldIsGone() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));

            assertThat(written.fields())
                    .doesNotContainKey(FakeSealedDocument.SUBJECT_NAME.fieldName())
                    .doesNotContainKey(FakeSealedDocument.CONTACT_HANDLE.fieldName());
        }

        /**
         * Scrubbing is not refusing. A leaking extractor still produces a usable, searchable,
         * openable document — otherwise the safe behaviour would be an outage and someone would turn
         * it off.
         */
        @Test
        @DisplayName("the write still produces all six components of each declared field")
        void theWriteStillSucceeds() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));

            assertThat(written.fields()).containsKeys(
                    FakeSealedDocument.SUBJECT_NAME.derivedFields().toArray(String[]::new));
            assertThat(written.fields()).containsKeys(
                    FakeSealedDocument.CONTACT_HANDLE.derivedFields().toArray(String[]::new));
        }

        @Test
        @DisplayName("an undeclared application field crosses over untouched")
        void undeclaredFieldsAreCarriedAcross() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));

            assertThat(written.fields())
                    .containsEntry(FakeSealedDocument.NOTE_FIELD, NOTE)
                    .containsEntry(TenantDocument.TENANT_ID_FIELD, HIGH_TENANT)
                    .containsEntry(FakeSealedDocument.DOCUMENT_ID_FIELD, DOCUMENT_ID);
        }

        /**
         * The honest boundary of the scrub, asserted so nobody has to rediscover it. The mapper
         * removes fields it was <em>told</em> are sensitive; an extractor that copies a sensitive
         * value into a field of its own invention is copying an undeclared field, and it survives
         * here. What stops it is {@code dynamic: strict} on the secure template, which defines no
         * such property and makes the cluster reject the write. If that setting is ever softened,
         * this is the hole it opens — which is why the template test is not optional.
         */
        @Test
        @DisplayName("a value copied into an undeclared field is not scrubbed — the strict mapping is what catches that")
        void anUndeclaredCopyIsNotTheMappersToScrub() {
            SpecDrivenSecureDocumentMapper<FakeSealedDocument> copying = mapperExtractingWith(document -> {
                Map<String, Object> fields = FakeSealedDocument.plain(document);
                fields.put("subjectNameForDebugging", document.subjectName());
                return fields;
            });

            SecureDocument written = copying.toDocument(HIGH, document(HIGH_TENANT));

            assertThat(written.fields()).containsEntry("subjectNameForDebugging", SUBJECT);
        }
    }

    // ------------------------------------------------------------------ hashes

    @Nested
    @DisplayName("hashed for lookup")
    class Hashes {

        @Test
        @DisplayName("every token of the value is independently matchable")
        void everyTokenIsMatchable() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));

            assertThat(tokens(written, FakeSealedDocument.SUBJECT_NAME))
                    .contains(blindIndexer.tokenIndex(HIGH_TENANT, "maria").getFirst())
                    .contains(blindIndexer.tokenIndex(HIGH_TENANT, "garcia").getFirst())
                    .contains(blindIndexer.tokenIndex(HIGH_TENANT, "lopez").getFirst());
        }

        /**
         * The property that keeps blind indexing working at all: index-time and query-time hashes
         * come from the same methods. Drift between them produces no error anywhere — documents just
         * become unfindable.
         */
        @Test
        @DisplayName("the stored hashes are exactly the ones a query derives")
        void storedHashesAreTheQuerySideHashes() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));

            assertThat(tokens(written, FakeSealedDocument.SUBJECT_NAME))
                    .containsExactlyInAnyOrderElementsOf(blindIndexer.tokenIndex(HIGH_TENANT, "maria garcia lopez"));
            assertThat(prefixes(written, FakeSealedDocument.SUBJECT_NAME))
                    .contains(blindIndexer.prefixProbe(HIGH_TENANT, "garc").orElseThrow())
                    .contains(blindIndexer.prefixProbe(HIGH_TENANT, "garcia").orElseThrow());
        }

        /**
         * Canonicalization is shared between the two paths, so the accents and case a lab types make
         * no difference to what is findable. A second normalization anywhere would show up here.
         */
        @Test
        @DisplayName("diacritics and case canonicalize to the same hashes")
        void diacriticsCanonicalizeAway() {
            SecureDocument accented = mapper.toDocument(HIGH, document(HIGH_TENANT, "GARCÍA", HANDLE));
            SecureDocument plain = mapper.toDocument(HIGH, document(HIGH_TENANT, "garcia", HANDLE));

            assertThat(tokens(accented, FakeSealedDocument.SUBJECT_NAME))
                    .isEqualTo(tokens(plain, FakeSealedDocument.SUBJECT_NAME));
        }

        /**
         * Per-tenant keys are what make the blind index tenant-isolated: a probe computed with one
         * tenant's key matches nothing of another's, even if the tenant filter were dropped.
         */
        @Test
        @DisplayName("the same value hashes differently for two tenants")
        void twoTenantsHashTheSameValueDifferently() {
            SecureDocument mine = mapper.toDocument(HIGH, document(HIGH_TENANT));
            SecureDocument theirs = mapper.toDocument(OTHER, document(OTHER_TENANT));

            assertThat(tokens(mine, FakeSealedDocument.SUBJECT_NAME))
                    .isNotEmpty()
                    .doesNotContainAnyElementsOf(tokens(theirs, FakeSealedDocument.SUBJECT_NAME));
        }
    }

    // ------------------------------------------------------------------ sealing

    @Nested
    @DisplayName("sealed for retrieval")
    class Sealing {

        @Test
        @DisplayName("a sealed value opens to exactly what was handed in, accents and hyphen included")
        void sealedValuesRoundTrip() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));

            FakeSealedDocument read = mapper.fromDocument(HIGH, DOCUMENT_ID, written.fields());

            // Canonicalization is for the hashes only: the read path must return what was typed.
            assertThat(read.subjectName()).isEqualTo(SUBJECT);
            assertThat(read.contactHandle()).isEqualTo(HANDLE);
            assertThat(read.note()).isEqualTo(NOTE);
            assertThat(read.documentId()).isEqualTo(DOCUMENT_ID);
        }

        /**
         * A per-document data key is what stops the index itself revealing that two records carry the
         * same value. Equal ciphertexts would be an equality oracle nobody asked for.
         */
        @Test
        @DisplayName("two documents with the same value look unrelated in the index")
        void identicalValuesSealDifferently() {
            SecureDocument first = mapper.toDocument(HIGH, document(HIGH_TENANT));
            SecureDocument second = mapper.toDocument(HIGH, document(HIGH_TENANT));

            assertThat(component(first, FakeSealedDocument.SUBJECT_NAME.cipherField()))
                    .isNotEqualTo(component(second, FakeSealedDocument.SUBJECT_NAME.cipherField()));
            assertThat(component(first, FakeSealedDocument.SUBJECT_NAME.dekWrappedField()))
                    .isNotEqualTo(component(second, FakeSealedDocument.SUBJECT_NAME.dekWrappedField()));
            assertThat(component(first, FakeSealedDocument.SUBJECT_NAME.ivField()))
                    .isNotEqualTo(component(second, FakeSealedDocument.SUBJECT_NAME.ivField()));
            // The hashes deliberately DO match — that is what makes the value findable at all.
            assertThat(tokens(first, FakeSealedDocument.SUBJECT_NAME))
                    .isEqualTo(tokens(second, FakeSealedDocument.SUBJECT_NAME));
        }

        @Test
        @DisplayName("each sensitive field carries its own ciphertext, IV and wrapped key")
        void eachFieldSealsIndependently() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));

            assertThat(component(written, FakeSealedDocument.SUBJECT_NAME.cipherField()))
                    .isNotEqualTo(component(written, FakeSealedDocument.CONTACT_HANDLE.cipherField()));
            assertThat(component(written, FakeSealedDocument.SUBJECT_NAME.ivField()))
                    .isNotEqualTo(component(written, FakeSealedDocument.CONTACT_HANDLE.ivField()));
            assertThat(component(written, FakeSealedDocument.SUBJECT_NAME.dekWrappedField()))
                    .isNotEqualTo(component(written, FakeSealedDocument.CONTACT_HANDLE.dekWrappedField()));
            assertThat(component(written, FakeSealedDocument.SUBJECT_NAME.dekIvField()))
                    .isNotEqualTo(component(written, FakeSealedDocument.CONTACT_HANDLE.dekIvField()));
        }

        /**
         * Independence has to mean more than "the strings differ". If one field's data key opened
         * another's ciphertext, a single compromised envelope would open every field of the document.
         */
        @Test
        @DisplayName("one field's data key does not open another field's ciphertext")
        void oneFieldsKeyDoesNotOpenAnothersCiphertext() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));
            Map<String, Object> swapped = written.toMutableMap();
            swapped.put(FakeSealedDocument.SUBJECT_NAME.cipherField(),
                    component(written, FakeSealedDocument.CONTACT_HANDLE.cipherField()));

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> mapper.fromDocument(HIGH, DOCUMENT_ID, swapped));
        }

        /**
         * The spec's words are "neither field's envelope opens the other's", and the strongest form
         * of that is the one an attacker with write access would actually use: move field B's four
         * components wholesale onto field A's names. Swapping a single part is caught by the other
         * three disagreeing with it; a whole envelope is internally consistent, so only the binding
         * can refuse it.
         *
         * <p>This is what the associated data buys. The cipher seals with the tenant id, the
         * document id and the field name fed to GCM, so an envelope carrying field B's name in its
         * tag fails when the read recomputes the context with field A's. Without it the read path
         * would report success and return the contact handle as the subject's name — no plaintext
         * leaked, and the record silently saying something it never said.
         */
        @Test
        @DisplayName("a whole envelope moved onto another field's names does not open as that field")
        void aWholeEnvelopeIsNotPortableBetweenFields() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));
            Map<String, Object> relabelled = written.toMutableMap();
            List<String> from = FakeSealedDocument.CONTACT_HANDLE.envelopeFields();
            List<String> to = FakeSealedDocument.SUBJECT_NAME.envelopeFields();
            for (int part = 0; part < to.size(); part++) {
                relabelled.put(to.get(part), written.get(from.get(part)));
            }

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> mapper.fromDocument(HIGH, DOCUMENT_ID, relabelled))
                    .satisfies(e -> assertThat(e.getMessage())
                            .contains(DOCUMENT_ID, FakeSealedDocument.SUBJECT_NAME.fieldName()));
        }

        /**
         * The same move, one document over. Both documents belong to the same tenant and both
         * envelopes sit under the same field's names, so the tenant key and the field name agree
         * perfectly — the document id is the only part of the binding left to refuse it.
         *
         * <p>The mutation this catches is one line: a mapper that passed a constant, or the spec's
         * field name twice, instead of {@code source.documentId()} would let every record of a
         * tenant be swapped for every other, which is how a result gets attributed to the wrong
         * subject with nothing in any log to show for it.
         */
        @Test
        @DisplayName("an envelope copied into another document of the same tenant does not open")
        void aWholeEnvelopeIsNotPortableBetweenDocuments() {
            SecureDocument mine = mapper.toDocument(HIGH, document(HIGH_TENANT));
            SecureDocument theirs = mapper.toDocument(HIGH,
                    new FakeSealedDocument(HIGH_TENANT, OTHER_DOCUMENT_ID, NOTE, "Esmeralda Barandilla", HANDLE));

            Map<String, Object> transplanted = mine.toMutableMap();
            for (String component : FakeSealedDocument.SUBJECT_NAME.envelopeFields()) {
                transplanted.put(component, theirs.get(component));
            }

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> mapper.fromDocument(HIGH, DOCUMENT_ID, transplanted))
                    .satisfies(e -> assertThat(e.getMessage()).contains(DOCUMENT_ID));
        }

        @Test
        @DisplayName("one field's wrapped key does not unwrap into another field's envelope")
        void oneFieldsWrappedKeyDoesNotServeAnother() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));
            Map<String, Object> swapped = written.toMutableMap();
            swapped.put(FakeSealedDocument.SUBJECT_NAME.dekWrappedField(),
                    component(written, FakeSealedDocument.CONTACT_HANDLE.dekWrappedField()));
            swapped.put(FakeSealedDocument.SUBJECT_NAME.dekIvField(),
                    component(written, FakeSealedDocument.CONTACT_HANDLE.dekIvField()));

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> mapper.fromDocument(HIGH, DOCUMENT_ID, swapped));
        }
    }

    // ------------------------------------------------------------------ absent values

    @Nested
    @DisplayName("absent values stay absent")
    class AbsentValues {

        @Test
        @DisplayName("a null value writes empty hash arrays, not hashes of the empty string")
        void nullWritesEmptyHashArrays() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT, null, HANDLE));

            assertThat(tokens(written, FakeSealedDocument.SUBJECT_NAME)).isEmpty();
            assertThat(prefixes(written, FakeSealedDocument.SUBJECT_NAME)).isEmpty();
        }

        /**
         * Absence is asserted with {@code containsKey}, not with a null value, and the difference is
         * the documented divergence from the record this generalizes: the record serialized four
         * explicit nulls, the map omits the keys entirely. A reader who assumes the kata's shape
         * writes {@code assertThat(map.get(cipherField)).isNull()}, which passes either way and
         * proves nothing.
         */
        @Test
        @DisplayName("a null value writes no envelope component at all — the keys are absent, not null")
        void nullWritesNoEnvelopeKeys() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT, null, HANDLE));

            assertThat(written.fields())
                    .doesNotContainKeys(FakeSealedDocument.SUBJECT_NAME.envelopeFields().toArray(String[]::new));
        }

        /**
         * Null, not {@code ""}. A subject with no value on file has to stay distinguishable from one
         * whose sealed value opens to the empty string, or the two get merged by whatever reads them.
         */
        @Test
        @DisplayName("a null value reads back as null, never as an empty string")
        void nullReadsBackAsNull() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT, null, HANDLE));

            FakeSealedDocument read = mapper.fromDocument(HIGH, DOCUMENT_ID, written.fields());

            assertThat(read.subjectName()).isNull();
        }

        @Test
        @DisplayName("one field being absent does not disturb the other")
        void theOtherFieldIsStillSealed() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT, null, HANDLE));

            FakeSealedDocument read = mapper.fromDocument(HIGH, DOCUMENT_ID, written.fields());

            assertThat(read.contactHandle()).isEqualTo(HANDLE);
            assertThat(tokens(written, FakeSealedDocument.CONTACT_HANDLE)).isNotEmpty();
        }

        /**
         * A value that really is the empty string produces no tokens either — but it is sealed, so
         * the envelope is what tells the two cases apart on read. This is the pair of
         * {@link #nullReadsBackAsNull}: together they say the distinction survives a round trip.
         */
        @Test
        @DisplayName("an empty value is sealed and reads back as empty, distinguishable from absent")
        void emptyIsNotAbsent() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT, "", HANDLE));

            FakeSealedDocument read = mapper.fromDocument(HIGH, DOCUMENT_ID, written.fields());

            assertThat(written.fields())
                    .containsKeys(FakeSealedDocument.SUBJECT_NAME.envelopeFields().toArray(String[]::new));
            assertThat(read.subjectName()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ the write fails closed

    @Nested
    @DisplayName("the write fails closed")
    class WriteFailsClosed {

        /**
         * The blind indexer reaches for the tenant's HMAC key before producing anything, so this
         * raises before a document exists — let alone a request. The tenant is named because an
         * operator has to fix the configuration; nothing else is, because this message reaches logs.
         */
        @Test
        @DisplayName("a tenant with no key material gets an exception, never a document")
        void keylessTenantFailsClosed() {
            assertThatExceptionOfType(KeyUnavailableException.class)
                    .isThrownBy(() -> mapper.toDocument(KEYLESS, document(KEYLESS_TENANT)))
                    .withMessageContaining(KEYLESS_TENANT)
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .doesNotContain(READABLE_SPELLINGS.toArray(String[]::new)));
        }

        /**
         * An extractor that "helpfully" omits the field when the document has no value would write a
         * document with no searchable value and no envelope — indistinguishable from a sealed record
         * that nobody can find, discovered months later by a user who swears they entered a name.
         * The contract is: always present, null when there is nothing.
         */
        @Test
        @DisplayName("an extractor that omits a declared sensitive field is refused")
        void anOmittedSensitiveFieldIsRefused() {
            SpecDrivenSecureDocumentMapper<FakeSealedDocument> forgetful = mapperExtractingWith(document -> {
                Map<String, Object> fields = FakeSealedDocument.plain(document);
                fields.remove(FakeSealedDocument.SUBJECT_NAME.fieldName());
                return fields;
            });

            assertThatIllegalStateException()
                    .isThrownBy(() -> forgetful.toDocument(HIGH, document(HIGH_TENANT)))
                    .withMessageContaining(FakeSealedDocument.SUBJECT_NAME.fieldName());
        }

        /**
         * A non-String sensitive value cannot be canonicalized or sealed. Refusing it here beats
         * whatever {@code toString} would have produced being hashed and stored as the truth.
         */
        @Test
        @DisplayName("a non-String sensitive value is refused rather than coerced")
        void aNonStringSensitiveValueIsRefused() {
            SpecDrivenSecureDocumentMapper<FakeSealedDocument> wrongType = mapperExtractingWith(document -> {
                Map<String, Object> fields = FakeSealedDocument.plain(document);
                fields.put(FakeSealedDocument.SUBJECT_NAME.fieldName(), List.of(SUBJECT));
                return fields;
            });

            assertThatIllegalStateException()
                    .isThrownBy(() -> wrongType.toDocument(HIGH, document(HIGH_TENANT)))
                    .withMessageContaining(FakeSealedDocument.SUBJECT_NAME.fieldName());
        }
    }

    // ------------------------------------------------------------------ the read fails closed

    @Nested
    @DisplayName("the read fails closed")
    class ReadFailsClosed {

        @Test
        @DisplayName("a tampered ciphertext raises, naming the document so it can be found")
        void tamperedCiphertextRaises() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));
            Map<String, Object> altered = written.toMutableMap();
            altered.put(FakeSealedDocument.SUBJECT_NAME.cipherField(),
                    flipFirstCharacter(component(written, FakeSealedDocument.SUBJECT_NAME.cipherField())));

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> mapper.fromDocument(HIGH, DOCUMENT_ID, altered))
                    .withMessageContaining(DOCUMENT_ID)
                    .withMessageContaining(FakeSealedDocument.SUBJECT_NAME.fieldName());
        }

        /**
         * The failure message is an incident report, and incident reports get pasted into tickets.
         * A ciphertext in one still correlates two records across a retention boundary, and an
         * opened value in one is the leak the sealing existed to prevent.
         */
        @Test
        @DisplayName("the authentication failure leaks no ciphertext, key material or opened value")
        void theAuthenticationFailureLeaksNothing() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));
            String ciphertext = component(written, FakeSealedDocument.SUBJECT_NAME.cipherField());
            Map<String, Object> altered = written.toMutableMap();
            altered.put(FakeSealedDocument.SUBJECT_NAME.cipherField(), flipFirstCharacter(ciphertext));

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> mapper.fromDocument(HIGH, DOCUMENT_ID, altered))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .doesNotContain(READABLE_SPELLINGS.toArray(String[]::new))
                            .doesNotContain(ciphertext)
                            .doesNotContain(component(written, FakeSealedDocument.SUBJECT_NAME.ivField()))
                            .doesNotContain(component(written, FakeSealedDocument.SUBJECT_NAME.dekWrappedField()))
                            .doesNotContain(component(written, FakeSealedDocument.SUBJECT_NAME.dekIvField())));
        }

        /**
         * A document carrying some of an envelope but not all of it is altered data: the mapper never
         * writes a partial envelope, because an absent value carries none of the four. Returning null
         * for such a document would report "no value on file" for a record that has one.
         */
        @ParameterizedTest(name = "missing {0}")
        @MethodSource("io.twba.search.toolkit.opensearch.dp.SpecDrivenSecureDocumentMapperTest#carriedComponentsBesidesTheCiphertext")
        @DisplayName("an envelope missing a part raises rather than returning a partial result")
        void anIncompleteEnvelopeRaises(String missingComponent) {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));
            Map<String, Object> incomplete = written.toMutableMap();
            incomplete.remove(missingComponent);

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> mapper.fromDocument(HIGH, DOCUMENT_ID, incomplete))
                    .withMessageContaining(DOCUMENT_ID);
        }

        /**
         * The fourth component, and the one the sentinel check cannot see: {@code open} treats an
         * absent ciphertext as "no value was on file". A document carrying an IV and a wrapped data
         * key but no ciphertext carries an envelope — an incomplete one — and the same rule has to
         * apply to it as to the other three. Reading it as absent is a blank value derived from
         * altered data, which is precisely the outcome "HIGH fails closed" rules out.
         */
        @Test
        @DisplayName("an envelope missing its ciphertext raises too, rather than reading as absent")
        void anEnvelopeMissingItsCiphertextRaises() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));
            Map<String, Object> incomplete = written.toMutableMap();
            incomplete.remove(FakeSealedDocument.SUBJECT_NAME.cipherField());

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> mapper.fromDocument(HIGH, DOCUMENT_ID, incomplete))
                    .withMessageContaining(DOCUMENT_ID);
        }

        /**
         * Cryptographic erasure: destroying the key is how a tenant's data is retired, and it has to
         * surface as an explicit error. Silently empty results would let an erased tenant's searches
         * look merely unproductive.
         */
        @Test
        @DisplayName("a tenant whose key is gone raises rather than returning blanks")
        void aDestroyedKeyRaises() {
            SecureDocument written = mapper.toDocument(HIGH, document(HIGH_TENANT));
            SpecDrivenSecureDocumentMapper<FakeSealedDocument> afterErasure = mapperWithKeysFor(OTHER_TENANT);

            assertThatExceptionOfType(KeyUnavailableException.class)
                    .isThrownBy(() -> afterErasure.fromDocument(HIGH, DOCUMENT_ID, written.fields()))
                    .withMessageContaining(HIGH_TENANT);
        }

        /**
         * Defence in depth behind the tenant filter. Opening another tenant's document with this
         * tenant's key would fail anyway, but as an authentication error that reads like corruption —
         * and corruption gets investigated as a storage problem, not as a cross-tenant read.
         */
        @Test
        @DisplayName("a document stored under another tenant is refused before decryption is attempted")
        void aDocumentOfAnotherTenantIsRefused() {
            SecureDocument theirs = mapper.toDocument(OTHER, document(OTHER_TENANT));

            assertThatIllegalStateException()
                    .isThrownBy(() -> mapper.fromDocument(HIGH, DOCUMENT_ID, theirs.fields()))
                    .withMessageContaining(DOCUMENT_ID)
                    .withMessageContaining(OTHER_TENANT);
        }

        private SpecDrivenSecureDocumentMapper<FakeSealedDocument> mapperWithKeysFor(String... tenantIds) {
            TenantKeyProvider remaining = DerivedTenantKeys.forTenants(tenantIds);
            return new SpecDrivenSecureDocumentMapper<>(
                    FakeSealedDocument.SPECS, FakeSealedDocument::plain, FakeSealedDocument::fromPlain,
                    new BlindIndexer(remaining), new SealedFieldCipher(remaining));
        }
    }

    // ------------------------------------------------------------------ fixtures and helpers

    /**
     * The three envelope components whose absence the mapper's own guard can see, derived rather
     * than typed so a change to the suffixes moves this source with them.
     */
    static Stream<String> carriedComponentsBesidesTheCiphertext() {
        return FakeSealedDocument.SUBJECT_NAME.envelopeFields().stream()
                .filter(component -> !component.equals(FakeSealedDocument.SUBJECT_NAME.cipherField()));
    }

    private static FakeSealedDocument document(String tenantId) {
        return document(tenantId, SUBJECT, HANDLE);
    }

    private static FakeSealedDocument document(String tenantId, String subjectName, String contactHandle) {
        return new FakeSealedDocument(tenantId, DOCUMENT_ID, NOTE, subjectName, contactHandle);
    }

    /** Every key and every value the document carries, as one string — an operator's view of {@code _source}. */
    private static String renderEverything(SecureDocument document) {
        StringBuilder text = new StringBuilder();
        new LinkedHashMap<>(document.fields()).forEach((name, value) ->
                text.append(name).append('=').append(value).append('\n'));
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> tokens(SecureDocument document, SecureFieldSpec spec) {
        return (List<String>) document.get(spec.tokensField());
    }

    @SuppressWarnings("unchecked")
    private static List<String> prefixes(SecureDocument document, SecureFieldSpec spec) {
        return (List<String>) document.get(spec.prefixesField());
    }

    private static String component(SecureDocument document, String field) {
        return (String) document.get(field);
    }

    /**
     * Alters one base64url character. The first, not the last: with unpadded base64 the trailing
     * character's low bits may decode to the same bytes, which would make the tamper a no-op and the
     * test a false pass.
     */
    private static String flipFirstCharacter(String base64url) {
        char replacement = base64url.charAt(0) == 'A' ? 'B' : 'A';
        return replacement + base64url.substring(1);
    }
}
