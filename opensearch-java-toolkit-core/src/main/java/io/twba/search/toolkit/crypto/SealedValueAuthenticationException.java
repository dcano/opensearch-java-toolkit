package io.twba.search.toolkit.crypto;

/**
 * A sealed value failed its authentication tag: the ciphertext, its initialization vector, or the
 * wrapped data key is not what was written.
 *
 * <p>Kept distinct from {@link KeyUnavailableException} because it means something different and
 * deserves a different response. A missing key is a configuration problem; a failed tag says the
 * stored bytes were altered, whether by corruption or by tampering, and that is worth noticing
 * rather than folding into a generic decryption error.
 *
 * <p>Either way the read fails closed. Returning a blank or partially populated value here would
 * present altered data as if it were the record as written.
 */
public class SealedValueAuthenticationException extends RuntimeException {

    public SealedValueAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
