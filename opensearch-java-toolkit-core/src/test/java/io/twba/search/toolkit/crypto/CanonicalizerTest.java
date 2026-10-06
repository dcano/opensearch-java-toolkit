package io.twba.search.toolkit.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the canonicalization rules.
 *
 * <p>These assertions exist to fail loudly the day someone edits {@link Canonicalizer}, because
 * the alternative failure mode is silent: hashes drift, documents stop matching, and nothing
 * anywhere reports an error.
 *
 * <p>Ported from the kata's {@code CanonicalizerTest}. The toolkit carries a second copy of this
 * function, and two copies that are only equal by review are two copies that will diverge; these
 * cases plus the golden vectors are what makes the divergence loud.
 */
class CanonicalizerTest {

    @Test
    @DisplayName("diacritics do not survive canonicalization")
    void stripsDiacritics() {
        assertThat(Canonicalizer.canonical("García")).isEqualTo("garcia");
        assertThat(Canonicalizer.canonical("GARCÍA")).isEqualTo("garcia");
        assertThat(Canonicalizer.canonical("Müller")).isEqualTo("muller");
        assertThat(Canonicalizer.canonical("Renée")).isEqualTo("renee");
    }

    @Test
    @DisplayName("a precomposed character and its decomposed form canonicalize alike")
    void normalisationFormDoesNotMatter() {
        // U+00ED vs. "i" + U+0301. Two systems typing the same value can disagree on which
        // encoding they send; if the NFKD pass were dropped, one of them would become
        // permanently unfindable by the other with no error anywhere.
        assertThat(Canonicalizer.canonical("García")).isEqualTo(Canonicalizer.canonical("García"));
    }

    @Test
    @DisplayName("case does not change the canonical form")
    void isCaseInsensitive() {
        assertThat(Canonicalizer.canonical("MARIA")).isEqualTo(Canonicalizer.canonical("maria"));
        assertThat(Canonicalizer.canonical("MaRiA")).isEqualTo("maria");
    }

    @Test
    @DisplayName("the headline equality from the spec holds")
    void garciaEqualsGarcia() {
        assertThat(Canonicalizer.canonical("García")).isEqualTo(Canonicalizer.canonical("garcia"));
    }

    @Test
    @DisplayName("lowercasing ignores the JVM default locale")
    void lowercasingIsLocaleIndependent() {
        // The javadoc's stated reason for Locale.ROOT: under a Turkish default, "I" lowercases to
        // the dotless "ı" and every value containing a capital I forks into hashes no other node
        // can reproduce. Swapping the default out and back is the only way to observe that.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(Canonicalizer.canonical("ISABEL")).isEqualTo("isabel");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("punctuation and repeated whitespace collapse to single spaces")
    void normalisesSeparators() {
        assertThat(Canonicalizer.canonical("Maria  García-Lopez"))
                .isEqualTo(Canonicalizer.canonical("maria garcia lopez"))
                .isEqualTo("maria garcia lopez");
        assertThat(Canonicalizer.canonical("O'Brien, Sean")).isEqualTo("o brien sean");
        assertThat(Canonicalizer.canonical("  leading and trailing  ")).isEqualTo("leading and trailing");
    }

    @Test
    @DisplayName("tokens are the canonical words, in order")
    void splitsIntoTokens() {
        assertThat(Canonicalizer.tokens("Maria  García-Lopez"))
                .containsExactly("maria", "garcia", "lopez");
        assertThat(Canonicalizer.tokens("García")).containsExactly("garcia");
    }

    @Test
    @DisplayName("empty, blank and null inputs produce nothing rather than a blank token")
    void handlesEmptyInput() {
        assertThat(Canonicalizer.canonical(null)).isEmpty();
        assertThat(Canonicalizer.canonical("")).isEmpty();
        assertThat(Canonicalizer.canonical("   ---   ")).isEmpty();
        assertThat(Canonicalizer.tokens(null)).isEqualTo(List.of());
        assertThat(Canonicalizer.tokens("  ")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("digits survive, so identifiers embedded in a value still hash")
    void keepsDigits() {
        assertThat(Canonicalizer.canonical("Ward 3B")).isEqualTo("ward 3b");
    }
}
