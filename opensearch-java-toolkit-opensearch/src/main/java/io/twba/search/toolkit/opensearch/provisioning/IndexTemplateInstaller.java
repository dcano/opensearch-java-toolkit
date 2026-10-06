package io.twba.search.toolkit.opensearch.provisioning;

import io.twba.search.toolkit.IndexNames;
import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SecureFieldSpec;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.DynamicMapping;
import org.opensearch.client.opensearch._types.mapping.KeywordProperty;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.util.ObjectBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Installs one family's composable index template.
 *
 * <p>One class for both families rather than two, because the two templates agree everywhere except
 * the sensitive fields, and written out twice they would agree on the day they were written and drift
 * on the first change. A tenant would then get a different analyzer depending on its privacy level,
 * and its search results would quietly disagree with another tenant's.
 *
 * <p>What the installer owns, and the application therefore cannot get wrong:
 *
 * <ul>
 *   <li><strong>The name and the pattern</strong>, derived from the domain and the level through
 *       {@link IndexNames}. A pattern that did not match the family is a template that silently never
 *       applies, and a new index created with dynamic mappings instead.</li>
 *   <li><strong>The priority arithmetic.</strong> Both patterns match a secure index name, and
 *       OpenSearch applies exactly one composable template — the highest priority wins.</li>
 *   <li><strong>{@code dynamic: strict}</strong>, set after the application's contribution so a
 *       contribution that relaxed it is overwritten rather than obeyed. On the secure family this is
 *       the mechanism that rejects a document carrying a plaintext sensitive value; on the plaintext
 *       family it catches typos.</li>
 *   <li><strong>The six derived properties per sensitive field</strong>, on the secure family only,
 *       and the plaintext property's <em>absence</em> there.</li>
 * </ul>
 *
 * <p>Idempotent by being a full-state PUT rather than a read-then-write: installing the same template
 * twice leaves the cluster in the same state, and the second call is not a no-op only because it did
 * not check first. A read-then-compare would have to decide what "the same" means across a cluster's
 * own normalization of settings and mappings, and getting that wrong means skipping an install that
 * was needed.
 */
public class IndexTemplateInstaller {

    private static final Logger log = LoggerFactory.getLogger(IndexTemplateInstaller.class);

    /**
     * The {@code NORMAL} family's priority. {@code HIGH} sits above it, and the gap is left wide so a
     * deployment can slot a template of its own between the two without renumbering these.
     */
    public static final int BASE_PRIORITY = 100;

    /** {@code HIGH}'s priority. Strictly greater than {@link #BASE_PRIORITY}; see {@link #priority}. */
    public static final int SECURE_PRIORITY = 200;

    private final OpenSearchClient client;
    private final SearchDomain domain;
    private final PrivacyLevel level;
    private final DomainMapping mapping;

    public IndexTemplateInstaller(OpenSearchClient client, SearchDomain domain, PrivacyLevel level,
                                  DomainMapping mapping) {
        this.client = Objects.requireNonNull(client, "client");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.level = Objects.requireNonNull(level, "level");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    /**
     * The priority for a level, and the one place the ordering between the families is decided.
     *
     * <p>The assertion is inside the switch's own arithmetic rather than in a comment: {@code HIGH}
     * is defined as strictly above {@code NORMAL}, and a future third level that tied with either
     * would be left to fail at the cluster, which rejects equal priorities on overlapping patterns as
     * a conflict. That refusal is the loud failure worth keeping — resolving a tie here, by any rule,
     * would mean one of two templates silently never applies.
     */
    public static int priority(PrivacyLevel level) {
        return switch (level) {
            case NORMAL -> BASE_PRIORITY;
            case HIGH -> SECURE_PRIORITY;
        };
    }

    public void install() throws IOException {
        String name = IndexNames.templateName(domain, level);
        String pattern = IndexNames.indexPattern(domain, level);
        client.indices().putIndexTemplate(t -> t
                .name(name)
                .indexPatterns(pattern)
                .priority(priority(level))
                .template(tpl -> tpl
                        .settings(s -> mapping.settings().apply(s))
                        .mappings(this::properties)));
        log.info("Installed index template {} for pattern {} at priority {}",
                name, pattern, priority(level));
    }

    private TypeMapping.Builder properties(TypeMapping.Builder m) {
        TypeMapping.Builder builder = mapping.sharedProperties().apply(m);
        for (SensitiveFieldMapping field : mapping.sensitiveFields()) {
            if (level == PrivacyLevel.HIGH) {
                secureProperties(builder, field.spec());
            } else {
                builder.properties(field.fieldName(), field.plaintext());
            }
        }
        // Last, so a contribution that set it loosely does not survive. A mapping that is strict
        // everywhere except where an application relaxed it is not a guarantee.
        return builder.dynamic(DynamicMapping.Strict);
    }

    /**
     * The six components of one sealed field.
     *
     * <p>The hash arrays are {@code keyword} so they are matched byte for byte and never analyzed. An
     * analyzer here would be actively harmful: hashes have no stems, no case and no diacritics to
     * fold, and any token splitting would break equality outright.
     *
     * <p>The four envelope components are carried, never searched, so neither indexed nor
     * doc-valued. They exist to be read back out of {@code _source} and opened.
     */
    private static void secureProperties(TypeMapping.Builder builder, SecureFieldSpec spec) {
        for (String hashed : spec.hashedFields()) {
            builder.properties(hashed, p -> p.keyword(k -> k));
        }
        for (String carried : spec.envelopeFields()) {
            builder.properties(carried, p -> p.keyword(carried()));
        }
        // Deliberately absent: spec.fieldName() itself. Strict mapping plus no property means a
        // document carrying a readable value is rejected by the cluster, not quietly dynamic-mapped.
    }

    /** A field the document carries but nothing ever queries, sorts or aggregates on. */
    private static Function<KeywordProperty.Builder, ObjectBuilder<KeywordProperty>> carried() {
        return k -> k.index(false).docValues(false);
    }
}
