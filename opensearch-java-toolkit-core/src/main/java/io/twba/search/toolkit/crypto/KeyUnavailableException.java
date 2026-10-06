package io.twba.search.toolkit.crypto;

/**
 * No usable key exists for a tenant that requires one.
 *
 * <p>Thrown instead of falling back to plaintext, to an unkeyed hash, to a shared default key, or
 * to results with blank values. Every one of those fallbacks would turn a configuration mistake
 * into a silent privacy failure, so the operation stops here.
 *
 * <p>The message names the tenant and the key it wanted, and never contains key material — this
 * exception reaches logs and error responses.
 */
public class KeyUnavailableException extends RuntimeException {

    private final String tenantId;

    public KeyUnavailableException(String tenantId, String detail) {
        super("No key material available for tenant '%s': %s".formatted(tenantId, detail));
        this.tenantId = tenantId;
    }

    public KeyUnavailableException(String tenantId, String detail, Throwable cause) {
        super("No key material available for tenant '%s': %s".formatted(tenantId, detail), cause);
        this.tenantId = tenantId;
    }

    public String tenantId() {
        return tenantId;
    }
}
