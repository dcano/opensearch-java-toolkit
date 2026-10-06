package io.twba.search.toolkit;

import java.util.Objects;

/**
 * A tenant has run more sensitive-identifier searches than its window allows.
 *
 * <p>Not a capacity signal. The prefix blind index is an online guessing oracle: anyone who can
 * issue probes can walk the alphabet and learn which values a tenant holds, one keystroke's worth
 * at a time. Encryption does not stop that, because the attack never decrypts anything — it only
 * asks yes-or-no questions. A rate limit is what turns "expensive to invert" into "slow enough
 * that the audit trail catches it first".
 *
 * <p>A toolkit-core exception rather than an adapter one, because it is a decision callers must be
 * able to act on: a UI shows "try again in a moment", an HTTP layer answers 429. The message names
 * the tenant, its domain and the limit — and never the query, because what was being probed for
 * staying unrecorded is the entire point.
 */
public class IdentifierProbeThrottledException extends RuntimeException {

    private final TenantRef tenant;

    public IdentifierProbeThrottledException(TenantRef tenant, int limitForPeriod, String period) {
        super(("identifier searches for tenant '%s' are throttled: the limit of %d per %s "
                + "is exhausted for this window")
                .formatted(Objects.requireNonNull(tenant, "tenant"), limitForPeriod, period));
        this.tenant = tenant;
    }

    public TenantRef tenant() {
        return tenant;
    }

    public String tenantId() {
        return tenant.tenantId();
    }
}
