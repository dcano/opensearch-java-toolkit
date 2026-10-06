package io.twba.search.toolkit.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden vectors for the blind index: fixed key material in, fixed base64url out.
 *
 * <p><strong>Why this test exists.</strong> The toolkit carries its own copy of the crypto because
 * it cannot depend on the kata it exists to replace (design §2). Two copies of security-critical
 * code drift, and the drift is silent: an HMAC is an equality oracle, so a canonicalizer that
 * strips one character differently produces hashes that simply never match. Nothing throws.
 * Documents quietly become unfindable.
 *
 * <p>Every literal below was produced by running the <em>kata</em> implementation under the key
 * derivation in {@link FixedTenantKeys}, and captured verbatim. They are pinned as literals, not
 * recomputed live, because {@code toolkit-core} must not depend on the kata — a {@code
 * maven-enforcer} rule fails the build if it ever does, and that rule is deliberate.
 *
 * <p><strong>These strings are a wire format.</strong> They are what is already written into
 * indices by another implementation. If a change to {@link Canonicalizer} or {@link BlindIndexer}
 * makes this test go red, the fix is not to update the literals: it is to decide whether you meant
 * to invalidate every stored hash. A change here is a reindex, not a patch.
 */
class CryptoGoldenVectorsTest {

    /** Fixed, obviously fake tenants. The key material is derived from these ids alone. */
    private static final String TENANT = "tenant-golden";
    private static final String OTHER_TENANT = "tenant-other";

    private final BlindIndexer indexer = new BlindIndexer(FixedTenantKeys.forTenants(TENANT, OTHER_TENANT));

    // The input set: diacritics, mixed case, a hyphen, an apostrophe, repeated whitespace, a
    // 30-character token that exercises the twelve-character cap, and the empty string.
    private static final String PLAIN = "Maria Garcia";
    private static final String ACCENTED = "María José García-López";
    private static final String PADDED = "  MARIA   jose  ";
    private static final String APOSTROPHE = "O'Brien";
    private static final String UMLAUTS = "Ünal Öztürk";
    private static final String COMPOUND = "Anne-Marie de la Cruz";
    private static final String LONG_TOKEN = "Wolfeschlegelsteinhausenberger";
    private static final String EMPTY = "";

    @Test
    @DisplayName("canonical forms match the pinned vectors")
    void canonicalVectors() {
        assertThat(Canonicalizer.canonical(PLAIN)).isEqualTo("maria garcia");
        assertThat(Canonicalizer.canonical(ACCENTED)).isEqualTo("maria jose garcia lopez");
        assertThat(Canonicalizer.canonical(PADDED)).isEqualTo("maria jose");
        assertThat(Canonicalizer.canonical(APOSTROPHE)).isEqualTo("o brien");
        assertThat(Canonicalizer.canonical(UMLAUTS)).isEqualTo("unal ozturk");
        assertThat(Canonicalizer.canonical(COMPOUND)).isEqualTo("anne marie de la cruz");
        assertThat(Canonicalizer.canonical(LONG_TOKEN)).isEqualTo("wolfeschlegelsteinhausenberger");
        assertThat(Canonicalizer.canonical(EMPTY)).isEqualTo("");
    }

    @Test
    @DisplayName("token splits match the pinned vectors")
    void tokenVectors() {
        assertThat(Canonicalizer.tokens(PLAIN)).containsExactly("maria", "garcia");
        assertThat(Canonicalizer.tokens(ACCENTED)).containsExactly("maria", "jose", "garcia", "lopez");
        assertThat(Canonicalizer.tokens(PADDED)).containsExactly("maria", "jose");
        assertThat(Canonicalizer.tokens(APOSTROPHE)).containsExactly("o", "brien");
        assertThat(Canonicalizer.tokens(UMLAUTS)).containsExactly("unal", "ozturk");
        assertThat(Canonicalizer.tokens(COMPOUND)).containsExactly("anne", "marie", "de", "la", "cruz");
        assertThat(Canonicalizer.tokens(LONG_TOKEN)).containsExactly("wolfeschlegelsteinhausenberger");
        assertThat(Canonicalizer.tokens(EMPTY)).isEmpty();
    }

