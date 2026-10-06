package io.twba.search.toolkit.opensearch.dp;

/**
 * The names attached to the two branches of a search, so a response can say which one produced each
 * hit.
 *
 * <p>They travel to the cluster as {@code _name} on a query and come back as
 * {@code matched_queries} on every hit that clause matched. That round trip is the only way to get
 * per-hit provenance without re-running the branches client-side, and it is why the strings live in
 * one place: the value written into the request and the value compared against the response must be
 * the same, and nothing else enforces that.
 *
 * <p>Public, unlike the implementation this generalizes, because the toolkit no longer builds both
 * branches. The application builds its own content query over its own fields, and if it named that
 * branch something else its hits would come back with no content provenance — a result that looks
 * like "matched the identifier only" and is silently wrong. The toolkit builds the identifier
 * branch and names it itself.
 */
public final class BranchNames {

    /** Free-text fields — whatever the application made searchable, and the branch that can highlight. */
    public static final String CONTENT = "content";

    /** The sensitive identifier: the plaintext value for {@code NORMAL}, blind-index hashes for {@code HIGH}. */
    public static final String IDENTIFIER = "identifier";

    private BranchNames() {
    }
}
