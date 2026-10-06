package io.twba.search.toolkit.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Ported from the kata's {@code BlindIndexerTest}.
 *
 * <p>Everything asserted here is a property of the stored wire format or of the fail-closed
 * posture: the token/prefix split, the four-character floor, the twelve-character cap, tenant
 * isolation and domain separation. {@link CryptoGoldenVectorsTest} pins the exact bytes; this
 * class pins the reasons those bytes are shaped the way they are.
 */
class BlindIndexerTest {

    private static final String TENANT_A = "lab-a";
    private static final String TENANT_B = "lab-b";

    private final BlindIndexer indexer = new BlindIndexer(FixedTenantKeys.forTenants(TENANT_A, TENANT_B));

    @Test
    @DisplayName("a two-word value yields one hash per token")
    void tokenIndexCoversEveryWord() {
        List<String> hashes = indexer.tokenIndex(TENANT_A, "Maria Garcia");

        assertThat(hashes).hasSize(2);
        // Searching for one word alone has to produce a value present in the stored set —
        // that equality is the whole mechanism behind "a search for garcia matches".
        assertThat(hashes).contains(indexer.tokenIndex(TENANT_A, "maria").getFirst());
        assertThat(hashes).contains(indexer.tokenIndex(TENANT_A, "garcia").getFirst());
    }

    @Test
    @DisplayName("canonicalization reaches the hashes, so accents and punctuation do not matter")
    void tokenIndexIsCanonical() {
        assertThat(indexer.tokenIndex(TENANT_A, "Maria  García-Lopez"))
                .isEqualTo(indexer.tokenIndex(TENANT_A, "maria garcia lopez"));
    }

    @Test
    @DisplayName("a repeated word is stored once")
    void tokenIndexDeduplicates() {
        assertThat(indexer.tokenIndex(TENANT_A, "Maria Maria")).hasSize(1);
    }

    @Test
    @DisplayName("prefix grams run from 4 characters up to the length of the token")
    void prefixIndexStartsAtFour() {
        // "maria" is 5 characters: grams "mari" and "maria".
        assertThat(indexer.prefixIndex(TENANT_A, "Maria")).hasSize(2);

        // A user typing the fourth character must land on a stored value.
        String probe = indexer.prefixIndex(TENANT_A, "mari").getFirst();
        assertThat(indexer.prefixIndex(TENANT_A, "Maria")).contains(probe);
    }

    @Test
    @DisplayName("the four-character floor produces nothing at all below it")
    void prefixIndexRefusesShortProbes() {
        assertThat(indexer.prefixIndex(TENANT_A, "gar")).isEmpty();
        assertThat(indexer.prefixIndex(TENANT_A, "g")).isEmpty();
        // A short token in a longer value contributes nothing either, rather than a 1-gram.
        assertThat(indexer.prefixIndex(TENANT_A, "Jo Garcia"))
                .isEqualTo(indexer.prefixIndex(TENANT_A, "Garcia"));
    }

    @Test
    @DisplayName("prefix grams stop at twelve characters")
    void prefixIndexCapsAtTwelve() {
        // 18 characters: grams 4..12 inclusive is 9 values, not 15.
        assertThat(indexer.prefixIndex(TENANT_A, "Wolfeschlegelstein")).hasSize(9);
    }

    @Test
    @DisplayName("the same value under two tenant keys produces different hashes")
    void keysAreTenantIsolated() {
        assertThat(indexer.tokenIndex(TENANT_A, "maria garcia"))
                .doesNotContainAnyElementsOf(indexer.tokenIndex(TENANT_B, "maria garcia"));
        assertThat(indexer.fullValue(TENANT_A, "maria garcia"))
                .isNotEqualTo(indexer.fullValue(TENANT_B, "maria garcia"));
        assertThat(indexer.prefixIndex(TENANT_A, "garc"))
                .doesNotContainAnyElementsOf(indexer.prefixIndex(TENANT_B, "garc"));
    }

