package io.twba.search.toolkit.opensearch;

import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.cp.TenantCatalog.Tier;

import java.util.Objects;

/**
 * The minimal placement the {@link TieredTenantIndexResolver} works from: tier and pool number, with
 * index names derived rather than stored. The catalog-backed model ({@code TenantCatalog.Placement})
 * stores its targets instead, which is what lets it express a migration in flight.
 */
public record TenantPlacement(TenantRef tenant, Tier tier, int poolNumber, PrivacyLevel privacyLevel) {

    public TenantPlacement {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(privacyLevel, "privacyLevel");
    }

    /** The common case: a {@code NORMAL} tenant in the plaintext family. */
    public TenantPlacement(TenantRef tenant, Tier tier, int poolNumber) {
        this(tenant, tier, poolNumber, PrivacyLevel.NORMAL);
    }
}
