package io.twba.search.toolkit;

/**
 * How much of a tenant's sensitive data may be stored in readable form.
 *
 * <p>The level is the one decision everything downstream descends from: the index family, the
 * mapping, the write shape and the query shape. Keeping it in a single value, consulted from a
 * single place, is what stops two components from disagreeing — and the dangerous half of a
 * disagreement writes readable values into the family that promised to hold none.
 *
 * <p>Note what this level does <em>not</em> carry: an index prefix. It carries the <em>rule</em> —
 * {@code HIGH} decorates a domain's family with a {@code secure-} infix, and {@code NORMAL}
 * refuses to own anything wearing it. The prefix itself belongs to {@link SearchDomain}, which
 * is what lets a second application have its own families without the toolkit learning a single
 * index name.
 */
public enum PrivacyLevel {

    /** Sensitive values are stored readable and searched directly. */
    NORMAL(""),

    /**
     * Sensitive values never reach the cluster in readable form: sealed for retrieval, hashed
     * for lookup, in a family whose mapping defines no plaintext field at all.
     */
    HIGH(PrivacyLevel.SECURE_SEGMENT + "-");

    /**
     * The name segment that marks the {@code HIGH} family. Named once because three rules depend on
     * it agreeing: the family itself, the refusal of a domain name ending in it, and the refusal of a
     * tenant id starting with it.
     */
    static final String SECURE_SEGMENT = "secure";

    private final String infix;

    PrivacyLevel(String infix) {
        this.infix = infix;
    }

    /**
     * The index-name prefix this level owns within {@code domainName}.
     *
     * <p>Package-private on purpose: the only supported way to name a family is through a
     * {@link SearchDomain}. Every index name in the toolkit is therefore derived from an
     * application's domain rather than typed, which is the property that makes
     * "no index literal in toolkit code" checkable instead of aspirational.
     */
    String indexFamily(String domainName) {
        return domainName + "-" + infix;
    }

    /**
     * True if {@code target} belongs to this level's family within {@code domainName}, and to no
     * other level's.
     *
     * <p>The second clause is the load-bearing one. For a domain {@code d}, the target
     * {@code d-secure-pool-0} starts with {@code d-}, so a naive prefix test would hand a secure
     * index to the level that is allowed to write readable values into it.
     */
    boolean owns(String target, String domainName) {
        return target.startsWith(indexFamily(domainName))
                && (this == HIGH || !target.startsWith(HIGH.indexFamily(domainName)));
    }
}
