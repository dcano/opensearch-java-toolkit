package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.crypto.KeyUnavailableException;
import io.twba.search.toolkit.crypto.TenantKeyProvider;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * A deterministic {@link TenantKeyProvider} for this module's secure-document tests.
 *
 * <p>Keys are derived from the tenant id, so naming two tenants yields two genuinely different key
 * pairs with no fixture files, and a run tomorrow derives the same keys as a run today. The
 * derivation — a repeated-byte fill of the tenant id — is worthless cryptographically and exactly
 * right here: these tests assert that keys <em>differ per tenant</em> and that an unknown tenant
 * <em>fails closed</em>, never anything about key strength. Nothing below is, or resembles, a real
 * key.
 *
 * <p>The core module has an equivalent fixture, but it is package-private test code and pinned to
 * golden vectors produced elsewhere; this is a separate, deliberately independent copy rather than
 * a shared test jar, because coupling two modules' test fixtures is how a golden vector quietly
 * becomes a moving target.
 *
 * <p>A tenant absent from {@code known} raises {@link KeyUnavailableException} from both accessors,
 * which is what lets a test model two different situations with one class: a tenant that was never
 * provisioned, and a tenant whose key has been destroyed (build the document with a provider that
 * knows it, read it back with one that does not).
 */
final class DerivedTenantKeys implements TenantKeyProvider {

    private final Set<String> known;

    private DerivedTenantKeys(Set<String> known) {
        this.known = Set.copyOf(known);
    }

    static DerivedTenantKeys forTenants(String... tenantIds) {
        return new DerivedTenantKeys(Set.of(tenantIds));
    }

    @Override
    public SecretKey kek(String tenantId) {
        return keyFor(tenantId, "kek");
    }

    @Override
    public SecretKey hmacKey(String tenantId) {
        return keyFor(tenantId, "hmac");
    }

    private SecretKey keyFor(String tenantId, String purpose) {
        if (!known.contains(tenantId)) {
            throw new KeyUnavailableException(tenantId, "no test key is configured for this tenant");
        }
        byte[] seed = (tenantId + ':' + purpose).getBytes(StandardCharsets.UTF_8);
        byte[] material = new byte[32];
        for (int i = 0; i < material.length; i++) {
            material[i] = seed[i % seed.length];
        }
        return new SecretKeySpec(material, "AES");
    }
}
