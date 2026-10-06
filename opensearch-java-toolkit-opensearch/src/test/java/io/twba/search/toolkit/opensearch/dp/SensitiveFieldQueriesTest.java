package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.BlindIndexer;
import io.twba.search.toolkit.crypto.TenantKeyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.client.opensearch._types.query_dsl.Query;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The shape of the identifier branch, asserted against the JSON that actually goes to the cluster.
 *
 * <p>Shape is worth pinning here rather than only end to end, because every one of these mistakes
 * still returns plausible results for the simple queries an integration test tends to use. A branch
 * that quietly became {@code must} answers a one-word query perfectly. A branch that probed prefixes
 * on every token answers a one-word query perfectly. A branch that dropped {@code constant_score}
 * answers every query, just in a slightly different order. None of them survives this file.
 *
 * <p>Nothing in this file asserts what the hashes <em>are</em> — that is the core module's business,
 * pinned there by golden vectors. What is asserted here is that a hash is what travels, that it was
 * derived through the same methods the write path uses, and that the text it came from does not
 * appear anywhere in the request.
 */
class SensitiveFieldQueriesTest {

    private static final SearchDomain LAB = new SearchDomain("lab-results");
    private static final SearchDomain CLAIMS = new SearchDomain("claims");

    private static final String TENANT = "clinic-guarded";
    private static final String OTHER_TENANT = "clinic-other";

    private static final TenantRef HIGH = TenantRef.of(LAB, TENANT);
    private static final TenantRef OTHER = TenantRef.of(LAB, OTHER_TENANT);
    /** The same organisation, reached through a second domain — same key material, by design. */
    private static final TenantRef SAME_TENANT_OTHER_DOMAIN = TenantRef.of(CLAIMS, TENANT);

    private static final SecureFieldSpec SUBJECT = new SecureFieldSpec("subjectName");

    /** The application's own mapping's terms: the field and the shingle sub-fields it declared. */
    private static final List<String> PLAINTEXT_FIELDS =
            List.of("subjectName", "subjectName._2gram", "subjectName._3gram");

    private final TenantKeyProvider keys = DerivedTenantKeys.forTenants(TENANT, OTHER_TENANT);
    private final BlindIndexer blindIndexer = new BlindIndexer(keys);
    private final SensitiveFieldQueries queries =
            new SensitiveFieldQueries(SUBJECT, PLAINTEXT_FIELDS, blindIndexer);

    @Nested
    @DisplayName("the HIGH branch is a should of constant-scored token clauses")
    class HighShape {

        @Test
        @DisplayName("one constant_score clause per token, combined with should and a minimum of one")
        void oneConstantScoreClausePerToken() {
            String json = json(PrivacyLevel.HIGH, HIGH, "marisol quintanilla");

            // should, not must: "quintanilla hemolyzed" must still fire on the identifier half, and
            // it cannot if every query token has to match a value token.
            assertThat(json).contains("\"minimum_should_match\":\"1\"");
            assertThat(occurrences(json, "constant_score")).isEqualTo(2);
            // A fixed boost per clause makes the score a count of matched tokens. Left scored,
            // Lucene weighs a term by how rare it is inside the tenant — which is meaningless as
            // relevance and mildly disclosive as a statistic.
            assertThat(occurrences(json, "\"boost\":1.0")).isEqualTo(2);
        }

        @Test
        @DisplayName("the branch is named, so a hit can say which half produced it")
        void theBranchIsNamed() {
            assertThat(json(PrivacyLevel.HIGH, HIGH, "quintanilla"))
                    .contains("\"_name\":\"" + BranchNames.IDENTIFIER + "\"");
        }

        @Test
        @DisplayName("all tokens but the last probe the token field only")
        void onlyTheTrailingTokenProbesPrefixes() {
            String json = json(PrivacyLevel.HIGH, HIGH, "marisol quin");

            // "marisol" is a finished word — the user typed a space after it — so it is matched
            // whole, exactly as bool_prefix treats plaintext. Probing every token would widen
            // every term in the query.
            assertThat(occurrences(json, SUBJECT.prefixesField())).isEqualTo(1);
            assertThat(occurrences(json, SUBJECT.tokensField())).isEqualTo(2);
        }

