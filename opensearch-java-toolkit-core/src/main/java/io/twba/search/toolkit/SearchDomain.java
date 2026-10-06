package io.twba.search.toolkit;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An application's document family, named once so it can be derived from everywhere.
 *
 * <p>This is the seam that makes the toolkit reusable. The implementation it generalizes had the
 * privacy level own the string {@code "lab-results-"}, so every index name, alias, template
 * pattern and lifecycle pattern in the stack descended from a constant that named one
 * application's documents. Here the application supplies the name and the privacy level only
 * decorates it, which means a second domain costs a {@code new SearchDomain("orders")} and
 * nothing else.
 *
 * <p>A domain is an identity, not a capacity plan: pool counts, shard counts and replica counts
 * are deployment configuration and are deliberately not here.
 */
public record SearchDomain(String name) {

    /**
     * Index names are lowercase and may not start with a hyphen or an underscore. The domain name
     * becomes a prefix of real index names, so a name that is illegal here fails at construction
     * rather than as a cluster rejection during provisioning.
     */
    private static final Pattern VALID_NAME = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public SearchDomain {
        Objects.requireNonNull(name, "name");
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    ("'%s' is not a usable domain name: it becomes an index-name prefix, so it must be "
                            + "lowercase alphanumeric words joined by single hyphens, starting with a letter")
                            .formatted(name));
        }
        // A domain named "d-secure" would have a NORMAL family byte-identical to the HIGH family
        // of a domain named "d": two families, one set of index names, each claiming to own the
        // other's indices — and one of the two may hold readable values. The
        // boundary between the family that may hold plaintext and the family that may not is the
        // one thing this toolkit refuses to let an application configure into a collision.
        //
        // This is a special case of a general rule — no two domains may have overlapping families —
        // which SearchDomains enforces over a declared set. It is kept here as well because it is
        // the one overlap that crosses the plaintext/sealed boundary, and it can be refused without
        // knowing which other domains exist.
        if (name.endsWith("-" + PrivacyLevel.SECURE_SEGMENT)) {
            throw new IllegalArgumentException(
                    ("'%s' is not a usable domain name: a name ending in '-%s' collides with the HIGH "
                            + "family of the domain it is derived from").formatted(name, PrivacyLevel.SECURE_SEGMENT));
        }
    }

    /** The index-name prefix this domain owns at {@code level} — {@code <name>-} or {@code <name>-secure-}. */
    public String indexFamily(PrivacyLevel level) {
        Objects.requireNonNull(level, "level");
        return level.indexFamily(name);
    }

    /** True if {@code target} is an index or alias belonging to this domain at exactly {@code level}. */
    public boolean owns(String target, PrivacyLevel level) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(level, "level");
        return level.owns(target, name);
    }

    /** The bare name, because a domain appears in error messages and audit records constantly. */
    @Override
    public String toString() {
        return name;
    }
}
