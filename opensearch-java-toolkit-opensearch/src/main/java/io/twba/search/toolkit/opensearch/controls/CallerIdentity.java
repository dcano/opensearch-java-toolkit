package io.twba.search.toolkit.opensearch.controls;

/**
 * Who is asking. Supplied per request, and the one piece of an audit record that cannot be derived
 * from the query itself.
 *
 * <p>A port because the answer depends on how the service is fronted: an authenticated principal, a
 * JWT subject, a service account, a batch job's name. The default is honest about a service with no
 * authentication rather than inventing a plausible-looking user id — an audit trail that says
 * {@code "admin"} for everyone is worse than one that admits it does not know, because only the
 * second gets fixed.
 *
 * <p>In production this is the authenticated principal, and the same identity should be the one the
 * security plugin sees, so the application's audit trail and the cluster's line up.
 *
 * <p>Domain-free on purpose: an identity belongs to a caller, not to a search domain. The domain of
 * the search being audited comes from the {@code TenantRef} the executor was handed.
 */
@FunctionalInterface
public interface CallerIdentity {

    String ANONYMOUS = "anonymous";

    /** The caller of the request being served, never null. */
    String current();

    /** The default for a service with no authentication in front of it. */
    static CallerIdentity anonymous() {
        return () -> ANONYMOUS;
    }
}
