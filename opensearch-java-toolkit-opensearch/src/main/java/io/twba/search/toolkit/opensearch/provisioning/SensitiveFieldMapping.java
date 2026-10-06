package io.twba.search.toolkit.opensearch.provisioning;

import io.twba.search.toolkit.SecureFieldSpec;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.util.ObjectBuilder;

import java.util.Objects;
import java.util.function.Function;

/**
 * One sensitive field, and how the {@code NORMAL} family stores it.
 *
 * <p>Only the plaintext half is the application's to decide. A {@code search_as_you_type} field with
 * shingles, a plain {@code text}, a {@code keyword} — that is a relevance decision about the
 * application's own data, and the toolkit has no opinion on it. The {@code HIGH} half is not a
 * decision at all: the six derived components are dictated by the {@link SecureFieldSpec}, and an
 * application that could choose their mappings could choose one that breaks equality on a hash.
 *
 * <p>So this pairs a spec with exactly one contribution, and the installer reads the plaintext
 * property only when installing the {@code NORMAL} family and ignores it entirely for {@code HIGH}.
 * The secure template's guarantee — no plaintext demographics in this index — is then structural:
 * there is no code path that could put this property in the secure mapping.
 *
 * @param plaintext how the {@code NORMAL} family maps {@link SecureFieldSpec#fieldName()}
 */
public record SensitiveFieldMapping(SecureFieldSpec spec,
                                    Function<Property.Builder, ObjectBuilder<Property>> plaintext) {

    public SensitiveFieldMapping {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(plaintext, "plaintext");
    }

    public String fieldName() {
        return spec.fieldName();
    }
}
