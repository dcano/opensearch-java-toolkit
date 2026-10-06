package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.crypto.KeyUnavailableException;
import io.twba.search.toolkit.crypto.TenantKeyProvider;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic key material for conformance runs, and the means to take it away.
 *
 * <p>Keys are derived from the tenant id, so naming two tenants yields two genuinely different key
 * pairs with no fixture files to keep in step, and a run tomorrow derives the same keys as a run
 * today. The derivation is a repeated-byte fill of the tenant id: worthless cryptographically and
 * exactly right here, because what a conformance run asserts is that keys <em>differ per tenant</em>
 * and that an unknown tenant <em>fails closed</em> — never anything about key strength.
 *
 * <p><strong>Nothing below is, or resembles, a real key.</strong> It is test material for a container
 * that is discarded when the suite ends. A production {@link TenantKeyProvider} reads from a KMS or
 * an HSM, and the whole point of the port is that swapping this for that touches no caller.
 *
 * <p>The second half of this class is the more interesting one. {@link #withoutKeysFor} builds a
 * provider that knows every tenant except the named ones, which is how a conformance run models the
 * two situations the fail-closed rule exists for: a tenant that was never provisioned with key
 * material, and a tenant whose key has been destroyed to erase its data. Both look identical from
 * inside the toolkit, and both must raise rather than produce a blank value.
 */
public final class ConformanceTenantKeys implements TenantKeyProvider {

    private static final int KEY_BYTES = 32;         // AES-256 / HMAC-SHA256
    private static final String AES = "AES";
    private static final String HMAC = "HmacSHA256";

    /** Distinguishes the two keys derived for one tenant, so they are never the same bytes. */
    private static final String KEK_SALT = "kek:";
    private static final String HMAC_SALT = "hmac:";

    private final Set<String> known;

    private ConformanceTenantKeys(Set<String> known) {
        this.known = Set.copyOf(known);
    }

    /** Knows exactly the named tenants. Any other raises, which is the fail-closed posture. */
    public static ConformanceTenantKeys forTenants(String... tenantIds) {
        Set<String> known = new LinkedHashSet<>();
        for (String tenantId : tenantIds) {
            known.add(Objects.requireNonNull(tenantId, "tenantId"));
        }
        return new ConformanceTenantKeys(known);
    }

    /**
     * The same provider minus the named tenants — a key that was destroyed, or one that never
     * existed. The distinction is invisible from here on purpose: the toolkit must behave the same
     * either way, and a component that could tell them apart would be a component that could report
     * "this tenant's data was erased" to someone who only asked to read it.
     */
    public ConformanceTenantKeys withoutKeysFor(String... tenantIds) {
        Set<String> remaining = new LinkedHashSet<>(known);
        for (String tenantId : tenantIds) {
            remaining.remove(tenantId);
        }
        return new ConformanceTenantKeys(remaining);
    }

    @Override
    public SecretKey kek(String tenantId) {
        return new SecretKeySpec(material(tenantId, KEK_SALT), AES);
    }

    @Override
    public SecretKey hmacKey(String tenantId) {
        return new SecretKeySpec(material(tenantId, HMAC_SALT), HMAC);
    }

    private byte[] material(String tenantId, String salt) {
        if (!known.contains(Objects.requireNonNull(tenantId, "tenantId"))) {
            // Which key was wanted, and nothing about why it is missing — "destroyed" and "never
            // provisioned" must read the same from outside.
            throw new KeyUnavailableException(tenantId, "this conformance run holds no key material");
        }
        byte[] seed = (salt + tenantId).getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[KEY_BYTES];
        for (int i = 0; i < KEY_BYTES; i++) {
            key[i] = seed[i % seed.length];
        }
        return key;
    }
}
