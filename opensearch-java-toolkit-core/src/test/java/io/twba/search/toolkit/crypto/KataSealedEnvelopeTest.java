package io.twba.search.toolkit.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The divergence, pinned from the other side.
 *
 * <p>The four strings below were produced by running the <em>kata's</em> {@code PatientFieldCipher}
 * for {@code tenant-golden} under the key derivation in {@link FixedTenantKeys}, and captured
 * verbatim. They stand in for a document already written to an index by that implementation.
 *
 * <p>This class used to assert that {@link SealedFieldCipher} opened them, on the argument that the
 * envelope format is a wire format shared by the two implementations. That is no longer true, and
 * deliberately so: the toolkit binds every envelope to its tenant, document and field as associated
 * data (design §2), the kata seals without it and is frozen, so the two no longer open each other's
 * envelopes. The cost was accepted knowingly — an unbound envelope is portable between fields,
 * documents and records, which lets anyone with write access make a document say something it never
 * said.
 *
 * <p>The literals stay, because they are still evidence — of something different. They show that
 * the refusal below is the binding and nothing else: {@link #theEnvelopeIsIntactWithoutTheBinding}
 * opens the very same bytes by hand with no associated data and recovers the value exactly, so the
 * envelope is well formed, the key derivation still agrees, and only the context differs. If the
 * binding is ever dropped, that test keeps passing and every test under it goes red.
 *
 * <p>What this class does <em>not</em> claim, and used to: that a document written by the kata can
 * be read by the toolkit. It cannot. Migrating such an index means re-sealing, not re-capturing
 * these literals. Hash derivation is untouched and remains byte-identical — {@link
 * CryptoGoldenVectorsTest} is where that is pinned.
 */
class KataSealedEnvelopeTest {

    private static final String TENANT = "tenant-golden";

    /** Obviously fake test data, sealed by the kata implementation. */
    private static final String EXPECTED_PLAINTEXT = "María José García-López";

    private static final String CIPHER = "AeTgoK3OeQ_NU2ddYhgFVtnVgs5imhMAFYXUWIQjuHxRYkmgmNnQed3QzA";
    private static final String IV = "W6G36T9N9LKF9jia";
    private static final String DEK_WRAPPED = "yjQHn1V8sFZjmWFnXSWT_TvjPby80sYpfmi1FtQFctpq74RXG3mCDCTz3v8pexCb";
    private static final String DEK_IV = "uwwqYA_m7AalYXlY";

    /** What the kata document was; the toolkit needs an address the kata never recorded. */
    private static final String DOCUMENT = "doc-0001";
    private static final String FIELD = "patientName";

    private final FixedTenantKeys keys = FixedTenantKeys.forTenants(TENANT);
    private final SealedFieldCipher cipher = new SealedFieldCipher(keys);

    private static SealedValue kataSealed() {
        return new SealedValue(CIPHER, IV, DEK_WRAPPED, DEK_IV);
    }

    /**
     * The control for everything below. AES-256-GCM, a 96-bit IV, a 128-bit tag, a per-document data
     * key wrapped under the tenant KEK, all four parts unpadded base64url — done by hand here, with
     * no associated data, which is precisely what the kata does. It recovers the value, so the
     * literals are sound and the key derivation still agrees across the two implementations.
     */
    @Test
    @DisplayName("the pinned envelope is intact and opens by hand when no binding is fed in")
    void theEnvelopeIsIntactWithoutTheBinding() throws Exception {
        SecretKey kek = keys.kek(TENANT);

        byte[] dataKey = openWithoutBinding(kek, DEK_IV, DEK_WRAPPED);
        byte[] plaintext = openWithoutBinding(new SecretKeySpec(dataKey, "AES"), IV, CIPHER);

        assertThat(new String(plaintext, StandardCharsets.UTF_8)).isEqualTo(EXPECTED_PLAINTEXT);
    }

    /**
     * The divergence itself. Not "the bytes are corrupt" and not "the key is wrong" — the test above
     * rules both out — but "this envelope was not sealed for this tenant, document and field".
     *
     * <p>It raises rather than returning anything, which is the requirement the refusal exists for:
     * {@code HIGH} fails closed. A swallowed tag failure returning {@code ""} or {@code null} would
     * present an envelope this implementation cannot vouch for as the record as written, and a blank
     * name is indistinguishable from a record that never had one.
     */
    @Test
    @DisplayName("the toolkit cipher refuses a kata-sealed envelope: it authenticates, it does not decode")
    void theToolkitRefusesAKataSealedEnvelope() {
        assertThatExceptionOfType(SealedValueAuthenticationException.class)
                .isThrownBy(() -> cipher.open(TENANT, DOCUMENT, FIELD, kataSealed()))
                .satisfies(failure -> {
                    // Authentication, stated as such: a malformed-IV or bad-base64 message would
                    // send someone looking for corruption that is not there.
                    assertThat(failure.getMessage()).contains("failed authentication");
                    // And nothing of the value or the envelope travels with it — this reaches logs.
                    assertThat(failure.getMessage())
                            .doesNotContain(EXPECTED_PLAINTEXT, "Garc", CIPHER, DEK_WRAPPED);
                });
    }

    /**
     * No choice of address recovers it, including the empty one. The empty triple is the case worth
     * naming: the kata's associated data is <em>nothing at all</em>, so an implementation that
     * skipped {@code updateAAD} for empty parts — a plausible "optimization" — would open this
     * envelope and quietly restore the portability the binding exists to remove. Length prefixes
     * make an empty triple twelve bytes of zeros, not zero bytes, and it stays shut.
     */
    @ParameterizedTest(name = "no value is recovered under tenant [{0}], document [{1}], field [{2}]")
    @CsvSource(value = {
            "tenant-golden | doc-0001    | patientName",
            "tenant-golden | doc-0001    | ''",
            "tenant-golden | ''          | ''",
            "tenant-golden | ''          | patientName",
            "tenant-golden | doc-0001    | patientNameCipher"},
            delimiter = '|')
    void noAddressOpensIt(String tenantId, String documentId, String fieldName) {
        assertThatExceptionOfType(SealedValueAuthenticationException.class)
                .isThrownBy(() -> cipher.open(tenantId.trim(), documentId.trim(), fieldName.trim(), kataSealed()));
    }

    /** AES-256-GCM with no associated data — the kata's scheme, reproduced on the JDK alone. */
    private static byte[] openWithoutBinding(SecretKey key, String iv, String ciphertext) throws Exception {
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, decode(iv)));
        return aes.doFinal(decode(ciphertext));
    }

    private static byte[] decode(String base64Url) {
        return Base64.getUrlDecoder().decode(base64Url);
    }
}
