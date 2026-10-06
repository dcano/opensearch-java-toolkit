package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.BlindIndexer;
import io.twba.search.toolkit.crypto.Canonicalizer;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TextQueryType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the identifier branch — the half of a search that looks for one sensitive field's value.
 *
 * <p>Two shapes, one signature. A {@code NORMAL} tenant's value is plaintext, so the branch is a
 * {@code bool_prefix} multi-match over the application's own fields. A {@code HIGH} tenant's value
 * is a set of keyed hashes, so the branch is a terms lookup the application assembles, because the
 * cluster cannot analyze what it cannot read.
 *
 * <p>The two halves of the construction come from opposite directions, and that is the point. The
 * hashed field names are <em>derived</em> from the {@link SecureFieldSpec} — the same object the
 * template and the mapper read, so a query cannot probe a field the write path does not fill. The
 * plaintext field names are <em>supplied</em>, because they are the application's own mapping
 * decision: which sub-fields exist, and whether a shingle family was configured at all, is
 * something only the application's template knows.
 *
 * <p>The {@code HIGH} shape is load-bearing in every part:
 *
 * <ul>
 *   <li><strong>{@code should}, not {@code must}.</strong> ANDing the per-token clauses makes a
 *       mixed query impossible: searching {@code "garcia hemolyzed"} would require a value token
 *       hashing to {@code "hemolyzed"}, so the branch would never fire and the document whose free
 *       text says "hemolyzed" would lose its identifier match entirely.</li>
 *   <li><strong>{@code constant_score} with a fixed boost.</strong> The score then reads as "how
 *       many tokens matched, times a constant" — deterministic and explainable. Left scored, Lucene
 *       would weigh these terms by inverse document frequency, and the IDF of a hash is a statement
 *       about how common a value is in the tenant, which is both meaningless as relevance and mildly
 *       disclosive.</li>
 *   <li><strong>Only the trailing token probes prefixes.</strong> Earlier tokens are finished words
 *       — the user typed a space after them — which is exactly how {@code bool_prefix} treats
 *       plaintext. Probing them all would widen every term in the query.</li>
 * </ul>
 */
public final class SensitiveFieldQueries {

    /**
     * One matched token is worth exactly one unit, whichever token it was. Rarity is not relevance
     * here: two people sharing a surname does not make it a weaker signal that this is the one the
     * user meant.
     */
    private static final float TOKEN_BOOST = 1.0f;

    private final SecureFieldSpec spec;
    private final List<String> plaintextFields;

    /** Null in a {@code NORMAL}-only deployment; {@link #blindBranch} turns that into a refusal. */
    private final BlindIndexer blindIndexer;

    /**
     * @param spec            the sensitive field, which supplies both hashed field names
     * @param plaintextFields the fields a {@code NORMAL} tenant's branch matches on — typically the
     *                        field itself plus its {@code search_as_you_type} sub-fields, in the
     *                        application's own mapping's terms
     */
    public SensitiveFieldQueries(SecureFieldSpec spec, List<String> plaintextFields, BlindIndexer blindIndexer) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.plaintextFields = List.copyOf(Objects.requireNonNull(plaintextFields, "plaintextFields"));
        if (this.plaintextFields.isEmpty()) {
            throw new IllegalArgumentException(
                    ("no plaintext fields given for '%s': a NORMAL tenant's identifier branch would "
                            + "match on nothing and report no error, which is indistinguishable from "
                            + "a tenant holding no such value").formatted(spec.fieldName()));
        }
        this.blindIndexer = blindIndexer;
    }

    /** For deployments with no {@code HIGH} tenants: asked for one, it refuses rather than guessing. */
    public static SensitiveFieldQueries plaintextOnly(SecureFieldSpec spec, List<String> plaintextFields) {
        return new SensitiveFieldQueries(spec, plaintextFields, null);
    }

    /** The field this builder probes. Applications read it to keep one declaration of the name. */
    public SecureFieldSpec spec() {
        return spec;
    }

    /**
     * @return the identifier branch for {@code text}, or empty when there is nothing to look for — a
     * blank query, or one that canonicalizes to no tokens at all. Empty means the caller issues no
     * identifier clause, never that it falls back to a broader search.
     */
    public Optional<Query> identifierBranch(PrivacyLevel level, TenantRef tenant, String text) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(tenant, "tenant");
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return switch (level) {
            case NORMAL -> Optional.of(plaintextBranch(text));
            case HIGH -> blindBranch(tenant, text);
        };
    }

    private Query plaintextBranch(String text) {
        return Query.of(q -> q.multiMatch(m -> m
                .query(text)
                .type(TextQueryType.BoolPrefix)
                .fields(plaintextFields)
                .queryName(BranchNames.IDENTIFIER)));
    }

    private Optional<Query> blindBranch(TenantRef tenant, String text) {
        if (blindIndexer == null) {
            // The tenant and its domain, never the text: the refusal says which configuration is
            // missing without recording what someone was looking for.
            throw new IllegalStateException(
                    ("tenant '%s' is HIGH privacy but no BlindIndexer is wired: refusing to search its "
                            + "documents with a plaintext query on '%s', which would match nothing and "
                            + "look exactly like a tenant holding no such value")
                            .formatted(tenant, spec.fieldName()));
        }
        List<String> tokens = Canonicalizer.tokens(text);
        if (tokens.isEmpty()) {
            return Optional.empty();
        }

        BoolQuery.Builder branch = new BoolQuery.Builder();
        for (int i = 0; i < tokens.size(); i++) {
            Query clause = tokenClause(tenant, tokens.get(i), i == tokens.size() - 1);
            branch.should(s -> s.constantScore(c -> c.filter(clause).boost(TOKEN_BOOST)));
        }
        return Optional.of(Query.of(q -> q.bool(branch
                .minimumShouldMatch("1")
                .queryName(BranchNames.IDENTIFIER)
                .build())));
    }

    /**
     * One token's clause: its exact hash, and — for the token still being typed — the prefix hash as
     * an alternative. Both live inside the caller's single {@code constant_score}, so a token that
     * matches both ways is still worth one unit and not two.
     */
    private Query tokenClause(TenantRef tenant, String token, boolean trailing) {
        // Derived through the same methods the write path used, which is the whole reason these
        // hashes agree with the ones in the index. Keyed on the bare tenant id, because key material
        // belongs to the tenant rather than to the domain it is being searched through.
        Query exact = term(spec.tokensField(), blindIndexer.tokenIndex(tenant.tenantId(), token).getFirst());
        if (!trailing) {
            return exact;
        }
        // Empty below the four-character floor: the term then matches complete values only, which is
        // the posture, not a gap.
        return blindIndexer.prefixProbe(tenant.tenantId(), token)
                .map(probe -> Query.of(q -> q.bool(b -> b
                        .should(exact)
                        .should(term(spec.prefixesField(), probe))
                        .minimumShouldMatch("1"))))
                .orElse(exact);
    }

    private static Query term(String field, String hash) {
        return Query.of(q -> q.term(t -> t.field(field).value(v -> v.stringValue(hash))));
    }
}
