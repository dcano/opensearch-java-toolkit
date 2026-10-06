package io.twba.search.toolkit.crypto;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single answer to "what makes two sensitive values the same".
 *
 * <p>Every blind index — index-time and query-time alike — is derived from the output of this
 * class. That is not a style preference: an HMAC is an equality oracle, so if the two paths ever
 * disagree about whether {@code "García"} and {@code "garcia"} are the same string, matches stop
 * happening and <em>nothing reports an error</em>. Documents simply become unfindable. To make
 * that drift impossible there is one function, no configuration knobs, and no alternative
 * normalization anywhere in the toolkit.
 *
 * <p>Changing the rules below invalidates every hash already stored, so a change here is a
 * reindex, never a patch. That is also why an application cannot supply its own: a second
 * canonicalizer is a silent leak waiting for the first accented character.
 */
public final class Canonicalizer {

    /** Anything that is not a letter or a digit separates tokens — hyphens, apostrophes, commas. */
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Unicode combining marks, left behind once NFKD splits an accented character apart. */
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private Canonicalizer() {
    }

    /**
     * Canonical form: lowercase, diacritic-free, punctuation reduced to single spaces, trimmed.
     *
     * <p>The order is deliberate. NFKD first splits {@code í} into {@code i} + combining acute so
     * the mark can be stripped; lowercasing uses {@link Locale#ROOT} so a Turkish default locale
     * cannot turn {@code I} into {@code ı} and silently fork the hashes of a value.
     */
    public static String canonical(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        String unaccented = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lowered = unaccented.toLowerCase(Locale.ROOT);
        return NON_ALPHANUMERIC.matcher(lowered).replaceAll(" ").strip();
    }

    /**
     * The canonical form split into its words, in order, with duplicates preserved.
     *
     * <p>Callers hash these one by one to build the token index, and hash a query's tokens the
     * same way to probe it.
     */
    public static List<String> tokens(String value) {
        String canonical = canonical(value);
        if (canonical.isEmpty()) {
            return List.of();
        }
        return List.of(canonical.split(" "));
    }
}
