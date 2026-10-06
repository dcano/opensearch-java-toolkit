package io.twba.search.toolkit.opensearch.controls;

import io.twba.search.toolkit.IdentifierProbeThrottledException;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.TenantRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The budget that turns "expensive to invert" into "slow enough that the audit trail catches it
 * first".
 *
 * <p>The prefix blind index answers "does this tenant hold a value starting with these four
 * characters?" — a question that costs the asker nothing and reveals a little each time. The control
 * that matters is therefore not stronger crypto but a limit on how fast the question can be asked,
 * and the two properties worth pinning are where the budget is drawn from and what happens when it
 * runs out.
 */
class IdentifierSearchLimiterTest {

    private static final SearchDomain LAB = new SearchDomain("lab-results");
    private static final SearchDomain CLAIMS = new SearchDomain("claims");

    private static final String TENANT_ID = "clinic-a";
    private static final TenantRef LAB_TENANT = TenantRef.of(LAB, TENANT_ID);
    private static final TenantRef LAB_OTHER_TENANT = TenantRef.of(LAB, "clinic-b");
    /** The same organisation in a second domain: different key material, different budget. */
    private static final TenantRef CLAIMS_TENANT = TenantRef.of(CLAIMS, TENANT_ID);

    private static final int LIMIT = 3;

    private final IdentifierSearchLimiter limiter = new IdentifierSearchLimiter(LIMIT, Duration.ofMinutes(1));

    @Nested
    @DisplayName("spending the budget")
    class Spending {

        @Test
        @DisplayName("the window allows exactly its budget, then refuses")
        void theBudgetIsSpentExactly() {
            for (int i = 0; i < LIMIT; i++) {
                int probe = i;
                assertThatCode(() -> limiter.claim(LAB_TENANT))
                        .as("probe %d of %d", probe + 1, LIMIT).doesNotThrowAnyException();
            }

            assertThatExceptionOfType(IdentifierProbeThrottledException.class)
                    .isThrownBy(() -> limiter.claim(LAB_TENANT));
        }

        @Test
        @DisplayName("the default budget is thirty probes a minute")
        void theDefaultBudgetIsGenerousButFinite() {
            IdentifierSearchLimiter defaults = IdentifierSearchLimiter.withDefaults();

            for (int i = 0; i < 30; i++) {
                defaults.claim(LAB_TENANT);
            }

            // Generous for a human looking values up, useless for walking the prefix space. Worth
            // pinning because it is the number that decides which of those two the control is.
            assertThatExceptionOfType(IdentifierProbeThrottledException.class)
                    .isThrownBy(() -> defaults.claim(LAB_TENANT));
        }

        @Test
        @DisplayName("the refusal names the tenant reference and the limit, and nothing about the search")
        void theRefusalCarriesNoQueryDetail() {
            for (int i = 0; i < LIMIT; i++) {
                limiter.claim(LAB_TENANT);
            }

            assertThatExceptionOfType(IdentifierProbeThrottledException.class)
                    .isThrownBy(() -> limiter.claim(LAB_TENANT))
                    .satisfies(error -> {
                        assertThat(error.tenant()).isEqualTo(LAB_TENANT);
                        assertThat(error.tenantId()).isEqualTo(TENANT_ID);
                        // domain/tenant, so an operator reading the message knows which of the
                        // tenant's two budgets ran out.
                        assertThat(error.getMessage()).contains(LAB.name(), TENANT_ID, String.valueOf(LIMIT));
                    });
        }

        @Test
        @DisplayName("a refused probe is refused immediately rather than queued")
        void refusalIsImmediate() {
            for (int i = 0; i < LIMIT; i++) {
                limiter.claim(LAB_TENANT);
            }

            // timeoutDuration is zero on purpose. With a waiting limiter this call would block for
            // most of the one-minute refresh period, so the bound below is three orders of
            // magnitude away from both outcomes and cannot flake on a slow machine.
            long startNanos = System.nanoTime();
            assertThatExceptionOfType(IdentifierProbeThrottledException.class)
                    .isThrownBy(() -> limiter.claim(LAB_TENANT));
            assertThat(Duration.ofNanos(System.nanoTime() - startNanos)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Nested
    @DisplayName("who shares a budget")
    class Scope {

        @Test
        @DisplayName("one tenant cannot spend another's budget")
        void budgetsArePerTenant() {
            exhaust(LAB_TENANT);

            // Global limiting would let one tenant's traffic throttle everyone else's users, which
            // is how a security control becomes an availability incident.
            assertThatCode(() -> limiter.claim(LAB_OTHER_TENANT)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the same tenant id in another domain has its own budget")
        void budgetsAreScopedPerDomainAndTenant() {
            exhaust(LAB_TENANT);

            // Deliberately the opposite of how BlindIndexer is keyed. Two domains hold two
            // unrelated value sets behind two unrelated keys, so probing one tells an attacker
            // nothing about the other — and keying on the bare id would let traffic in a low-value
            // domain exhaust the window protecting a high-value one.
            assertThatCode(() -> limiter.claim(CLAIMS_TENANT)).doesNotThrowAnyException();
        }

        private void exhaust(TenantRef tenant) {
            for (int i = 0; i < LIMIT; i++) {
                limiter.claim(tenant);
            }
            assertThatExceptionOfType(IdentifierProbeThrottledException.class)
                    .isThrownBy(() -> limiter.claim(tenant));
        }
    }

    @Nested
    @DisplayName("refusals at construction")
    class Construction {

        @ParameterizedTest(name = "limitForPeriod = {0}")
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("a budget below one is refused rather than silently refusing every search")
        void aBudgetBelowOneIsRefused(int limitForPeriod) {
            // A limiter configured to zero is not a strict control, it is an outage that looks like
            // a security feature, and it would be discovered in production.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new IdentifierSearchLimiter(limitForPeriod, Duration.ofMinutes(1)))
                    .withMessageContaining(String.valueOf(limitForPeriod));
        }

        @Test
        @DisplayName("a budget of exactly one is allowed")
        void oneIsAllowed() {
            assertThatCode(() -> new IdentifierSearchLimiter(1, Duration.ofMinutes(1)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a null period or tenant is refused")
        void nullsAreRefused() {
            assertThatNullPointerException().isThrownBy(() -> new IdentifierSearchLimiter(LIMIT, null));
            assertThatNullPointerException().isThrownBy(() -> limiter.claim(null));
        }
    }
}
