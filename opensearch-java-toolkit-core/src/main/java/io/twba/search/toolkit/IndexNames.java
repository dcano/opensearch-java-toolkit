package io.twba.search.toolkit;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The grammar of every index and alias name the toolkit creates, in one place.
 *
 * <p>The implementation this generalizes built names by concatenation in five separate classes, and
 * the concatenation was ambiguous: a hyphen joins the parts, but a hyphen is also legal <em>inside</em>
 * the parts. A dedicated tenant named {@code pool-2} resolved to the shared pool 2 index. A tenant
 * named {@code secure-clinic} at {@code NORMAL} privacy resolved into the {@code HIGH} family. Nothing
 * failed; the names were simply the same names.
 *
 * <p>The grammar, for a family {@code F} — {@code <domain>-} or {@code <domain>-secure-}:
 *
 * <pre>
 *   F pool-&lt;n&gt;              shared pool alias          F &lt;tenant&gt;              dedicated alias
 *   F pool-&lt;n&gt;-write        its write alias            F &lt;tenant&gt;-write        its write alias
 *   F pool-&lt;n&gt;-000001       its first backing index    F &lt;tenant&gt;-000001       its first backing index
 * </pre>
 *
 * <p>Every derived name is therefore unambiguous exactly when a tenant id cannot begin with
 * {@code secure} or {@code pool} and cannot end with {@code write} or a rollover generation. Those
 * rules are enforced by {@link TenantRef}, which reads them from this class — so adding a suffix here
 * and forgetting to reserve it is not possible without editing the constants the rules are built from.
 */
public final class IndexNames {

    /** Leading segment of a shared pool's name. Reserved: no tenant id may start with it. */
    static final String POOL_SEGMENT = "pool";

    /** Trailing segment of a write alias. Reserved: no tenant id may end with it. */
    static final String WRITE_SEGMENT = "write";

    /** The first rollover generation. Later ones increment it, so the reserved shape is any such run. */
    static final String FIRST_GENERATION = "000001";

    /** A rollover generation suffix: six or more digits. Reserved as a tenant id's trailing segment. */
    static final Pattern GENERATION = Pattern.compile("\\d{" + FIRST_GENERATION.length() + ",}");

    /** Trailing segment of a composable index template's name. Not an index name, so not reserved. */
    static final String TEMPLATE_SEGMENT = "template";

    /** Trailing segment of a lifecycle policy's id. Not an index name, so not reserved. */
    static final String LIFECYCLE_SEGMENT = "lifecycle";

    /** OpenSearch rejects index names longer than this many bytes. */
    static final int MAX_INDEX_NAME_BYTES = 255;

    private IndexNames() {
    }

    /** {@code <family>pool-<n>} — the search alias of shared pool {@code n}. */
    public static String poolAlias(SearchDomain domain, PrivacyLevel level, int poolNumber) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(level, "level");
        if (poolNumber < 0) {
            throw new IllegalArgumentException("pool number must not be negative, was " + poolNumber);
        }
        return domain.indexFamily(level) + POOL_SEGMENT + "-" + poolNumber;
    }

    /** {@code <family><tenant>} — the search alias of a dedicated tenant's own index. */
    public static String tenantAlias(TenantRef tenant, PrivacyLevel level) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(level, "level");
        return tenant.domain().indexFamily(level) + tenant.tenantId();
    }

    /** {@code <alias>-write} — the alias rollover advances and writers target. */
    public static String writeAlias(String alias) {
        return Objects.requireNonNull(alias, "alias") + "-" + WRITE_SEGMENT;
    }

    /** {@code <alias>-000001} — the first concrete index behind an alias. */
    public static String firstIndex(String alias) {
        return Objects.requireNonNull(alias, "alias") + "-" + FIRST_GENERATION;
    }

    /**
     * {@code <family>*} — the index pattern a family's composable template claims.
     *
     * <p>Note that the {@code NORMAL} pattern also matches every {@code HIGH} index, because the
     * secure family is a longer prefix of the same shape. That overlap is deliberate and is resolved
     * by template priority, not by a cleverer pattern: OpenSearch applies exactly one composable
     * template to a new index, the highest priority wins, and equal priorities are rejected as a
     * conflict. A pattern narrow enough to exclude the secure family would be one more place that has
     * to agree with the family grammar, and the failure mode of getting it wrong is a secure index
     * created with a plaintext mapping.
     */
    public static String indexPattern(SearchDomain domain, PrivacyLevel level) {
        return family(domain, level) + "*";
    }

    /**
     * {@code <family>pool-*} — the pattern a lifecycle policy attaches itself to.
     *
     * <p>Narrower than {@link #indexPattern} on purpose: lifecycle actions roll over and eventually
     * delete, and a dedicated tenant's index is not a pool generation. The {@code NORMAL} pattern
     * does not match the {@code HIGH} family here, because {@code secure} sits between the family and
     * the {@code pool} segment — which is why each family needs a policy of its own rather than
     * inheriting one.
     */
    public static String poolPattern(SearchDomain domain, PrivacyLevel level) {
        return family(domain, level) + POOL_SEGMENT + "-*";
    }

    /** {@code <family>template} — the composable index template's name. */
    public static String templateName(SearchDomain domain, PrivacyLevel level) {
        return family(domain, level) + TEMPLATE_SEGMENT;
    }

    /** {@code <family>lifecycle} — the ISM policy's id. */
    public static String lifecyclePolicyName(SearchDomain domain, PrivacyLevel level) {
        return family(domain, level) + LIFECYCLE_SEGMENT;
    }

    private static String family(SearchDomain domain, PrivacyLevel level) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(level, "level");
        return domain.indexFamily(level);
    }
}
