package io.twba.search.toolkit.opensearch.provisioning;

import io.twba.search.toolkit.SearchDomain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.Alias;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Pools exist before the first write, on both families, or the first write creates the index
 * itself — dynamically mapped, with no {@code dynamic: strict} and therefore no refusal to store a
 * readable sensitive value. That is why the secure pools are created whether or not the domain has
 * a {@code HIGH} tenant yet, and it is the first thing asserted here.
 *
 * <p>The second theme is idempotency, which is the difference between a startup that survives a
 * cluster it has already provisioned and one that does not. Every assertion is on what reached the
 * transport: which names were probed, which were created, and what aliases the creation carried.
 */
class PoolProvisionerTest {

    private static final SearchDomain CASE_FILES = new SearchDomain("case-files");
    private static final SearchDomain LEDGERS = new SearchDomain("ledgers");

    private static final int POOLS = 4;

    private final ProvisioningOpenSearchTransport transport = new ProvisioningOpenSearchTransport();
    private final OpenSearchClient client = new OpenSearchClient(transport);
    private final PoolProvisioner provisioner = new PoolProvisioner(client);

    // ------------------------------------------------------------------ both families

    @Nested
    @DisplayName("registering a domain provisions both families")
    class BothFamilies {

        @Test
        @DisplayName("four pools become four NORMAL and four HIGH backing indices, numbered from zero")
        void fourPoolsBecomeEightIndices() throws IOException {
            provisioner.provision(CASE_FILES, POOLS);

            assertThat(transport.createdIndices()).containsExactlyInAnyOrder(
                    "case-files-pool-0-000001",
                    "case-files-pool-1-000001",
                    "case-files-pool-2-000001",
                    "case-files-pool-3-000001",
                    "case-files-secure-pool-0-000001",
                    "case-files-secure-pool-1-000001",
                    "case-files-secure-pool-2-000001",
                    "case-files-secure-pool-3-000001");
        }

        @Test
        @DisplayName("the secure family is provisioned even though no HIGH tenant exists yet")
        void theSecureFamilyIsProvisionedUpFront() throws IOException {
            provisioner.provision(CASE_FILES, 1);

            // The one index in the system that must never be created by the write that lands on it:
            // a dynamically-mapped secure index has no strict mapping, so it accepts a readable name.
            assertThat(transport.createdIndices()).contains("case-files-secure-pool-0-000001");
        }

        @Test
        @DisplayName("each pool is one index behind a search alias and a write alias marked for writing")
        void eachPoolIsAnAliasTrio() throws IOException {
            provisioner.provision(CASE_FILES, 1);

            CreateIndexRequest normal = createOf("case-files-pool-0-000001");
            Map<String, Alias> aliases = normal.aliases();
            assertThat(aliases).containsOnlyKeys("case-files-pool-0", "case-files-pool-0-write");
            // Readers target the search alias so it can span generations; writers target the write
            // alias so rollover can move it. Without is_write_index the cluster refuses the write.
            assertThat(aliases.get("case-files-pool-0").isWriteIndex()).isNotEqualTo(true);
            assertThat(aliases.get("case-files-pool-0-write").isWriteIndex()).isTrue();
        }

        @Test
        @DisplayName("the secure pool's aliases wear the secure family's names")
        void theSecurePoolsAliasesAreInItsOwnFamily() throws IOException {
            provisioner.provision(CASE_FILES, 1);

            // A secure index behind a NORMAL alias is the single worst outcome available here: a
            // NORMAL-resolved writer would send readable values to it.
            assertThat(createOf("case-files-secure-pool-0-000001").aliases())
                    .containsOnlyKeys("case-files-secure-pool-0", "case-files-secure-pool-0-write");
        }

        @Test
        @DisplayName("a pool count of one creates pool zero only")
        void poolCountOneCreatesPoolZeroOnly() throws IOException {
            provisioner.provision(CASE_FILES, 1);

            // Numbered from zero, matching the range the catalog hashes tenants into. Off by one in
            // either direction places tenants in pools nothing created.
            assertThat(transport.createdIndices())
                    .containsExactlyInAnyOrder("case-files-pool-0-000001", "case-files-secure-pool-0-000001");
        }
    }

    // ------------------------------------------------------------------ idempotency

    @Nested
    @DisplayName("re-registration changes nothing")
    class Idempotency {

        @Test
        @DisplayName("a second startup against a provisioned cluster creates nothing and succeeds")
        void aSecondStartupCreatesNothing() throws IOException {
            provisioner.provision(CASE_FILES, POOLS);
            transport.asIfRestarted();

            provisioner.provision(CASE_FILES, POOLS);

            assertThat(transport.createdIndices()).isEmpty();
            // It still looked: eight probes, one per pool per family, and no creation.
            assertThat(transport.probedAliases()).hasSize(2 * POOLS);
        }

        @Test
        @DisplayName("a half-provisioned cluster gets exactly the missing pools")
        void onlyTheMissingPoolsAreCreated() throws IOException {
            // Three pools already provisioned, each as the cluster actually holds one: a backing
            // index behind both of its aliases. The fourth has never been created.
            transport.alreadyHolding(
                            "case-files-pool-0-000001",
                            "case-files-pool-1-000001",
                            "case-files-secure-pool-0-000001")
                    .alreadyHoldingAliases(
                            "case-files-pool-0", "case-files-pool-0-write",
                            "case-files-pool-1", "case-files-pool-1-write",
                            "case-files-secure-pool-0", "case-files-secure-pool-0-write");

            provisioner.provision(CASE_FILES, 2);

            assertThat(transport.createdIndices()).containsExactly("case-files-secure-pool-1-000001");
        }

