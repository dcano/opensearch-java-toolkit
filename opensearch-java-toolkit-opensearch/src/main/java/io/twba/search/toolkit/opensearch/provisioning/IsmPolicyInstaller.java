package io.twba.search.toolkit.opensearch.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.twba.search.toolkit.IndexNames;
import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Installs one family's index lifecycle policy.
 *
 * <p>Retention is the application's decision and the toolkit has no business having an opinion on
 * it: how long a domain keeps its data, when it drops replicas, whether it force-merges at all, are
 * answers that come from a regulator or a cost model. So the policy body arrives as JSON from the
 * application.
 *
 * <p>What the toolkit does own is the part that is a <em>name</em>: the policy's id, and the
 * {@code ism_template} block that decides which indices the policy attaches itself to. Those are
 * derived from the domain and the level through {@link IndexNames}, and the block is injected rather
 * than accepted, because the failure mode of a wrong pattern is silent. A policy whose pattern
 * matches nothing never runs, and nobody notices until a shard is three hundred gigabytes; a policy
 * whose pattern matches too much runs somewhere it was never meant to, and its last state is
 * {@code delete}.
 *
 * <p>A separate policy per family, never a widened pattern. Retention on encrypted data is a decision
 * made independently of the plaintext family's — the posture that justifies the encryption is usually
 * the one that argues for keeping it less long — and separate ids are what let the two diverge without
 * editing a shared document.
 */
public class IsmPolicyInstaller {

    private static final Logger log = LoggerFactory.getLogger(IsmPolicyInstaller.class);

    private static final String POLICY_ENDPOINT = "/_plugins/_ism/policies/";
    private static final String POLICY = "policy";
    private static final String ISM_TEMPLATE = "ism_template";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenSearchClient client;
    private final SearchDomain domain;
    private final PrivacyLevel level;
    private final String policyBody;

    /**
     * @param policyBody the policy as JSON: an object with a {@code policy} member holding the
     *                   description, the default state and the states. It must <em>not</em> declare
     *                   {@code ism_template}; that block is the toolkit's, and a body carrying one is
     *                   refused rather than overwritten, so an application that believed it was
     *                   choosing its own pattern hears about it
     */
    public IsmPolicyInstaller(OpenSearchClient client, SearchDomain domain, PrivacyLevel level,
                              String policyBody) {
        this.client = Objects.requireNonNull(client, "client");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.level = Objects.requireNonNull(level, "level");
        this.policyBody = Objects.requireNonNull(policyBody, "policyBody");
    }

    /**
     * Installs the policy, or updates it in place if it already exists.
     *
     * <p>Not a blind PUT. The ISM plugin treats a PUT on an existing policy as an update, and refuses
     * an update that does not name the version it is replacing ({@code if_seq_no} and
     * {@code if_primary_term}) with a {@code 409}. So the installer reads the current version first and
     * updates against it. That makes a second startup converge on the declared body rather than fail —
     * which a blind PUT with error checking did, on every restart, and which a blind PUT without
     * error checking hid by logging the {@code 409} as if it were an install.
     *
     * <p>Updated rather than skipped when it exists, so a changed retention body reaches the cluster on
     * the next deploy. Skipping on "already there" would leave a policy that silently stopped matching
     * its source.
     */
    public void install() throws IOException {
        String policyId = IndexNames.lifecyclePolicyName(domain, level);
        String body = withIsmTemplate(policyId);
        Map<String, String> version = currentVersion(policyId);
        // throwOnHttpErrors, because the generic client's default predicate is `status -> false`: a
        // 400 from the ISM plugin — an unknown action, an unparseable condition — would otherwise be
        // logged as an install and startup would continue with no lifecycle policy at all. That is the
        // silent failure this class exists to prevent, arriving through the client instead of through
        // the pattern.
        try (Response response = client.generic()
                .withClientOptions(OpenSearchGenericClient.ClientOptions.throwOnHttpErrors())
                .execute(Requests.builder()
                        .endpoint(POLICY_ENDPOINT + policyId)
                        .method("PUT")
                        .query(version)
                        .json(body)
                        .build())) {
            log.info("{} ISM policy {} for pattern {}: status {}",
                    version.isEmpty() ? "Installed" : "Updated",
                    policyId, IndexNames.poolPattern(domain, level), response.getStatus());
        }
    }

    /**
     * The existing policy's version as update preconditions, or none when there is no policy yet.
     *
     * <p>Read with a client that does not throw, because a {@code 404} is an answer here rather than a
     * failure. Any other non-success status is a failure, raised rather than read as "absent": treating
     * an unreachable ISM plugin as "no policy yet" would send a create that then fails with a less
     * useful error, or — worse — succeed against a cluster in an unknown state.
     */
    private Map<String, String> currentVersion(String policyId) throws IOException {
        try (Response response = client.generic().execute(Requests.builder()
                .endpoint(POLICY_ENDPOINT + policyId)
                .method("GET")
                .build())) {
            int status = response.getStatus();
            if (status == 404) {
                return Map.of();
            }
            if (status >= 300) {
                throw new IOException("could not read ISM policy '%s' before installing it: status %d"
                        .formatted(policyId, status));
            }
            JsonNode current = JSON.readTree(response.getBody()
                    .orElseThrow(() -> new IOException(
                            "ISM policy '%s' exists but its GET returned no body".formatted(policyId)))
                    .bodyAsString());
            JsonNode seqNo = current.get("_seq_no");
            JsonNode primaryTerm = current.get("_primary_term");
            if (seqNo == null || primaryTerm == null) {
                throw new IOException(
                        "ISM policy '%s' exists but carries no _seq_no/_primary_term to update against"
                                .formatted(policyId));
            }
            return Map.of("if_seq_no", seqNo.asText(), "if_primary_term", primaryTerm.asText());
        }
    }

    /**
     * Parses the application's body and inserts the pattern block the toolkit owns.
     *
     * <p>Parsed rather than string-spliced. A body is a document with a shape, and the two things
     * worth refusing here — a body that is not an object with a {@code policy} member, and one that
     * already claims {@code ism_template} — are only visible once it has been read as one. String
     * concatenation would accept both and produce a policy the cluster rejects with a message about
     * JSON, at startup, some distance from the file that caused it.
     */
    private String withIsmTemplate(String policyId) throws IOException {
        JsonNode root = JSON.readTree(policyBody);
        JsonNode policy = root.path(POLICY);
        if (!root.isObject() || !policy.isObject()) {
            throw new IllegalArgumentException(
                    ("the lifecycle policy body for '%s' is not an object with a '%s' member; "
                            + "it cannot be installed as an ISM policy").formatted(policyId, POLICY));
        }
        if (policy.has(ISM_TEMPLATE)) {
            throw new IllegalArgumentException(
                    ("the lifecycle policy body for '%s' declares its own '%s': the index pattern a "
                            + "policy attaches to is derived from the domain and the privacy level, "
                            + "because a pattern that does not match the family produces a policy that "
                            + "silently never runs").formatted(policyId, ISM_TEMPLATE));
        }

        ObjectNode template = JSON.createObjectNode();
        template.putArray("index_patterns").add(IndexNames.poolPattern(domain, level));
        // The two families' pool patterns are disjoint, so this priority is not disambiguating them
        // the way the index templates' priorities do — it decides against a policy some deployment
        // adds later over the same pattern. It mirrors the template numbering so a reader comparing
        // the two finds one scheme rather than two.
        template.put("priority", IndexTemplateInstaller.priority(level));
        ((ObjectNode) policy).putArray(ISM_TEMPLATE).add(template);
        return JSON.writeValueAsString(root);
    }
}
