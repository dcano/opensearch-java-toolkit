package io.twba.search.toolkit.crypto;

/**
 * A value sealed under envelope encryption, in the four parts a document must carry to be
 * self-contained.
 *
 * <p>Self-contained is the point: the wrapped data key travels next to the ciphertext, so reading
 * a hit needs no key-store lookup beyond the tenant's key encryption key, and a restored snapshot
 * decrypts as long as that one key still exists.
 *
 * <p>All four parts are unpadded base64url text, because they are stored as {@code keyword} fields
 * with {@code index: false} — carried, never searched.
 *
 * @param cipher     the value encrypted under the data key, with the GCM tag appended
 * @param iv         the 96-bit initialization vector used for {@code cipher}
 * @param dekWrapped the data key encrypted under the tenant's key encryption key
 * @param dekIv      the 96-bit initialization vector used to wrap the data key
 */
public record SealedValue(
        String cipher,
        String iv,
        String dekWrapped,
        String dekIv
) {

    public SealedValue {
        if (cipher == null || iv == null || dekWrapped == null || dekIv == null) {
            throw new IllegalArgumentException("a sealed value needs all four parts; a missing part cannot be decrypted");
        }
    }

    /**
     * Never print the parts. They are not plaintext, but a ciphertext in a log line is still an
     * artefact that outlives its retention policy and correlates records across systems.
     */
    @Override
    public String toString() {
        return "SealedValue[sealed]";
    }
}
