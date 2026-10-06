package io.twba.search.toolkit.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Ported from the kata's {@code PatientFieldCipherTest} — {@code PatientFieldCipher} is
 * {@link SealedFieldCipher} here, with the domain vocabulary removed.
 *
 * <p>The theme is fail-closed. Every negative case below has a plausible-looking alternative
 * outcome — a blank value, a partially decrypted string, an empty {@code Optional} — and each of
 * those would present altered or unreadable data as though it were the record as written.
 *
 * <p>The second theme, and the reason this cipher diverges from the one it was ported from, is that
 * an envelope is bound to <em>where it lives</em>: tenant, document and field go into the
 * authentication tag as associated data. Without that, four self-consistent components are portable
 * — anyone able to write to the index can relabel them onto another field or another document and
 * the read path reports success while returning one record's value as another's. See
 * {@link BoundToWhereItLives}.
 */
class SealedFieldCipherTest {

    private static final String TENANT = "lab-a";
    private static final String OTHER_TENANT = "lab-b";

    private static final String DOCUMENT = "doc-0001";
    private static final String OTHER_DOCUMENT = "doc-0002";

    private static final String FIELD = "subjectName";
    private static final String OTHER_FIELD = "contactHandle";

    /** Obviously fake test data; no real person's details appear in this suite. */
    private static final String VALUE = "María García-Lopez";

    private final SealedFieldCipher cipher =
            new SealedFieldCipher(FixedTenantKeys.forTenants(TENANT, OTHER_TENANT));

    @Test
    @DisplayName("a sealed value comes back exactly as it went in, accents and all")
    void roundTrips() {
        SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);

        assertThat(cipher.open(TENANT, DOCUMENT, FIELD, sealed)).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("an empty value round-trips as empty rather than being confused with a failure")
    void roundTripsAnEmptyValue() {
        SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, "");