        @Test
        @DisplayName("the trailing token probes the token field OR the prefix field, worth one unit either way")
        void theTrailingTokenProbesBothFields() {
            String json = json(PrivacyLevel.HIGH, HIGH, "quintanilla");

            assertThat(json).contains(SUBJECT.tokensField(), SUBJECT.prefixesField());
            // Both alternatives sit inside one constant_score, so a token that matches exactly and
            // by prefix scores one, not two.
            assertThat(occurrences(json, "constant_score")).isEqualTo(1);
        }

        @Test
        @DisplayName("a probe at exactly four characters is issued")
        void theFloorIsInclusive() {
            assertThat(json(PrivacyLevel.HIGH, HIGH, "quin")).contains(SUBJECT.prefixesField());
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"q", "qu", "qui"})
        @DisplayName("below four characters no prefix clause is built at all")
        void probesBelowTheFloorAreNotIssued(String tooShort) {
            String json = json(PrivacyLevel.HIGH, HIGH, tooShort);

            // Not a narrower probe, not a wildcard, not a fallback to a broader search: nothing.
            // What remains matches a value whose token is literally that short.
            assertThat(json).doesNotContain(SUBJECT.prefixesField());
            assertThat(json).contains(SUBJECT.tokensField());
        }

        @Test
        @DisplayName("nothing about the query text survives into the request")
        void theQueryTextNeverReachesTheCluster() {
            String json = json(PrivacyLevel.HIGH, HIGH, "Marisol Quintanilla-Écija");

            assertThat(json).doesNotContainIgnoringCase("marisol", "quintanilla", "écija", "ecija");
        }