    @Test
    @DisplayName("full-value hashes match the pinned vectors")
    void fullValueVectors() {
        assertThat(indexer.fullValue(TENANT, PLAIN)).isEqualTo("W9dDBBEoHGiGGuxiAIQNiDftCTU");
        assertThat(indexer.fullValue(TENANT, ACCENTED)).isEqualTo("a1AgJcLRRaLPZtWRYEoicOHADV8");
        assertThat(indexer.fullValue(TENANT, PADDED)).isEqualTo("gNQUeSai5z3Rs9b_fqPYnRZc2gY");
        assertThat(indexer.fullValue(TENANT, APOSTROPHE)).isEqualTo("snuzbhPSR3Cm6vYMcZHXL5Grtp8");
        assertThat(indexer.fullValue(TENANT, UMLAUTS)).isEqualTo("TSk7FOxWcHIXlb-gVB6kaux41HI");
        assertThat(indexer.fullValue(TENANT, COMPOUND)).isEqualTo("Xb-0mXxJJnhIwRlDkTLom03ehqE");
        assertThat(indexer.fullValue(TENANT, LONG_TOKEN)).isEqualTo("_vC3DkRLGnMH_xd762k4kqDmqzw");
        assertThat(indexer.fullValue(TENANT, EMPTY)).isEqualTo("");
    }

    @Test
    @DisplayName("token-index hashes match the pinned vectors, in order")
    void tokenIndexVectors() {
        assertThat(indexer.tokenIndex(TENANT, PLAIN)).containsExactly(
                "WfhMadlFTzGhL6rKHEXLaRs6Chs",
                "FiUKBW1euhziv4x_FU5sEo5bCXw");
        assertThat(indexer.tokenIndex(TENANT, ACCENTED)).containsExactly(
                "WfhMadlFTzGhL6rKHEXLaRs6Chs",
                "WXnzLjMFt8aQZWWv44GdyvhAS68",
                "FiUKBW1euhziv4x_FU5sEo5bCXw",
                "_OBq84PBWIrW88rXqCy88wZ34uQ");
        assertThat(indexer.tokenIndex(TENANT, PADDED)).containsExactly(
                "WfhMadlFTzGhL6rKHEXLaRs6Chs",
                "WXnzLjMFt8aQZWWv44GdyvhAS68");
        assertThat(indexer.tokenIndex(TENANT, APOSTROPHE)).containsExactly(
                "W28mn9ZBIhdo4xc_4XHfXwJhq8w",
                "MtW860tnkxb17xv0uEtAkiD4gfY");
        assertThat(indexer.tokenIndex(TENANT, UMLAUTS)).containsExactly(
                "PSmga6qBaRT3DrmSGyLlPgoBhVU",
                "FV9gd96jRrrbKlWumYTnKNMYBHA");
        assertThat(indexer.tokenIndex(TENANT, COMPOUND)).containsExactly(
                "EwDL9r2bhdq0DaDjuimcd79kwn8",
                "Jlqzj2uShOI3_UNOq3It_W2d5GA",
                "DuSoMxjesuUTaWmCd1brmQrcfpM",
                "aOau6XQu1xi6MQdSW9UHbTDswuE",
                "OGBQmYwKylRWk7IiWZVKkZrCSdY");
        assertThat(indexer.tokenIndex(TENANT, LONG_TOKEN)).containsExactly(
                "ZXCksJm-YoEnrfxOMuadItyYT14");
        assertThat(indexer.tokenIndex(TENANT, EMPTY)).isEmpty();
    }

