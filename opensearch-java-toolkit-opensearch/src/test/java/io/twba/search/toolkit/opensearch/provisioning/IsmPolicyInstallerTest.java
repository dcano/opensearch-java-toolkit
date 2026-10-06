package io.twba.search.toolkit.opensearch.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.OpenSearchClientException;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Requests;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.twba.search.toolkit.PrivacyLevel.HIGH;
import static io.twba.search.toolkit.PrivacyLevel.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retention is the application's; the <em>pattern</em> is the toolkit's. This file is about that
 * split, because both halves fail silently when they are wrong.
 *
 * <p>A lifecycle policy whose {@code ism_template} matches nothing simply never runs: no rollover,
 * no error, and nobody notices until a shard is hundreds of gigabytes. A policy whose pattern
 * matches too much runs where it was never meant to, and its last state is {@code delete}. Neither
 * shows up in a smoke test, so the pattern is asserted here, byte for byte, out of the JSON that
 * would have gone on the wire.
 *
 * <p>The body is read back with a plain Jackson parse rather than by string matching: what must be
 * true is that the resulting <em>document</em> has the right shape and that the application's own
 * states survived untouched, not that some substring appears.
 */
class IsmPolicyInstallerTest {

    private static final SearchDomain CASE_FILES = new SearchDomain("case-files");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * An application's retention policy, in the shape the plugin expects. Obviously a fixture: two
     * states and a made-up size threshold. What matters is that every part of it is still present,
     * and unchanged, in what the installer sends.
     */
    private static final String POLICY_BODY = """
            {
              "policy": {
                "description": "roll over case-file pools and age out cold generations",
                "default_state": "hot",
                "states": [
                  {
                    "name": "hot",
                    "actions": [{ "rollover": { "min_size": "30gb", "min_index_age": "30d" } }],
                    "transitions": [{ "state_name": "cold", "conditions": { "min_index_age": "60d" } }]
                  },
                  { "name": "cold", "actions": [{ "delete": {} }], "transitions": [] }
                ]
              }
            }
            """;

    private final ProvisioningOpenSearchTransport transport = new ProvisioningOpenSearchTransport();
    private final OpenSearchClient client = new OpenSearchClient(transport);

    // ------------------------------------------------------------------ where it is PUT

    @Nested
    @DisplayName("the policy id and the endpoint are derived from the domain and the level")
    class Addressing {

        @Test
        @DisplayName("the NORMAL policy is PUT to /_plugins/_ism/policies/<domain>-lifecycle")
        void normalPolicyIsAddressedByItsDerivedId() throws IOException {
            install(NORMAL, POLICY_BODY);

            ProvisioningOpenSearchTransport.GenericCall call = transport.onlyPolicyPut();
            assertThat(call.method()).isEqualTo("PUT");
            assertThat(call.endpoint()).isEqualTo("/_plugins/_ism/policies/case-files-lifecycle");
        }

        @Test
        @DisplayName("the HIGH policy has its own id, so the two families' retention can diverge")
        void securePolicyIsAddressedSeparately() throws IOException {
            install(HIGH, POLICY_BODY);

            // Never a widened pattern over one shared document: retention on sealed data is decided
            // independently, and separate ids are what let it be edited independently.
            assertThat(transport.onlyPolicyPut().endpoint())
                    .isEqualTo("/_plugins/_ism/policies/case-files-secure-lifecycle");
        }