        @Test
        @DisplayName("idempotency is decided by the write alias, not by the search alias or the index")
        void theProbeIsOnTheWriteAliasNotTheIndex() throws IOException {
            transport.alreadyHoldingAliases("case-files-pool-0", "case-files-pool-0-write");

            provisioner.provision(CASE_FILES, 1);

            // The write alias is the thing whose duplication is fatal — a second index claiming it
            // is what a cluster refuses — so it is the thing worth asking about. The search alias
            // would answer the same question today, but it is not the one that makes the create
            // fail, so pinning it would pin a coincidence.
            assertThat(transport.probedAliases())
                    .containsExactly("case-files-pool-0-write", "case-files-secure-pool-0-write");
            // And the first-generation index is not consulted at all: it is absent after a rollover
            // from a pool that is perfectly healthy.
            assertThat(transport.probedIndices()).isEmpty();
        }

        @Test
        @DisplayName("a pool that has rolled past generation one is left alone, and startup succeeds")
        void aRolledOverPoolIsLeftAlone() throws IOException {
            // The cluster after a rollover plus the lifecycle delete of generation one: both aliases
            // alive and pointing at generation two, and -000001 gone. This is the state that made
            // the first-generation probe wrong — it reports a healthy pool as missing, and the
            // re-creation then fails because the write alias already has a write index, so a cluster
            // that had run long enough to roll over could not be restarted.
            transport.asIfRolledOver("case-files-pool-0", "case-files-pool-0-write",
                            "case-files-pool-0-000002")
                    .asIfRolledOver("case-files-secure-pool-0", "case-files-secure-pool-0-write",
                            "case-files-secure-pool-0-000002");

            provisioner.provision(CASE_FILES, 1);

            assertThat(transport.createdIndices()).isEmpty();
        }

        @Test
        @DisplayName("a pool whose aliases are gone is provisioned, whatever indices happen to remain")
        void aPoolWithNoAliasesIsProvisioned() throws IOException {
            // The other half of the same decision. An orphaned index — a leftover from a deleted
            // pool, or a hand-made one — is not a provisioned pool: nothing writes to it, because
            // writers target the write alias, which does not exist.
            transport.alreadyHolding("case-files-pool-0-000001", "case-files-secure-pool-0-000001");

            provisioner.provision(CASE_FILES, 1);

            assertThat(transport.createdIndices()).containsExactlyInAnyOrder(
                    "case-files-pool-0-000001", "case-files-secure-pool-0-000001");
        }
    }

    // ------------------------------------------------------------------ domains do not disturb each other

    @Nested
    @DisplayName("a second domain leaves the first intact")
    class SecondDomain {

        @Test
        @DisplayName("provisioning a second domain touches only its own names")
        void theSecondDomainTouchesOnlyItsOwn() throws IOException {
            provisioner.provision(CASE_FILES, 2);
            transport.asIfRestarted();

            provisioner.provision(LEDGERS, 2);

            assertThat(transport.createdIndices()).allSatisfy(index ->
                    assertThat(index).startsWith("ledgers-"));
            assertThat(transport.probedAliases()).allSatisfy(alias ->
                    assertThat(alias).startsWith("ledgers-"));
            assertThat(transport.createdIndices()).containsExactlyInAnyOrder(
                    "ledgers-pool-0-000001", "ledgers-pool-1-000001",
                    "ledgers-secure-pool-0-000001", "ledgers-secure-pool-1-000001");
        }

        @Test
        @DisplayName("and re-registering the first afterwards still creates nothing")
        void theFirstDomainIsStillComplete() throws IOException {
            provisioner.provision(CASE_FILES, 2);
            provisioner.provision(LEDGERS, 2);
            transport.asIfRestarted();

            provisioner.provision(CASE_FILES, 2);

            assertThat(transport.createdIndices()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ argument checks

    @Nested
    @DisplayName("arguments that could not produce a usable pool range are refused")
    class ArgumentChecks {

        @ParameterizedTest(name = "pool count {0}")
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("a non-positive pool count is refused, naming the domain and the count")
        void nonPositivePoolCountsAreRefused(int poolCount) {
            // Zero is the dangerous one: it is a quiet no-op that leaves every write to create its
            // own index, and it is what an unset configuration property reads as.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> provisioner.provision(CASE_FILES, poolCount))
                    .withMessageContaining("case-files")
                    .withMessageContaining(String.valueOf(poolCount));
            assertThat(transport.requestCount()).isZero();
        }

        @Test
        @DisplayName("a null domain or client is refused")
        void nullsAreRefused() {
            assertThatNullPointerException().isThrownBy(() -> new PoolProvisioner(null));
            assertThatNullPointerException().isThrownBy(() -> provisioner.provision(null, 1));
        }
    }

    // ------------------------------------------------------------------ helpers

    private CreateIndexRequest createOf(String index) {
        List<CreateIndexRequest> matching = transport.createRequests().stream()
                .filter(request -> request.index().equals(index))
                .toList();
        assertThat(matching).as("creations of %s", index).hasSize(1);
        return matching.getFirst();
    }
}
