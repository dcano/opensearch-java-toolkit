package io.twba.search.toolkit.crypto;

import javax.crypto.SecretKey;

/**
 * The only way to reach key material.
 *
 * <p>Nothing else in the toolkit reads keys from configuration, files or the environment. That
 * indirection is what lets a configuration-backed adapter be swapped for a KMS or an HSM without
 * touching a single caller, and it is what keeps key handling auditable: there is exactly one
 * place to look.
 *
 * <p>Both keys are per tenant, which is what makes the blind index tenant-isolated — the same
 * value held by two tenants hashes to two different values, so a probe computed with one tenant's
 * key cannot match another tenant's documents even if the tenant filter were dropped.
 *
 * <p>Keyed on the tenant id alone, not on a {@code TenantRef}: key material is a property of the
 * tenant, not of the domain its documents live in, and per-domain key material is deliberately
 * not a feature. One tenant's key opens that tenant's data wherever it is.
 *
 * <p>Implementations fail closed: an absent or unusable key raises {@link KeyUnavailableException}
 * rather than returning a default, a shared key, or null. A blank value in a search result would
 * be a privacy incident that looks like a bug; an exception is neither.
 */
public interface TenantKeyProvider {

    /**
     * The tenant's key encryption key, used to wrap and unwrap per-document data keys. Destroying
     * it is the cryptographic-erasure lever: the documents survive, the sealed values do not.
     */
    SecretKey kek(String tenantId);

    /**
     * The tenant's blind-index HMAC key. Rotating it invalidates every hash stored for the tenant,
     * which is why rotation is modelled as a reindex rather than an in-place swap.
     */
    SecretKey hmacKey(String tenantId);
}