        @Test
        @DisplayName("no fuzziness, no wildcards: a hash one bit out is not a near miss")
        void theBranchIsExactTermsOnly() {
            String json = json(PrivacyLevel.HIGH, HIGH, "quintanilla");

            assertThat(json).doesNotContain("fuzziness", "wildcard", "regexp", "match_phrase");
            assertThat(json).contains("\"term\"");
        }
    }

    @Nested
    @DisplayName("keys belong to the tenant, not to the domain it is searched through")
    class KeyScope {

        @Test
        @DisplayName("the same tenant id in two domains probes the same values")
        void theProbeIsKeyedOnTheBareTenantId() {
            // Deliberate, and the opposite of how IdentifierSearchLimiter is keyed: key material
            // belongs to the tenant, so a domain change must not silently derive different hashes
            // and make a tenant's own documents unfindable through one of its domains.
            assertThat(json(PrivacyLevel.HIGH, HIGH, "quintanilla"))
                    .isEqualTo(json(PrivacyLevel.HIGH, SAME_TENANT_OTHER_DOMAIN, "quintanilla"));
        }

        @Test
        @DisplayName("two tenants probing the same text send different hashes")
        void probesAreTenantIsolated() {
            assertThat(json(PrivacyLevel.HIGH, HIGH, "quintanilla"))
                    .isNotEqualTo(json(PrivacyLevel.HIGH, OTHER, "quintanilla"));
        }
    }

    @Nested
    @DisplayName("the NORMAL branch is the application's own plaintext fields")
    class NormalShape {

        @Test
        @DisplayName("a bool_prefix multi-match over the supplied fields, named")
        void normalBranchIsABoolPrefixMultiMatch() {
            String json = json(PrivacyLevel.NORMAL, TenantRef.of(LAB, "clinic-a"), "mar q");

            assertThat(json).contains("\"type\":\"bool_prefix\"");
            assertThat(json).contains(PLAINTEXT_FIELDS.toArray(String[]::new));
            assertThat(json).contains("\"_name\":\"" + BranchNames.IDENTIFIER + "\"");
            // The hashed fields belong to the other family; naming them here would probe fields
            // the NORMAL write path never fills.
            assertThat(json).doesNotContain(SUBJECT.tokensField(), SUBJECT.prefixesField());
        }

        @Test
        @DisplayName("the field list is the application's, and only the application's")
        void theToolkitInventsNoFieldNames() {
            SensitiveFieldQueries oneField =
                    SensitiveFieldQueries.plaintextOnly(SUBJECT, List.of("displayName"));

            String json = oneField.identifierBranch(PrivacyLevel.NORMAL, TenantRef.of(LAB, "clinic-a"), "mar")
                    .orElseThrow().toJsonString();

            assertThat(json).contains("displayName").doesNotContain("subjectName");
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", "   ", "--", "·"})
        @DisplayName("nothing to look for produces no branch, at either level")
        void nothingToLookForProducesNoBranch(String text) {
            // Empty means the caller issues no identifier clause — never that it falls back to a
            // broader search that would quietly match more.
            assertThat(queries.identifierBranch(PrivacyLevel.HIGH, HIGH, text)).isEmpty();
        }

        @Test
        @DisplayName("null text produces no branch rather than a null-pointer somewhere downstream")
        void nullTextProducesNoBranch() {
            assertThat(queries.identifierBranch(PrivacyLevel.HIGH, HIGH, null)).isEmpty();
            assertThat(queries.identifierBranch(PrivacyLevel.NORMAL, HIGH, null)).isEmpty();
        }

        @Test
        @DisplayName("punctuation alone canonicalizes away to nothing at NORMAL too")
        void normalBlankProducesNoBranch() {
            assertThat(queries.identifierBranch(PrivacyLevel.NORMAL, HIGH, "  ")).isEmpty();
        }

        @Test
        @DisplayName("a HIGH tenant with no blind indexer is refused, naming the tenant and the field but not the text")
        void aPlaintextOnlyDeploymentRefusesHighTenants() {
            SensitiveFieldQueries plaintextOnly =
                    SensitiveFieldQueries.plaintextOnly(SUBJECT, PLAINTEXT_FIELDS);

            // A plaintext query against the secure family matches nothing and looks exactly like a
            // tenant that holds no such value — the worst possible way to fail.
            assertThatIllegalStateException()
                    .isThrownBy(() -> plaintextOnly.identifierBranch(PrivacyLevel.HIGH, HIGH, "Quintanilla"))
                    .withMessageContaining(TENANT)
                    .withMessageContaining(SUBJECT.fieldName())
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContainIgnoringCase("quintanilla"));
        }

        @Test
        @DisplayName("an empty plaintext field list is refused at construction")
        void anEmptyFieldListIsRefused() {
            // A NORMAL branch over no fields matches nothing and reports no error — which is
            // indistinguishable from a tenant holding no such value.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SensitiveFieldQueries(SUBJECT, List.of(), blindIndexer))
                    .withMessageContaining(SUBJECT.fieldName());
        }

        @Test
        @DisplayName("a null spec, field list, level or tenant is refused")
        void nullsAreRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SensitiveFieldQueries(null, PLAINTEXT_FIELDS, blindIndexer));
            assertThatNullPointerException()
                    .isThrownBy(() -> new SensitiveFieldQueries(SUBJECT, null, blindIndexer));
            assertThatNullPointerException()
                    .isThrownBy(() -> queries.identifierBranch(null, HIGH, "quin"));
            assertThatNullPointerException()
                    .isThrownBy(() -> queries.identifierBranch(PrivacyLevel.HIGH, null, "quin"));
        }

        @Test
        @DisplayName("the spec is readable, so an application keeps one declaration of the field name")
        void theSpecIsExposed() {
            assertThat(queries.spec()).isEqualTo(SUBJECT);
        }
    }

    private String json(PrivacyLevel level, TenantRef tenant, String text) {
        Optional<Query> branch = queries.identifierBranch(level, tenant, text);
        assertThat(branch).as("identifier branch for '%s'", text).isPresent();
        return branch.orElseThrow().toJsonString();
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