        @Test
        @DisplayName("a null client, domain, level or body is refused at construction")
        void nullsAreRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new IsmPolicyInstaller(null, CASE_FILES, NORMAL, POLICY_BODY));
            assertThatNullPointerException()
                    .isThrownBy(() -> new IsmPolicyInstaller(client, null, NORMAL, POLICY_BODY));
            assertThatNullPointerException()
                    .isThrownBy(() -> new IsmPolicyInstaller(client, CASE_FILES, null, POLICY_BODY));
            assertThatNullPointerException()
                    .isThrownBy(() -> new IsmPolicyInstaller(client, CASE_FILES, NORMAL, null));
        }
    }

    // ------------------------------------------------------------------ what the toolkit injects

    @Nested
    @DisplayName("the toolkit injects the pattern block and leaves everything else alone")
    class InjectedTemplate {

        @Test
        @DisplayName("the NORMAL policy attaches to <domain>-pool-* at the base priority")
        void normalPolicyAttachesToItsOwnPools() throws IOException {
            install(NORMAL, POLICY_BODY);

            JsonNode template = ismTemplate();
            assertThat(template.path("index_patterns").isArray()).isTrue();
            assertThat(template.path("index_patterns")).hasSize(1);
            assertThat(template.path("index_patterns").get(0).asText()).isEqualTo("case-files-pool-*");
            assertThat(template.path("priority").asInt()).isEqualTo(IndexTemplateInstaller.BASE_PRIORITY);
        }

        @Test
        @DisplayName("the HIGH policy attaches to <domain>-secure-pool-* at the secure priority")
        void securePolicyAttachesToItsOwnPools() throws IOException {
            install(HIGH, POLICY_BODY);

            JsonNode template = ismTemplate();
            assertThat(template.path("index_patterns").get(0).asText())
                    .isEqualTo("case-files-secure-pool-*");
            assertThat(template.path("priority").asInt()).isEqualTo(IndexTemplateInstaller.SECURE_PRIORITY);
        }

        @Test
        @DisplayName("the pool patterns are disjoint: the base policy cannot reach a secure index")
        void theTwoPoolPatternsAreDisjoint() throws IOException {
            install(NORMAL, POLICY_BODY);
            String basePattern = ismTemplate().path("index_patterns").get(0).asText();

            // Unlike the index-template patterns, these do not overlap, and must not: the last state
            // of a lifecycle policy is a delete, and a base policy reaching the sealed family would
            // apply the plaintext family's retention to data the regulator treats differently.
            assertThat(matchesGlob("case-files-secure-pool-0-000001", basePattern)).isFalse();
            assertThat(matchesGlob("case-files-pool-0-000001", basePattern)).isTrue();
            // Nor a dedicated tenant's index: that is not a pool generation and must not be rolled.
            assertThat(matchesGlob("case-files-tenant-big-000001", basePattern)).isFalse();
        }

        @ParameterizedTest(name = "{0} family")
        @EnumSource(PrivacyLevel.class)
        @DisplayName("the application's description, default state and states survive verbatim")
        void theApplicationsPolicyIsUnchanged(PrivacyLevel level) throws IOException {
            install(level, POLICY_BODY);

            JsonNode sent = policy();
            JsonNode original = JSON.readTree(POLICY_BODY).path("policy");
            assertThat(sent.path("description")).isEqualTo(original.path("description"));
            assertThat(sent.path("default_state")).isEqualTo(original.path("default_state"));
            // Deep equality on the states: an installer that re-serialized through a typed model
            // could drop an action it did not know about, and the policy would still install.
            assertThat(sent.path("states")).isEqualTo(original.path("states"));
            assertThat(fieldNames(sent))
                    .containsExactlyInAnyOrder("description", "default_state", "states", "ism_template");
        }

        @Test
        @DisplayName("the id the toolkit derived is the id it PUT to, and it appears nowhere in the body")
        void theBodyCarriesNoPolicyId() throws IOException {
            install(HIGH, POLICY_BODY);

            // The plugin takes the id from the URL. A body that also named it would be a second
            // place for the two to disagree.
            assertThat(transport.onlyPolicyPut().body()).doesNotContain("policy_id");
        }
    }

    // ------------------------------------------------------------------ what it refuses

    @Nested
    @DisplayName("a body the toolkit cannot own the pattern of is refused, and nothing is sent")
    class Refusals {

        @Test
        @DisplayName("a body that already declares ism_template is refused, naming the id and the block")
        void aBodyClaimingItsOwnPatternIsRefused() {
            String claiming = """
                    {
                      "policy": {
                        "description": "mine",
                        "default_state": "hot",
                        "ism_template": [{ "index_patterns": ["*"], "priority": 1 }],
                        "states": [{ "name": "hot", "actions": [], "transitions": [] }]
                      }
                    }
                    """;

            // Refused rather than overwritten: an application that believed it was choosing its own
            // pattern — here, one that matches every index in the cluster — has to hear about it.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> install(NORMAL, claiming))
                    .withMessageContaining("case-files-lifecycle")
                    .withMessageContaining("ism_template");
            assertThat(transport.requestCount()).as("requests sent after a refusal").isZero();
        }

        @ParameterizedTest(name = "body {0}")
        @ValueSource(strings = {
                "{}",                                  // an object, but no policy member
                "{\"policy\": \"hot\"}",               // policy present, but not an object
                "{\"policy\": [] }",                   // nor an array
                "[]",                                  // not an object at all
                "\"a policy\"",                        // a bare JSON string
                "   "                                  // blank: Jackson yields a missing node
        })
        @DisplayName("a body that is not an object with a policy member is refused, naming the id")
        void aBodyOfTheWrongShapeIsRefused(String malformed) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> install(NORMAL, malformed))
                    .withMessageContaining("case-files-lifecycle")
                    .withMessageContaining("policy");
            assertThat(transport.requestCount()).as("requests sent after a refusal").isZero();
        }

        @Test
        @DisplayName("a body that is not JSON at all raises before anything is sent")
        void aBodyThatIsNotJsonRaises() {
            // Jackson's own parse failure, which is an IOException and so already on install()'s
            // signature. What matters here is only that nothing reached the cluster: a spliced
            // string would have produced a policy the cluster rejects at startup instead.
            assertThatThrownBy(() -> install(NORMAL, "{ \"policy\": "))
                    .isInstanceOf(IOException.class);
            assertThat(transport.requestCount()).as("requests sent after a parse failure").isZero();
        }
    }

    // ------------------------------------------------------------------ the cluster's answer

    @Nested
    @DisplayName("a policy the cluster refuses is not reported as installed")
    class ClusterRefusals {

        @ParameterizedTest(name = "status {0}")
        @ValueSource(ints = {400, 500})
        @DisplayName("an error status raises rather than being logged as an install")
        void anErrorStatusRaises(int status) {
            transport.answeringWithStatus(status);

            // The generic client's default error predicate is `status -> false`, so without
            // throwOnHttpErrors a 400 from the ISM plugin — an unknown action, an unparseable
            // condition — comes back as an ordinary response and startup continues with no
            // lifecycle policy at all. Nothing rolls over, nothing errors, and nobody notices
            // until a shard is hundreds of gigabytes.
            assertThatThrownBy(() -> install(NORMAL, POLICY_BODY))
                    .isInstanceOf(OpenSearchClientException.class)
                    .hasMessageContaining(String.valueOf(status));
            // The PUT did go out: this is the cluster refusing it, not the toolkit.
            assertThat(transport.policyPuts()).hasSize(1);
            assertThat(transport.storedPolicy("case-files-lifecycle")).isEmpty();
        }

        @Test
        @DisplayName("a 200 still installs quietly, so the check costs the happy path nothing")
        void anAcceptedStatusDoesNotRaise() {
            // Without this the previous test passes for an installer that raises unconditionally.
            assertThatCode(() -> install(HIGH, POLICY_BODY)).doesNotThrowAnyException();
            assertThat(transport.onlyPolicyPut().endpoint())
                    .isEqualTo("/_plugins/_ism/policies/case-files-secure-lifecycle");
        }
    }

    // ------------------------------------------------------------------ idempotency

    /**
     * The ISM plugin versions a policy like any document: once one exists, a PUT must carry the
     * stored {@code if_seq_no} and {@code if_primary_term}, or it is a {@code 409}. That is what a
     * restart meets, and a real cluster is where it was first seen — this fake used to accept every
     * PUT, so a second install passed here and failed every restart against a cluster.
     */
    @Nested
    @DisplayName("installing over an existing policy updates it in place")
    class Idempotency {

        private static final String POLICY_ID = "case-files-lifecycle";

        @Test
        @DisplayName("a first install reads, finds nothing, and creates with no preconditions")
        void aFirstInstallSendsNoPreconditions() throws IOException {
            install(NORMAL, POLICY_BODY);

            assertThat(transport.genericCalls()).extracting(ProvisioningOpenSearchTransport.GenericCall::method)
                    .containsExactly("GET", "PUT");
            assertThat(transport.onlyPolicyPut().parameters())
                    .doesNotContainKeys("if_seq_no", "if_primary_term");
            assertThat(transport.storedPolicy(POLICY_ID)).isPresent();
        }

        @Test
        @DisplayName("a second install sends the stored version, and the cluster accepts it")
        void aSecondInstallSendsTheCurrentVersion() throws IOException {
            install(NORMAL, POLICY_BODY);
            ProvisioningOpenSearchTransport.StoredPolicy afterFirst = transport.storedPolicy(POLICY_ID).orElseThrow();
            transport.asIfRestarted();

            assertThatCode(() -> install(NORMAL, POLICY_BODY)).doesNotThrowAnyException();

            assertThat(transport.onlyPolicyPut().parameters())
                    .containsEntry("if_seq_no", String.valueOf(afterFirst.seqNo()))
                    .containsEntry("if_primary_term", String.valueOf(afterFirst.primaryTerm()));
        }

        @Test
        @DisplayName("an update replaces the stored body, so a changed retention reaches the cluster")
        void anUpdateReplacesTheBody() throws IOException {
            transport.alreadyHoldingPolicy(POLICY_ID, 7, 3);

            install(NORMAL, POLICY_BODY);

            assertThat(transport.onlyPolicyPut().parameters())
                    .containsEntry("if_seq_no", "7").containsEntry("if_primary_term", "3");
            ProvisioningOpenSearchTransport.StoredPolicy stored = transport.storedPolicy(POLICY_ID).orElseThrow();
            assertThat(JSON.readTree(stored.body()).path("policy").path("states"))
                    .isEqualTo(JSON.readTree(POLICY_BODY).path("policy").path("states"));
        }

        @Test
        @DisplayName("re-installing sends the same body each time")
        void reinstallingSendsTheSameBody() throws IOException {
            install(NORMAL, POLICY_BODY);
            install(NORMAL, POLICY_BODY);

            // Full-state replacement: what the second startup leaves is what the first left.
            List<ProvisioningOpenSearchTransport.GenericCall> puts = transport.policyPuts();
            assertThat(puts).hasSize(2);
            assertThat(puts.get(1).body()).isEqualTo(puts.get(0).body());
        }

        @Test
        @DisplayName("the fake refuses an unconditional PUT over an existing policy with 409, as the plugin does")
        void anUnconditionalOverwriteIsAConflict() {
            // The fixture's own fidelity, pinned: without it every test above could pass against an
            // installer that never sends a precondition.
            transport.alreadyHoldingPolicy(POLICY_ID, 0, 1);

            assertThatThrownBy(() -> client.generic()
                    .withClientOptions(OpenSearchGenericClient.ClientOptions.throwOnHttpErrors())
                    .execute(Requests.builder().endpoint("/_plugins/_ism/policies/" + POLICY_ID)
                            .method("PUT").json(POLICY_BODY).build()))
                    .isInstanceOf(OpenSearchClientException.class)
                    .hasMessageContaining("409");
        }

        @Test
        @DisplayName("a stale version is a conflict, and the install raises rather than overwriting")
        void aStaleVersionIsAConflict() {
            transport.alreadyHoldingPolicy(POLICY_ID, 4, 1);

            assertThatThrownBy(() -> client.generic()
                    .withClientOptions(OpenSearchGenericClient.ClientOptions.throwOnHttpErrors())
                    .execute(Requests.builder().endpoint("/_plugins/_ism/policies/" + POLICY_ID)
                            .method("PUT").query(Map.of("if_seq_no", "3", "if_primary_term", "1"))
                            .json(POLICY_BODY).build()))
                    .isInstanceOf(OpenSearchClientException.class)
                    .hasMessageContaining("409");
        }

        @ParameterizedTest(name = "status {0}")
        @ValueSource(ints = {401, 403, 500, 503})
        @DisplayName("a policy read that fails for any reason but 404 raises, and nothing is written")
        void aFailedReadRaises(int status) {
            // Reading an unreachable plugin as "no policy yet" would send a create against a
            // cluster in an unknown state.
            transport.failingPolicyReadsWith(status);

            assertThatThrownBy(() -> install(NORMAL, POLICY_BODY))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(POLICY_ID)
                    .hasMessageContaining(String.valueOf(status));
            assertThat(transport.policyPuts()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void install(PrivacyLevel level, String body) throws IOException {
        new IsmPolicyInstaller(client, CASE_FILES, level, body).install();
    }

    private JsonNode policy() throws IOException {
        return JSON.readTree(transport.onlyPolicyPut().body()).path("policy");
    }

    /** The single block the toolkit injected. One entry, always: two would be two patterns. */
    private JsonNode ismTemplate() throws IOException {
        JsonNode templates = policy().path("ism_template");
        assertThat(templates.isArray()).as("ism_template is an array").isTrue();
        assertThat(templates).as("ism_template entries").hasSize(1);
        return templates.get(0);
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static boolean matchesGlob(String name, String glob) {
        String regex = Arrays.stream(glob.split("\\*", -1))
                .map(java.util.regex.Pattern::quote)
                .reduce((left, right) -> left + ".*" + right)
                .orElseThrow();
        return name.matches(regex);
    }
}