        assertThat(cipher.open(TENANT, DOCUMENT, FIELD, sealed)).isEmpty();
    }

    @Test
    @DisplayName("the sealed parts carry no readable trace of the value")
    void sealedPartsAreOpaque() {
        SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);

        assertThat(sealed.cipher()).doesNotContain("Garcia", "garcia", "María");
        assertThat(sealed.toString()).doesNotContain(sealed.cipher(), sealed.dekWrapped());
    }

    @Test
    @DisplayName("identical values seal to different ciphertexts under their own data keys")
    void identicalPlaintextsDiffer() {
        SealedValue first = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);
        SealedValue second = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);

        // Otherwise the index would reveal that two records carry the same value.
        assertThat(first.cipher()).isNotEqualTo(second.cipher());
        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.dekWrapped()).isNotEqualTo(second.dekWrapped());
        assertThat(first.dekIv()).isNotEqualTo(second.dekIv());

        assertThat(cipher.open(TENANT, DOCUMENT, FIELD, first))
                .isEqualTo(cipher.open(TENANT, DOCUMENT, FIELD, second))
                .isEqualTo(VALUE);
    }

    @Test
    @DisplayName("a tampered ciphertext fails the authentication tag")
    void tamperedCipherFailsClosed() {
        SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);
        SealedValue tampered = new SealedValue(flipFirstByte(sealed.cipher()), sealed.iv(), sealed.dekWrapped(), sealed.dekIv());

        assertThatExceptionOfType(SealedValueAuthenticationException.class)
                .isThrownBy(() -> cipher.open(TENANT, DOCUMENT, FIELD, tampered));
    }

    @Test
    @DisplayName("a tampered IV fails the authentication tag")
    void tamperedIvFailsClosed() {
        SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);
        SealedValue tampered = new SealedValue(sealed.cipher(), flipFirstByte(sealed.iv()), sealed.dekWrapped(), sealed.dekIv());

        assertThatExceptionOfType(SealedValueAuthenticationException.class)
                .isThrownBy(() -> cipher.open(TENANT, DOCUMENT, FIELD, tampered));
    }

    @Test
    @DisplayName("a tampered wrapped data key fails the authentication tag")
    void tamperedWrappedKeyFailsClosed() {
        SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);
        SealedValue tampered = new SealedValue(sealed.cipher(), sealed.iv(), flipFirstByte(sealed.dekWrapped()), sealed.dekIv());

        assertThatExceptionOfType(SealedValueAuthenticationException.class)
                .isThrownBy(() -> cipher.open(TENANT, DOCUMENT, FIELD, tampered));
    }

    @Test
    @DisplayName("a malformed part is an authentication failure, not a crash")
    void malformedPartsFailClosed() {
        SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);

        // Not base64url at all: corruption in transit or storage must land in the same
        // fail-closed branch as tampering, never escape as a raw IllegalArgumentException.
        assertThatExceptionOfType(SealedValueAuthenticationException.class)
                .isThrownBy(() -> cipher.open(TENANT, DOCUMENT, FIELD,
                        new SealedValue("!!not base64!!", sealed.iv(), sealed.dekWrapped(), sealed.dekIv())));

        // Valid base64url of the wrong length for a 96-bit IV.
        assertThatExceptionOfType(SealedValueAuthenticationException.class)
                .isThrownBy(() -> cipher.open(TENANT, DOCUMENT, FIELD,
                        new SealedValue(sealed.cipher(), "AAAA", sealed.dekWrapped(), sealed.dekIv())));
    }

    @Test
    @DisplayName("another tenant's key cannot open the value")
    void crossTenantOpenFails() {
        SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);

        assertThatExceptionOfType(SealedValueAuthenticationException.class)
                .isThrownBy(() -> cipher.open(OTHER_TENANT, DOCUMENT, FIELD, sealed));
    }

    @Test
    @DisplayName("a tenant with no key material fails closed rather than returning a blank value")
    void missingKeyFailsClosed() {
        SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);

        assertThatExceptionOfType(KeyUnavailableException.class)
                .isThrownBy(() -> cipher.open("unconfigured-tenant", DOCUMENT, FIELD, sealed))
                .satisfies(e -> assertThat(e.getMessage()).contains("unconfigured-tenant").doesNotContain(VALUE));
        assertThatExceptionOfType(KeyUnavailableException.class)
                .isThrownBy(() -> cipher.seal("unconfigured-tenant", DOCUMENT, FIELD, VALUE));
    }

    @Test
    @DisplayName("an envelope missing a part is refused at construction")
    void anIncompleteEnvelopeIsRefused() {
        // A document that lost one of the four fields cannot be decrypted; catching that where
        // the value is built keeps a half-envelope from reaching the cipher at all.
        SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new SealedValue(null, sealed.iv(), sealed.dekWrapped(), sealed.dekIv()));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new SealedValue(sealed.cipher(), sealed.iv(), sealed.dekWrapped(), null));
    }

    // ------------------------------------------------------------------ the binding

    /**
     * The requirement: an envelope is bound to the tenant, the document and the field it was sealed
     * for, so one moved anywhere else does not open.
     *
     * <p>The adversary here is not the reader — no plaintext leaks either way — it is the writer.
     * An unbound envelope is four self-consistent components and nothing more, so anyone able to
     * write to the index can move them onto another field's names or into another document, and the
     * read path reports success while showing one record's value as another's. A semi-trusted
     * cluster operator is exactly who a blind-index posture assumes, so defending only against
     * reading is half a defence.
     */
    @Nested
    @DisplayName("an envelope is bound to where it lives")
    class BoundToWhereItLives {

        @Test
        @DisplayName("an envelope moved to another field does not open")
        void movedToAnotherFieldDoesNotOpen() {
            SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> cipher.open(TENANT, DOCUMENT, OTHER_FIELD, sealed))
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(VALUE, "Garc"));
        }

        @Test
        @DisplayName("an envelope moved to another document does not open")
        void movedToAnotherDocumentDoesNotOpen() {
            SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> cipher.open(TENANT, OTHER_DOCUMENT, FIELD, sealed));
        }

        /**
         * The tenant is in the binding as well as in the key, and this pins the binding rather than
         * the key: both tenants below are handed the same key material, so the only thing left to
         * refuse the read is the associated data. Drop {@code tenantId} from the context and this
         * test opens another tenant's envelope; the cross-tenant test above would not notice.
         */
        @Test
        @DisplayName("an envelope read under another tenant does not open, even on shared key material")
        void readUnderAnotherTenantDoesNotOpen() {
            SealedFieldCipher shared = new SealedFieldCipher(oneKeyForEveryTenant());
            SealedValue sealed = shared.seal(TENANT, DOCUMENT, FIELD, VALUE);

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> shared.open(OTHER_TENANT, DOCUMENT, FIELD, sealed));
        }

        /**
         * The binding is three parts joined into one byte string, and joining is where this kind of
         * scheme is usually broken: any delimiter can appear inside a part, and plain concatenation
         * has no delimiter at all. Each pair below would be one and the same string under a naive
         * join, so if the length prefixes are ever "simplified" away, one of these rows starts
         * opening the other's envelope — silently, with no error anywhere.
         */
        @ParameterizedTest(name = "[{0}] does not open [{1}]")
        @MethodSource("io.twba.search.toolkit.crypto.SealedFieldCipherTest#ambiguousBindings")
        @DisplayName("bindings that a naive join would collide still differ")
        void theBindingIsUnambiguous(Binding sealedUnder, Binding readAs) {
            SealedFieldCipher shared = new SealedFieldCipher(oneKeyForEveryTenant());
            SealedValue sealed = shared.seal(sealedUnder.tenantId(), sealedUnder.documentId(), sealedUnder.fieldName(), VALUE);

            assertThatExceptionOfType(SealedValueAuthenticationException.class)
                    .isThrownBy(() -> shared.open(readAs.tenantId(), readAs.documentId(), readAs.fieldName(), sealed));

            // The control: the envelope is sound and opens under its own binding, so the failure
            // above is the binding and not a broken fixture.
            assertThat(shared.open(sealedUnder.tenantId(), sealedUnder.documentId(), sealedUnder.fieldName(), sealed))
                    .isEqualTo(VALUE);
        }

        /**
         * The divergence is in the binding, not in what is written. The context is recomputed on
         * read and never stored, so a document keeps carrying exactly four components in the same
         * shape as before: four unpadded base64url strings, a 96-bit IV each side, a wrapped
         * 256-bit data key, and a ciphertext of the value plus its 128-bit tag. A fifth component,
         * or a context smuggled into an existing one, would make every stored document unreadable.
         */
        @Test
        @DisplayName("the stored shape is still four components and nothing else")
        void theStoredShapeIsUnchanged() {
            SealedValue sealed = cipher.seal(TENANT, DOCUMENT, FIELD, VALUE);

            assertThat(SealedValue.class.getRecordComponents()).hasSize(4);
            assertThat(decoded(sealed.iv())).hasSize(12);
            assertThat(decoded(sealed.dekIv())).hasSize(12);
            assertThat(decoded(sealed.dekWrapped())).hasSize(32 + 16);
            assertThat(decoded(sealed.cipher())).hasSize(VALUE.getBytes(StandardCharsets.UTF_8).length + 16);

            // Unpadded base64url, as stored: no '=', and none of standard base64's '+' or '/'.
            assertThat(new String[] {sealed.cipher(), sealed.iv(), sealed.dekWrapped(), sealed.dekIv()})
                    .allSatisfy(part -> assertThat(part).doesNotContain("=", "+", "/"));
        }
    }

    /** One (tenant, document, field) address an envelope can be sealed under or read as. */
    record Binding(String tenantId, String documentId, String fieldName) {
        @Override
        public String toString() {
            return "%s / %s / %s".formatted(tenantId, documentId, fieldName);
        }
    }

    /**
     * Pairs whose parts differ but whose naive joins do not: straight concatenation, and a join on
     * each of the delimiters someone would reach for first. Document ids come from the application,
     * so any character a delimiter could be is a character a document id may contain.
     */
    static Stream<Arguments> ambiguousBindings() {
        return Stream.of(
                // Concatenation: "a" + "bc" is "ab" + "c".
                Arguments.of(new Binding(TENANT, "a", "bc"), new Binding(TENANT, "ab", "c")),
                // Concatenation across the tenant boundary: "lab" + "a" is "laba" + "".
                Arguments.of(new Binding("lab", "a", FIELD), new Binding("laba", "", FIELD)),
                // A pipe-joined context: "doc|name" + "|" + "x" is "doc" + "|" + "name|x".
                Arguments.of(new Binding(TENANT, "doc|name", "x"), new Binding(TENANT, "doc", "name|x")),
                // A colon-joined one, the other habitual choice.
                Arguments.of(new Binding(TENANT, "doc:name", "x"), new Binding(TENANT, "doc", "name:x")),
                // And a hyphen, which this codebase's own index names are built from.
                Arguments.of(new Binding("lab-a", "doc-1", FIELD), new Binding("lab", "a-doc-1", FIELD)));
    }

    /**
     * The same, obviously fake key for every tenant — thirty-two zero bytes. It exists so a test can
     * separate "the binding refused this" from "the key refused this": with per-tenant keys, a
     * cross-tenant read fails for two independent reasons and proves neither.
     */
    private static TenantKeyProvider oneKeyForEveryTenant() {
        SecretKey shared = new SecretKeySpec(new byte[32], "AES");
        return new TenantKeyProvider() {
            @Override
            public SecretKey kek(String tenantId) {
                return shared;
            }

            @Override
            public SecretKey hmacKey(String tenantId) {
                return shared;
            }
        };
    }

    private static byte[] decoded(String base64Url) {
        return Base64.getUrlDecoder().decode(base64Url);
    }

    /** Corrupts one byte while keeping the encoding and length valid, so the tag is what fails. */
    private static String flipFirstByte(String base64Url) {
        byte[] bytes = Base64.getUrlDecoder().decode(base64Url);
        bytes[0] ^= 0x01;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
