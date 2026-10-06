package io.twba.search.toolkit.opensearch;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.TenantRef;

import java.util.Optional;

/** Resolves where a tenant's data lives within a domain. The ONLY place topology is known. */
public interface TenantIndexResolver {

    /** Alias or index to write to. */
    String writeIndex(TenantRef tenant);

    /** Alias or index (or comma-joined list) to search. */
    String searchIndex(TenantRef tenant);

    /** Routing value, if this tenant lives in a routed shared pool. */
    Optional<String> routing(TenantRef tenant);

    /**
     * The tenant's privacy posture, which decides the <em>shape</em> of the documents written to the
     * targets above.
     *
     * <p>It is answered here, by the component that already answers "which index", precisely so the
     * two cannot disagree. A secure index accepts only sealed documents and a plaintext index has no
     * place to put a hash, so a caller that learned the family from one source and the shape from
     * another would eventually pick one of each — and the interesting half of that mistake writes
     * readable values into the family that promised to hold none.
     *
     * <p>There is deliberately no default implementation: {@code NORMAL} is the safe posture for a
     * new tenant but the wrong one to assume on behalf of a resolver that forgot to say.
     */
    PrivacyLevel privacyLevel(TenantRef tenant);
}
