package io.twba.search.toolkit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One sensitive field, declared once, in the terms every component needs it in.
 *
 * <p>The implementation this generalizes wrote the same six field names in three places: the
 * secure document record declared them as components, the secure template installer declared them
 * as mappings, and the query builder named two of them again. The specs said out loud that these
 * agree "by discipline" — an admission that nothing enforced it. A misnamed query field is the
 * worst kind of bug available here: it matches nothing and reports success.
 *
 * <p>So the names are derived, not typed. Three consumers read this one value — the template
 * contributes six properties, the mapper writes six values, the query builder probes the two
 * searchable ones — and they cannot disagree because there is nothing left to disagree about.
 *
 * <p>The six components, for a field named {@code f}:
 *
 * <ul>
 *   <li>{@code fBidxTokens} — one keyed hash per word, the equality lookup.</li>
 *   <li>{@code fBidxPrefixes} — one per 4–12 character prefix of each word, which is what keeps
 *       type-ahead alive over data the cluster cannot read.</li>
 *   <li>{@code fCipher}, {@code fIv}, {@code fDekWrapped}, {@code fDekIv} — the envelope: the
 *       sealed value and its wrapped data key, carried so a hit can be opened without a second
 *       lookup, and useless to anyone without the tenant's key encryption key.</li>
 * </ul>
 */
public record SecureFieldSpec(String fieldName) {

    private static final String TOKENS_SUFFIX = "BidxTokens";
    private static final String PREFIXES_SUFFIX = "BidxPrefixes";
    private static final String CIPHER_SUFFIX = "Cipher";
    private static final String IV_SUFFIX = "Iv";
    private static final String DEK_WRAPPED_SUFFIX = "DekWrapped";
    private static final String DEK_IV_SUFFIX = "DekIv";

    public SecureFieldSpec {
        Objects.requireNonNull(fieldName, "fieldName");
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
        // A dotted name would declare a sub-field of an object mapping, and the derived components
        // would land somewhere other than where the template puts them.
        if (fieldName.indexOf('.') >= 0 || fieldName.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "'%s' is not a usable field name: no dots and no whitespace".formatted(fieldName));
        }
    }

    /** Keyed hashes of the whole value's words. Searched for equality; never for ranking. */
    public String tokensField() {
        return fieldName + TOKENS_SUFFIX;
    }

    /** Keyed hashes of each word's prefixes. Searched only by the trailing token of a probe. */
    public String prefixesField() {
        return fieldName + PREFIXES_SUFFIX;
    }

    public String cipherField() {
        return fieldName + CIPHER_SUFFIX;
    }

    public String ivField() {
        return fieldName + IV_SUFFIX;
    }

    public String dekWrappedField() {
        return fieldName + DEK_WRAPPED_SUFFIX;
    }

    public String dekIvField() {
        return fieldName + DEK_IV_SUFFIX;
    }

    /**
     * The four carried components, in the order the mapper writes them. Neither indexed nor
     * doc-valued: they exist to be returned, not to be matched.
     */
    public List<String> envelopeFields() {
        return List.of(cipherField(), ivField(), dekWrappedField(), dekIvField());
    }

    /** The two searchable components. What a query may name, and the only thing it may name. */
    public List<String> hashedFields() {
        return List.of(tokensField(), prefixesField());
    }

    /**
     * Every storage name a list of specs occupies, refusing a list whose derived names collide.
     *
     * <p>Two <em>distinct</em> declared fields can still derive one storage name: a field called
     * {@code value} derives {@code valueDekIv} for its DEK's IV, and a field called {@code valueDek}
     * derives {@code valueDekIv} for its own IV. Distinct names, one storage field, and the second
     * one written wins.
     *
     * <p>Shared rather than checked in each consumer, because the consumers disagree about when they
     * would notice. A mapper catches it the first time a document is written; a template installer
     * would not catch it at all — it would simply install one property fewer than the fields it was
     * given and look entirely successful. Checked here, the refusal arrives where the fields were
     * declared, which is the only place the mistake can be corrected.
     */
    public static Set<String> derivedNamesOf(List<SecureFieldSpec> specs) {
        Objects.requireNonNull(specs, "specs");
        Set<String> derived = new LinkedHashSet<>();
        Set<String> declared = new LinkedHashSet<>();
        for (SecureFieldSpec spec : specs) {
            if (!declared.add(spec.fieldName())) {
                throw new IllegalArgumentException("field '%s' is declared twice".formatted(spec.fieldName()));
            }
            for (String name : spec.derivedFields()) {
                if (!derived.add(name)) {
                    throw new IllegalArgumentException(
                            "two sensitive fields both derive the storage field '%s'".formatted(name));
                }
            }
        }
        for (String name : declared) {
            if (derived.contains(name)) {
                throw new IllegalArgumentException(
                        "sensitive field '%s' collides with a storage field derived from another"
                                .formatted(name));
            }
        }
        return Set.copyOf(derived);
    }

    /**
     * Every storage field this spec occupies. The mapper checks an application's own field names
     * against this list, because a plain field colliding with a derived one would be overwritten
     * by the envelope — silently, and in the direction that loses the sealed value.
     */
    public List<String> derivedFields() {
        return List.of(tokensField(), prefixesField(),
                cipherField(), ivField(), dekWrappedField(), dekIvField());
    }
}
