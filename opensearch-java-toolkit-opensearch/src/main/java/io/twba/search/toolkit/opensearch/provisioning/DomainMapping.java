package io.twba.search.toolkit.opensearch.provisioning;

import io.twba.search.toolkit.SecureFieldSpec;
import io.twba.search.toolkit.TenantDocument;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.IndexSettings;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Everything an application contributes to its own index templates: the settings, the properties
 * every family shares, and how the {@code NORMAL} family stores each sensitive field.
 *
 * <p>The shape is chosen so the two families cannot drift. The implementation this generalizes wrote
 * its two templates separately and kept them in step by sharing one {@code commonProperties} method —
 * a good instinct that nothing enforced, because either template could have stopped calling it. Here
 * there is one contribution and one installer, and the families differ only where the installer makes
 * them differ: in the sensitive fields, which is the one region privacy is allowed to change.
 *
 * <p>{@code sharedProperties} must define every field the application writes <em>except</em> the
 * sensitive ones, including {@link TenantDocument#TENANT_ID_FIELD}, because the mapping is strict on
 * both families and a field with no property is rejected at write time. That rejection is the point
 * on the secure family — it is what makes "no plaintext value in this index" a property of the
 * cluster rather than a promise about the adapter — and it is a useful typo-catcher on the other.
 *
 * @param settings         shards, replicas, analyzers; identical on both families, so a tenant's
 *                         free-text search behaves the same whatever its privacy posture
 * @param sharedProperties every property except the sensitive fields; the installer adds
 *                         {@code dynamic: strict} itself, so a contribution that tried to relax it
 *                         would be overwritten rather than obeyed
 * @param sensitiveFields  the declared sensitive fields, in the order their properties are written
 */
public record DomainMapping(UnaryOperator<IndexSettings.Builder> settings,
                            UnaryOperator<TypeMapping.Builder> sharedProperties,
                            List<SensitiveFieldMapping> sensitiveFields) {

    public DomainMapping {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(sharedProperties, "sharedProperties");
        sensitiveFields = List.copyOf(Objects.requireNonNull(sensitiveFields, "sensitiveFields"));
        // Both a repeated field name and two distinct names that derive one storage name. The second
        // is the one worth catching here: nothing downstream would report it, because the installer
        // would map one property fewer than it was given and look entirely successful.
        SecureFieldSpec.derivedNamesOf(
                sensitiveFields.stream().map(SensitiveFieldMapping::spec).toList());
    }

    /** A domain with no sensitive fields at all. Its two families then differ only by name. */
    public static DomainMapping plaintextOnly(UnaryOperator<IndexSettings.Builder> settings,
                                              UnaryOperator<TypeMapping.Builder> sharedProperties) {
        return new DomainMapping(settings, sharedProperties, List.of());
    }

    /** The specs, for the mapper and the query builder, so one declaration reaches all three. */
    public List<SecureFieldSpec> specs() {
        return sensitiveFields.stream().map(SensitiveFieldMapping::spec).toList();
    }
}
