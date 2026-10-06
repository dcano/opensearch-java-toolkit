package io.twba.search.toolkit.crypto;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * A deterministic {@link TenantKeyProvider} for tests: keys derived from the tenant id, so a test
 * can name two tenants and get two genuinely different key pairs without fixtures.
 *
 * <p>Derivation is a repeated-byte fill of the tenant id, which is worthless cryptographically and
 * perfect here — the tests care that keys differ per tenant and stay stable across runs. The bytes
 * are obviously fake: nothing here is, or resembles, a real key.
 *
 * <p>The derivation is byte-for-byte the one the kata's own test fixture uses, and that is
 * load-bearing rather than incidental: the golden vectors in {@link CryptoGoldenVectorsTest} were
 * produced by the kata under these exact keys, so a change to the loop below turns every pinned
 * literal into noise.
 */
final class FixedTenantKeys implements TenantKeyProvider {

    private final Set<String> known;

    private FixedTenantKeys(Set<String> known) {
        this.known = known;
    }

    static FixedTenantKeys forTenants(String... tenantIds) {
        return new FixedTenantKeys(Set.of(tenantIds));
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
            throw new KeyUnavailableException(tenantId, "no test key configured");
        }
        byte[] seed = (tenantId + ':' + purpose).getBytes(StandardCharsets.UTF_8);
        byte[] material = new byte[32];
        for (int i = 0; i < material.length; i++) {
            material[i] = seed[i % seed.length];
        }
        return new SecretKeySpec(material, "AES");
    }
}
