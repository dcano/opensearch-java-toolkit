package io.twba.search.toolkit.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Seals and opens sensitive field values with envelope encryption.
 *
 * <p>Every document gets a freshly generated AES-256 data key. The value is sealed with AES-GCM
 * under that key; the data key is then wrapped with the tenant's key encryption key and stored
 * beside the ciphertext.
 *
 * <p>A per-document data key looks like extra work next to one key per tenant, and it buys two
 * things worth the cost. It makes the document self-contained — no key store on the read path, no
 * second lookup per hit — and it hands out a fresh key and IV pair for every single record, which
 * removes the classic GCM nonce-reuse footgun outright rather than defending against it with a
 * counter someone has to get right. Destroying the tenant's key encryption key still shreds every
 * document, because a wrapped data key without its wrapping key is noise.
 *
 * <p>Decryption is authenticated, and a failed tag raises
 * {@link SealedValueAuthenticationException} rather than yielding an empty string. Silently blank
 * values are indistinguishable from a record that never had one.
 *
 * <h2>An envelope is bound to where it lives</h2>
 *
 * <p>The tenant, the document id and the field name are fed to AES-GCM as associated data. They are
 * not stored — they are recomputed from the read's own context — and an envelope only opens when
 * all three match what it was sealed under.
 *
 * <p>Without that binding an envelope is self-contained and portable: anyone able to write to the
 * index can copy the four components of one field onto another field's names, or onto another
 * document, and the read path reports success while showing one record's value as another's. No
 * plaintext leaks, but the stored data silently stops meaning what it says. A semi-trusted cluster
 * operator is precisely the adversary this whole posture assumes, so leaving the envelope unbound
 * would defend against the reader and not against the writer.
 *
 * <p>The binding is length-prefixed rather than delimiter-joined, so no part can be shifted into
 * another by choosing a document id that contains the delimiter.
 *
 * <p>This is a deliberate divergence from the implementation being generalized, which seals without
 * associated data. Envelopes are therefore <em>not</em> interchangeable between the two: blind-index
 * hashes still are, and are pinned by golden vectors.
 */
public final class SealedFieldCipher {

    private static final String KEY_ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int DEK_BITS = 256;

    /** 96 bits is the GCM-native IV length: any other size costs an extra GHASH and buys nothing. */
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final TenantKeyProvider keys;
    private final SecureRandom random;

    public SealedFieldCipher(TenantKeyProvider keys) {
        this(keys, new SecureRandom());
    }

    SealedFieldCipher(TenantKeyProvider keys, SecureRandom random) {
        this.keys = keys;
        this.random = random;
    }

    /**
     * Seals {@code plaintext} for {@code tenantId}, minting a data key for this value alone.
     *
     * <p>Two documents holding the same value produce different ciphertexts, so an observer cannot
     * tell from the index that two records carry the same value.
     */
    public SealedValue seal(String tenantId, String documentId, String fieldName, String plaintext) {
        SecretKey kek = keys.kek(tenantId);
        byte[] context = context(tenantId, documentId, fieldName);
        byte[] dekBytes = null;
        try {
            SecretKey dek = newDataKey();
            dekBytes = dek.getEncoded();

            byte[] valueIv = randomIv();
            byte[] cipher = encrypt(dek, valueIv, context, plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] wrapIv = randomIv();
            byte[] dekWrapped = encrypt(kek, wrapIv, context, dekBytes);

            return new SealedValue(
                    ENCODER.encodeToString(cipher),
                    ENCODER.encodeToString(valueIv),
                    ENCODER.encodeToString(dekWrapped),
                    ENCODER.encodeToString(wrapIv));
        } catch (GeneralSecurityException e) {
            throw new KeyUnavailableException(tenantId, "the key encryption key could not seal a value", e);
        } finally {
            if (dekBytes != null) {
                Arrays.fill(dekBytes, (byte) 0);
            }
        }
    }

    /**
     * Opens a sealed value, unwrapping its data key with the tenant's key encryption key.
     *
     * @throws SealedValueAuthenticationException if any part fails its authentication tag
     * @throws KeyUnavailableException            if the tenant has no usable key encryption key
     */
    public String open(String tenantId, String documentId, String fieldName, SealedValue sealed) {
        SecretKey kek = keys.kek(tenantId);
        byte[] context = context(tenantId, documentId, fieldName);
        byte[] dekBytes = null;
        try {
            dekBytes = decrypt(kek, DECODER.decode(sealed.dekIv()), context, DECODER.decode(sealed.dekWrapped()));
            SecretKey dek = new SecretKeySpec(dekBytes, KEY_ALGORITHM);
            byte[] plaintext = decrypt(dek, DECODER.decode(sealed.iv()), context, DECODER.decode(sealed.cipher()));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            // The document id is deliberately kept out of the message even though this method now
            // takes one: the adapter catches this and re-throws naming the hit, which is where the
            // knowledge of what a hit is belongs.
            throw new SealedValueAuthenticationException(
                    "sealed value failed authentication: the ciphertext, IV or wrapped data key was altered", e);
        } catch (IllegalArgumentException e) {
            throw new SealedValueAuthenticationException("sealed value is not valid base64url", e);
        } catch (InvalidAlgorithmParameterException e) {
            // A stored IV of the wrong length is altered data, not a key problem.
            throw new SealedValueAuthenticationException("sealed value carries a malformed IV", e);
        } catch (GeneralSecurityException e) {
            throw new KeyUnavailableException(tenantId, "the key encryption key could not open a value", e);
        } finally {
            if (dekBytes != null) {
                Arrays.fill(dekBytes, (byte) 0);
            }
        }
    }

    private SecretKey newDataKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KEY_ALGORITHM);
        generator.init(DEK_BITS, random);
        return generator.generateKey();
    }

    private byte[] randomIv() {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        return iv;
    }

    private static byte[] encrypt(SecretKey key, byte[] iv, byte[] context, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(context);
        return cipher.doFinal(plaintext);
    }

    private static byte[] decrypt(SecretKey key, byte[] iv, byte[] context, byte[] ciphertext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(context);
        return cipher.doFinal(ciphertext);
    }

    /**
     * The associated data an envelope is bound to, as length-prefixed UTF-8 parts.
     *
     * <p>Length-prefixed rather than joined by a delimiter: {@code ("a|b", "c")} and
     * {@code ("a", "b|c")} would otherwise produce the same bytes, and a document id is supplied by
     * the application, so it can contain whatever the application allows.
     */
    private static byte[] context(String... parts) {
        byte[][] raw = new byte[parts.length][];
        int size = 0;
        for (int i = 0; i < parts.length; i++) {
            raw[i] = Objects.requireNonNull(parts[i], "binding part").getBytes(StandardCharsets.UTF_8);
            size += Integer.BYTES + raw[i].length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (byte[] part : raw) {
            buffer.putInt(part.length).put(part);
        }
        return buffer.array();
    }
}
