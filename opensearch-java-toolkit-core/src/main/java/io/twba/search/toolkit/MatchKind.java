package io.twba.search.toolkit;

/**
 * What a hit matched on — the answer to "why is this result here?".
 *
 * <p>The presentation layer needs this because the two branches of a search behave very
 * differently in a UI. A content match can be shown with a highlighted snippet; an identifier
 * match cannot, and for a {@code HIGH} tenant it never will be, because the cluster matched a hash
 * and has no idea what text it stood for. Without provenance, an identifier-only hit arrives with
 * no score explanation and no snippet, looking to the user like a result that appeared for no
 * reason.
 *
 * <p>Engine-neutral on purpose. The adapter recovers it from named queries in the response, and
 * nothing above the port ever learns which search engine was involved.
 */
public enum MatchKind {

    /** The document's free text matched — whatever the application made searchable. */
    CONTENT,

    /**
     * A sensitive identifier matched: the plaintext value for a {@code NORMAL} tenant, a
     * blind-index hash for a {@code HIGH} one. Deliberately one value for both, because the
     * distinction is a storage decision and a caller is not entitled to infer a tenant's privacy
     * posture from a search response.
     */
    IDENTIFIER
}