    @Test
    @DisplayName("prefix-index hashes match the pinned vectors, in order")
    void prefixIndexVectors() {
        assertThat(indexer.prefixIndex(TENANT, PLAIN)).containsExactly(
                "cbjhTaaR9puGU4m_x5piZnBtCLc",
                "7vSj04Zu_OkuArgP_HEixAysmC0",
                "ePJNBcpjaPx1xWfihAmcGoUN8_o",
                "bauzGIJMP3SYZUo1a6aFNVyF5aE",
                "-_aQf-FDqILrTGBAwbopaGyGXPg");
        assertThat(indexer.prefixIndex(TENANT, ACCENTED)).containsExactly(
                "cbjhTaaR9puGU4m_x5piZnBtCLc",
                "7vSj04Zu_OkuArgP_HEixAysmC0",
                "nEQ43k0cSMTCKWsH7clQHnYIomI",
                "ePJNBcpjaPx1xWfihAmcGoUN8_o",
                "bauzGIJMP3SYZUo1a6aFNVyF5aE",
                "-_aQf-FDqILrTGBAwbopaGyGXPg",
                "3nIZOsDBfx6u0hgS4WKVmsUHf_U",
                "8oWU5EDxgnVM28ckKUGD0SxBhS4");
        assertThat(indexer.prefixIndex(TENANT, PADDED)).containsExactly(
                "cbjhTaaR9puGU4m_x5piZnBtCLc",
                "7vSj04Zu_OkuArgP_HEixAysmC0",
                "nEQ43k0cSMTCKWsH7clQHnYIomI");
        // "o" contributes no gram at all: the four-character floor drops the whole token.
        assertThat(indexer.prefixIndex(TENANT, APOSTROPHE)).containsExactly(
                "hVOJqk4e1ViziBAtHRRf2Q0D5pk",
                "XtbWXkPq2bEKEaA-mCbEbgBkJOQ");
        assertThat(indexer.prefixIndex(TENANT, UMLAUTS)).containsExactly(
                "2_qcO5T5ie3RlGTPHd2GLrqfT8U",
                "CepgA9q-hANPOOhlc2Joie--j7E",
                "p6XpVf8abV3DehGD1rTi65whGMQ",
                "JQhwGN--FMfPt3aEp7gTRTynGZo");
        // "de" and "la" fall under the floor; "anne" and "cruz" contribute one gram each.
        assertThat(indexer.prefixIndex(TENANT, COMPOUND)).containsExactly(
                "Y-GsOBgqy444DtJfPyel8IXfPYE",
                "cbjhTaaR9puGU4m_x5piZnBtCLc",
                "vOq_hwBhts3w9fw5hC-NzvVai1Y",
                "LQ1jzda05dyjn8uF4P9KhOSesmM");
        // A 30-character token still stops at twelve grams' worth: 4..12 inclusive is nine.
        assertThat(indexer.prefixIndex(TENANT, LONG_TOKEN)).containsExactly(
                "ivbZPvwIUWtEE2TzKOOe_0M716U",
                "ckADnKGJbaBIZ9C2xyQn9-pwhkM",
                "_U8AX0BjovyuW5Xu33jkpJmdTGo",
                "XtxFoVIynNhptWK-GT9P6NGk6Co",
                "2y0pc-krMiSHlsTNi1OF6CuOVXM",
                "AxKQAsZmbiD-yZ1NWlsob0K7zD0",
                "lp-dwdS6uT7UBQHGo7iCC213otw",
                "yRpSZdXgV596IN-LmW1HO73PON8",
                "46DpbaclQQqN4eQebGnsIc9Na84");
        assertThat(indexer.prefixIndex(TENANT, EMPTY)).isEmpty();
    }

    @Test
    @DisplayName("prefix probes match the pinned vectors, including the empty ones below the floor")
    void prefixProbeVectors() {
        // Below the four-character floor there is no probe at all — the emptiness is as much a
        // pinned vector as the hashes, because the alternative is a silently widened search.
        assertThat(indexer.prefixProbe(TENANT, "gar")).isEmpty();
        assertThat(indexer.prefixProbe(TENANT, "garc")).contains("ePJNBcpjaPx1xWfihAmcGoUN8_o");
        assertThat(indexer.prefixProbe(TENANT, "garci")).contains("bauzGIJMP3SYZUo1a6aFNVyF5aE");
        assertThat(indexer.prefixProbe(TENANT, "garcia")).contains("-_aQf-FDqILrTGBAwbopaGyGXPg");
        assertThat(indexer.prefixProbe(TENANT, "maria gar")).isEmpty();
        assertThat(indexer.prefixProbe(TENANT, "maria garc")).contains("ePJNBcpjaPx1xWfihAmcGoUN8_o");
        assertThat(indexer.prefixProbe(TENANT, LONG_TOKEN)).contains("46DpbaclQQqN4eQebGnsIc9Na84");
        assertThat(indexer.prefixProbe(TENANT, "  ")).isEmpty();
        assertThat(indexer.prefixProbe(TENANT, "ó")).isEmpty();
    }

    @Test
    @DisplayName("the typed probes land on grams the index actually stored")
    void probesAndIndexAgreeOnTheSameVectors() {
        // Cross-check between two independently pinned vector sets: if either one were updated in
        // isolation to "make the build green", this assertion would catch it.
        List<String> stored = indexer.prefixIndex(TENANT, PLAIN);

        assertThat(indexer.prefixProbe(TENANT, "garc")).hasValueSatisfying(probe -> assertThat(stored).contains(probe));
        assertThat(indexer.prefixProbe(TENANT, "garcia")).hasValueSatisfying(probe -> assertThat(stored).contains(probe));
    }

    @Test
    @DisplayName("a second tenant hashes the same input to entirely different vectors")
    void tenantIsolationVector() {
        // Same plaintext, different tenant key: none of the golden hashes may reappear. This is
        // the property that keeps one tenant's probe from matching another's documents even if a
        // tenant filter were dropped from a query.
        assertThat(indexer.tokenIndex(OTHER_TENANT, PLAIN)).containsExactly(
                "_BxPXD4Fla9Ekj7LO8bD6diJMYg",
                "HKgXb-eMKGljJH0t_joEoYL3qy4");
        assertThat(indexer.tokenIndex(OTHER_TENANT, PLAIN))
                .doesNotContainAnyElementsOf(indexer.tokenIndex(TENANT, PLAIN));
    }
}
