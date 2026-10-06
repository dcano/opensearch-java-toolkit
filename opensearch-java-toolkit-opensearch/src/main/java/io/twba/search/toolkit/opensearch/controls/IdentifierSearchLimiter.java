package io.twba.search.toolkit.opensearch.controls;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.twba.search.toolkit.IdentifierProbeThrottledException;
import io.twba.search.toolkit.TenantRef;

import java.time.Duration;
import java.util.Objects;

/**
 * A per-tenant budget for sensitive-identifier searches.
 *
 * <p>The prefix blind index answers "does this tenant hold a value starting with these four
 * characters?" — a yes-or-no question that costs the asker nothing and reveals a little each time.
 * Ten thousand of them reconstruct a value list without decrypting anything, so the control that
 * matters is not stronger crypto but a limit on how fast the question can be asked. Together with
 * the audit trail, an inversion attempt becomes slow and loud.
 *
 * <p>Per tenant, not global and not per caller. Global would let one tenant's traffic throttle
 * another's, and per caller is exactly the axis an attacker rotates. A tenant's own users share a
 * budget that comfortably exceeds normal use and falls far short of a search of the value space.
 *
 * <p><strong>Per tenant means per {@link TenantRef}, not per tenant id.</strong> The same
 * organisation appearing in two domains holds two unrelated sets of values behind two unrelated
 * keys, so probing one tells an attacker nothing about the other and there is no reason for one to
 * spend the other's budget. The reverse mistake is the dangerous one: keying on the bare id would
 * let traffic in a low-value domain exhaust the window protecting a high-value one, and the tenant
 * would experience the control as an outage rather than as a limit.
 *
 * <p>{@code timeoutDuration} is zero on purpose: a search that would have to wait is refused
 * outright rather than queued. Queueing turns a security limit into latency, and latency is
 * something operators tune away.
 */
public class IdentifierSearchLimiter {

    /** Generous for a human looking values up, useless for walking the prefix space. */
    private static final int DEFAULT_LIMIT_FOR_PERIOD = 30;
    private static final Duration DEFAULT_PERIOD = Duration.ofMinutes(1);

    private final RateLimiterRegistry registry;
    private final int limitForPeriod;
    private final String period;

    public IdentifierSearchLimiter(int limitForPeriod, Duration period) {
        if (limitForPeriod < 1) {
            throw new IllegalArgumentException(
                    "limitForPeriod must be at least 1: a budget of %d refuses every search"
                            .formatted(limitForPeriod));
        }
        this.limitForPeriod = limitForPeriod;
        this.period = Objects.requireNonNull(period, "period").toString();
        this.registry = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(period)
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    public static IdentifierSearchLimiter withDefaults() {
        return new IdentifierSearchLimiter(DEFAULT_LIMIT_FOR_PERIOD, DEFAULT_PERIOD);
    }

    /**
     * Claims one probe for {@code tenant}.
     *
     * <p>Limiters are created on first use and keyed by {@code domain/tenant}, which is bounded by
     * the number of tenants across declared domains rather than by traffic. The key comes from
     * {@link TenantRef#toString()}, whose two segments cannot be confused with one another because
     * neither a domain name nor a tenant id may contain a slash.
     *
     * @throws IdentifierProbeThrottledException when the tenant's window is exhausted
     */
    public void claim(TenantRef tenant) {
        Objects.requireNonNull(tenant, "tenant");
        RateLimiter limiter = registry.rateLimiter(tenant.toString());
        if (!limiter.acquirePermission()) {
            throw new IdentifierProbeThrottledException(tenant, limitForPeriod, period);
        }
    }
}
