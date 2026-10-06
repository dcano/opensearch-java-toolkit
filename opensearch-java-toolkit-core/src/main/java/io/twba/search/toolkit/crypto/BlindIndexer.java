package io.twba.search.toolkit.crypto;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Derives the searchable hashes that stand in for a sensitive value in the secure index.
 *
 * <p>The cluster never sees the plaintext, so it cannot analyze, stem, fuzzy-match or highlight
 * these fields — an HMAC supports equality and nothing else. Partial matching is therefore
 * assembled here, by the only party holding the key, by storing extra hashes: one per token, plus
 * one per 4–12 character prefix of each token. Searching is then a terms lookup against those
 * exact values.
 *
 * <p>Two shape decisions are worth knowing:
 *
 * <ul>
 *   <li><strong>Truncated to 160 bits.</strong> The hash is a lookup key, not an authenticator —
 *       a collision costs a spurious candidate that decryption then discards — so 20 of the 32
 *       bytes buy a materially smaller index at no real risk.
 *   <li><strong>A four-character floor on prefixes.</strong> Three-character grams make dictionary
 *       inversion of the index substantially easier and inflate it badly, while four keeps
 *       type-ahead usable from the fourth keystroke. Probes below the floor produce nothing at all
 *       rather than quietly widening into some broader search.
 * </ul>
 *
 * <p>The same instance serves both paths: indexing a document and building a query call the
 * identical methods, which is the property that keeps the two in agreement forever.
 *
 * <p>Every constant here is load-bearing for compatibility. The truncation length, the domain
 * separation tags, the gram bounds and the base64url alphabet together define the stored value,
 * so changing any of them is a reindex. Golden vectors pin them.
 */
public final class BlindIndexer {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 160 bits of the 256-bit HMAC output. */
    private static final int TRUNCATED_BYTES = 20;

    public static final int MIN_PREFIX_LENGTH = 4;
    public static final int MAX_PREFIX_LENGTH = 12;

    /**
     * Domain separation tags. Without them the token hash of {@code "garc"} and the prefix hash of
     * {@code "garc"} would be the same value, letting a probe cross between fields and blurring
     * what a match actually proves.
     */
    private static final byte FULL_VALUE_TAG = 'F';
    private static final byte TOKEN_TAG = 'T';
    private static final byte PREFIX_TAG = 'P';

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final TenantKeyProvider keys;

    public BlindIndexer(TenantKeyProvider keys) {
        this.keys = keys;
    }

    /**
     * One hash of the whole canonical value — the exact-equality lookup, used for identifiers that
     * are only ever matched in full.
     */
    public String fullValue(String tenantId, String value) {
        return hash(tenantId, FULL_VALUE_TAG, Canonicalizer.canonical(value));
    }

    /**
     * One hash per canonical token, so every word of a value is independently matchable — a search
     * for {@code "garcia"} finds {@code "Maria Garcia"}.
     *
     * <p>Duplicates collapse: storing the same hash twice tells an observer that a value repeats a
     * word, and buys nothing.
     */
    public List<String> tokenIndex(String tenantId, String value) {
        Set<String> hashes = new LinkedHashSet<>();
        for (String token : Canonicalizer.tokens(value)) {
            hashes.add(hash(tenantId, TOKEN_TAG, token));
        }
        return List.copyOf(hashes);
    }

    /**
     * Hashes of every 4–12 character prefix of every token, which is what makes type-ahead possible
     * over encrypted values.
     *
     * <p>At query time this same method is the probe: {@code prefixIndex(tenant, "garc")} yields
     * the single hash to look for, and a term shorter than four characters yields an empty list —
     * the caller then issues no prefix clause at all.
     */
    public List<String> prefixIndex(String tenantId, String value) {
        Set<String> hashes = new LinkedHashSet<>();
        for (String token : Canonicalizer.tokens(value)) {
            int longest = Math.min(token.length(), MAX_PREFIX_LENGTH);
            for (int length = MIN_PREFIX_LENGTH; length <= longest; length++) {
                hashes.add(hash(tenantId, PREFIX_TAG, token.substring(0, length)));
            }
        }
        return List.copyOf(hashes);
    }

    /**
     * The single prefix hash to probe for a term the user is still typing.
     *
     * <p>{@link #prefixIndex} is the index-time half: it stores every gram from 4 to 12 characters,
     * because any of them may later be typed. A query wants exactly one of them — the longest,
     * which is the term itself — since probing the shorter grams as well would quietly widen
     * "garci" into "garc" and return records the user did not ask about.
     *
     * <p>Empty below the four-character floor, and that emptiness is the contract: the caller
     * issues no prefix clause at all rather than falling back to something broader. A term longer
     * than twelve characters probes its twelve-character gram, so the branch may return a few
     * candidates the full term would have excluded — the read path decrypts and the UI shows the
     * real value, so a superset costs precision, never correctness.
     */
    public Optional<String> prefixProbe(String tenantId, String term) {
        List<String> tokens = Canonicalizer.tokens(term);
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        // A partially typed term is one word; if the caller passed several, the one being typed is
        // the last, which is the only one a prefix probe makes sense for.
        String token = tokens.getLast();
        if (token.length() < MIN_PREFIX_LENGTH) {
            return Optional.empty();
        }
        String gram = token.substring(0, Math.min(token.length(), MAX_PREFIX_LENGTH));
        return Optional.of(hash(tenantId, PREFIX_TAG, gram));
    }

    private String hash(String tenantId, byte tag, String value) {
        if (value.isEmpty()) {
            return "";
        }
        SecretKey key = keys.hmacKey(tenantId);
        byte[] full;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            mac.update(tag);
            full = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // The message deliberately names neither the value nor the key.
            throw new KeyUnavailableException(tenantId, "the HMAC key was rejected by the JCE provider", e);
        }
        byte[] truncated = Arrays.copyOf(full, TRUNCATED_BYTES);
        Arrays.fill(full, (byte) 0);
        return ENCODER.encodeToString(truncated);
    }
}
