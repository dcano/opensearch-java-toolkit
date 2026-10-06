package io.twba.search.toolkit;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The address of one tenant's data within one application domain.
 *
 * <p>One value rather than two parameters. The risk this closes is not argument order — the types
 * differ, so the compiler would catch a swap — it is <em>drift</em>. A two-parameter signature can
 * quietly lose its domain parameter when a call site already "knows" which domain it is in, and
 * what is left is a lookup that crosses domains on a cluster where two applications share a
 * control plane. Carried as one value, the domain travels with the tenant into every catalog
 * lookup, index resolution, rate-limit budget and audit record; there is no partially applied
 * form to degrade into.
 *
 * <p>Application-facing ports keep a bare {@code String tenantId}. An application already knows
 * its own domain, and making a REST caller name it would push toolkit vocabulary into the API.
 * The domain is bound once, where the repository is built.
 *
 * <h2>Why a tenant id is validated here</h2>
 *
 * <p>A tenant id becomes part of an index name the moment the tenant is promoted to a dedicated
 * index, and promotion can happen at any time — so an id must be a safe name component from the
 * day it is first used, not from the day it is promoted. The rules follow from {@link IndexNames}:
 *
 * <ul>
 *   <li><strong>Lowercase alphanumeric words joined by single hyphens.</strong> Not lowercased on the
 *       caller's behalf: normalising {@code Clinic} and {@code clinic} to one id would merge two
 *       tenants' documents, which is a leak, not a convenience.</li>
 *   <li><strong>First segment is not {@code secure}.</strong> A {@code NORMAL} dedicated tenant named
 *       {@code secure-clinic} would be placed at {@code <domain>-secure-clinic}, inside the {@code HIGH}
 *       family, carrying readable values.</li>
 *   <li><strong>First segment is not {@code pool}.</strong> A dedicated tenant named {@code pool-2}
 *       would be placed at {@code <domain>-pool-2} — the shared pool itself, where anything treating
 *       the index as the tenant's own would act on every pooled tenant in it.</li>
 *   <li><strong>Last segment is not {@code write}, nor a rollover generation.</strong> Tenant
 *       {@code a-write}'s alias would be tenant {@code a}'s write alias; tenant {@code a-000001}'s
 *       alias would be tenant {@code a}'s backing index.</li>
 *   <li><strong>The longest name derived from it fits.</strong> Checked here, where both the domain
 *       and the id are known, rather than as a rejection from the cluster during a migration.</li>
 * </ul>
 *
 * <p>The implementation this generalizes accepted all of these. The difference is deliberate, and it
 * affects no tenant id that implementation's own tests use.
 */
public record TenantRef(SearchDomain domain, String tenantId) {

    private static final Pattern VALID_TENANT_ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public TenantRef {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(tenantId, "tenantId");
        refuseUnusableTenantId(domain, tenantId);
    }

    private static void refuseUnusableTenantId(SearchDomain domain, String tenantId) {
        if (tenantId.isBlank()) {
            throw unusable(tenantId, "it must not be blank");
        }
        if (!VALID_TENANT_ID.matcher(tenantId).matches()) {
            throw unusable(tenantId, "it must be lowercase alphanumeric words joined by single hyphens, "
                    + "and is not lowercased for you — two ids differing only in case would become one tenant");
        }
        String[] segments = tenantId.split("-");
        String first = segments[0];
        String last = segments[segments.length - 1];
        if (first.equals(PrivacyLevel.SECURE_SEGMENT)) {
            throw unusable(tenantId, "an id starting with '%s' would place a dedicated NORMAL tenant inside the HIGH family"
                    .formatted(PrivacyLevel.SECURE_SEGMENT));
        }
        if (first.equals(IndexNames.POOL_SEGMENT)) {
            throw unusable(tenantId, "an id starting with '%s' would name a dedicated index identical to a shared pool"
                    .formatted(IndexNames.POOL_SEGMENT));
        }
        if (last.equals(IndexNames.WRITE_SEGMENT)) {
            throw unusable(tenantId, "an id ending in '%s' would name an alias identical to another tenant's write alias"
                    .formatted(IndexNames.WRITE_SEGMENT));
        }
        if (IndexNames.GENERATION.matcher(last).matches()) {
            throw unusable(tenantId, "an id ending in a run of six or more digits would name an alias identical to "
                    + "another tenant's backing index");
        }
        // The longest name ever derived: the HIGH family, the id, and a generation suffix.
        String longest = IndexNames.firstIndex(domain.indexFamily(PrivacyLevel.HIGH) + tenantId);
        if (longest.getBytes(StandardCharsets.UTF_8).length > IndexNames.MAX_INDEX_NAME_BYTES) {
            throw unusable(tenantId, "names derived from it in domain '%s' would exceed %d bytes"
                    .formatted(domain, IndexNames.MAX_INDEX_NAME_BYTES));
        }
    }

    private static IllegalArgumentException unusable(String tenantId, String reason) {
        return new IllegalArgumentException("'%s' is not a usable tenant id: %s".formatted(tenantId, reason));
    }

    public static TenantRef of(SearchDomain domain, String tenantId) {
        return new TenantRef(domain, tenantId);
    }

    /** The domain's own name, for building index and alias names. */
    public String domainName() {
        return domain.name();
    }

    /** {@code <domain>/<tenant>} — this value is the subject of most error messages and audit records. */
    @Override
    public String toString() {
        return domain.name() + "/" + tenantId;
    }
}