    @Test
    @DisplayName("token, prefix and full-value spaces are separated, so a probe cannot cross fields")
    void domainsAreSeparated() {
        assertThat(indexer.tokenIndex(TENANT_A, "garc").getFirst())
                .isNotEqualTo(indexer.prefixIndex(TENANT_A, "garc").getFirst());
        // The third tag matters just as much: without it an exact-identifier hash would collide
        // with the token hash of the same single-word value.
        assertThat(indexer.fullValue(TENANT_A, "garc"))
                .isNotEqualTo(indexer.tokenIndex(TENANT_A, "garc").getFirst())
                .isNotEqualTo(indexer.prefixIndex(TENANT_A, "garc").getFirst());
    }

    @Test
    @DisplayName("a probe finds exactly one of the grams the index stored")
    void prefixProbeMatchesTheStoredGram() {
        List<String> stored = indexer.prefixIndex(TENANT_A, "Garcia");

        // Exactly one: probing the shorter grams too would widen "garci" into "garc" and
        // return records the user never asked about.
        assertThat(indexer.prefixProbe(TENANT_A, "garci")).contains(stored.get(1));
        assertThat(indexer.prefixProbe(TENANT_A, "Garc")).contains(stored.getFirst());
    }

    @Test
    @DisplayName("below four characters there is no probe at all")
    void prefixProbeRefusesShortTerms() {
        // Empty, not a shorter gram and not a fallback: the caller issues no clause.
        assertThat(indexer.prefixProbe(TENANT_A, "gar")).isEmpty();
        assertThat(indexer.prefixProbe(TENANT_A, "")).isEmpty();
        assertThat(indexer.prefixProbe(TENANT_A, "--")).isEmpty();
        assertThat(indexer.prefixProbe(TENANT_A, "garc")).isPresent();
    }

    @Test
    @DisplayName("a multi-word probe asks about the word still being typed")
    void prefixProbeUsesTheLastToken() {
        assertThat(indexer.prefixProbe(TENANT_A, "maria garc"))
                .isEqualTo(indexer.prefixProbe(TENANT_A, "garc"));
    }

    @Test
    @DisplayName("a term longer than the longest stored gram probes that gram")
    void prefixProbeCapsAtTheStoredMaximum() {
        // The index stops at twelve characters, so a longer term can only ask about the first
        // twelve — a superset the read path narrows by decrypting, never a missed match.
        assertThat(indexer.prefixProbe(TENANT_A, "vanderbergensteinsen"))
                .isEqualTo(indexer.prefixProbe(TENANT_A, "vanderbergen"));
    }

    @Test
    @DisplayName("hashes are 160 bits of unpadded base64url")
    void hashShapeIsStable() {
        String hash = indexer.fullValue(TENANT_A, "maria garcia");

        assertThat(hash).hasSize(27).doesNotContain("=", "+", "/");
        assertThat(indexer.fullValue(TENANT_A, "maria garcia")).isEqualTo(hash);
    }

    @Test
    @DisplayName("an empty value hashes to nothing rather than to a keyed constant")
    void emptyValueHashesToNothing() {
        // A hash of the empty string would be the same 27 characters for every record with no
        // value, which is a tenant-wide "this field is blank" marker sitting in the index.
        assertThat(indexer.fullValue(TENANT_A, "")).isEmpty();
        assertThat(indexer.fullValue(TENANT_A, "  --  ")).isEmpty();
        assertThat(indexer.tokenIndex(TENANT_A, "")).isEmpty();
        assertThat(indexer.prefixIndex(TENANT_A, "")).isEmpty();
    }

    @Test
    @DisplayName("a tenant with no key material fails closed rather than hashing under a default")
    void missingKeyFailsClosed() {
        // Falling back to a shared or absent key would make one tenant's probes match another's
        // documents. The message names the tenant and nothing else.
        assertThatExceptionOfType(KeyUnavailableException.class)
                .isThrownBy(() -> indexer.tokenIndex("unconfigured-tenant", "Maria Garcia"))
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("unconfigured-tenant")
                        .doesNotContain("Maria", "maria", "garcia"));
        assertThatExceptionOfType(KeyUnavailableException.class)
                .isThrownBy(() -> indexer.prefixProbe("unconfigured-tenant", "garc"));
    }
}
