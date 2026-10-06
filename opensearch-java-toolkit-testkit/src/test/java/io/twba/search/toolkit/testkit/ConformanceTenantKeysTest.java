package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.crypto.KeyUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The fixture every domain module's conformance run will derive its key material from.
 *
 * <p>Two properties matter and neither is about cryptographic strength. First, <em>determinism with
 * separation</em>: a run tomorrow must derive the same keys as a run today, and two tenants must
 * never share a key — a fixture that handed every tenant the same bytes would let a conformance run
 * pass while one tenant's envelope opened under another's key, which is the single failure the
 * secure family exists to prevent.
 *
 * <p>Second, <em>failing closed indistinguishably</em>. A tenant this provider does not know must
 * raise from both accessors, and the refusal must not say whether the key was destroyed or never
 * provisioned. Those are the same event from inside the toolkit and must stay that way: a component
 * that could tell them apart could report "this tenant's data was erased" to someone who only asked
 * to read a record.
 */
class ConformanceTenantKeysTest {

    private static final String TENANT = "tenant-alpha";
    private static final String OTHER_TENANT = "tenant-beta";
    private static final String UNKNOWN_TENANT = "tenant-nobody";

    @Nested
    @DisplayName("derivation")
    class Derivation {

        @Test
        @DisplayName("the same tenant derives the same bytes every time it is asked")
        void sameTenantDerivesTheSameBytes() {
            ConformanceTenantKeys keys = ConformanceTenantKeys.forTenants(TENANT);

            SecretKey first = keys.kek(TENANT);
            SecretKey second = keys.kek(TENANT);

            assertThat(first.getEncoded()).isEqualTo(second.getEncoded());
        }

        @Test
        @DisplayName("two providers built the same way derive the same bytes")
        void derivationIsStableAcrossProviders() {
            // A run tomorrow must reproduce a run today: nothing here may depend on a random seed,
            // a clock, or the order tenants were named in.
            SecretKey fromOne = ConformanceTenantKeys.forTenants(TENANT, OTHER_TENANT).hmacKey(TENANT);
            SecretKey fromAnother = ConformanceTenantKeys.forTenants(OTHER_TENANT, TENANT).hmacKey(TENANT);

            assertThat(fromOne.getEncoded()).isEqualTo(fromAnother.getEncoded());
        }

        @Test
        @DisplayName("two tenants never share key bytes")
        void twoTenantsGetDifferentKeys() {
            ConformanceTenantKeys keys = ConformanceTenantKeys.forTenants(TENANT, OTHER_TENANT);

            assertThat(keys.kek(TENANT).getEncoded()).isNotEqualTo(keys.kek(OTHER_TENANT).getEncoded());
            assertThat(keys.hmacKey(TENANT).getEncoded()).isNotEqualTo(keys.hmacKey(OTHER_TENANT).getEncoded());
        }

        @Test
        @DisplayName("one tenant's wrapping key and hashing key are different bytes")
        void kekAndHmacKeyDifferForOneTenant() {
            // The salts exist only for this. One tenant whose two keys were identical would mean a
            // blind-index hash and an envelope wrapped under the same secret, so compromising either
            // would compromise both, and a conformance run would notice nothing.
            ConformanceTenantKeys keys = ConformanceTenantKeys.forTenants(TENANT);

            assertThat(keys.kek(TENANT).getEncoded())
                    .isNotEqualTo(keys.hmacKey(TENANT).getEncoded());
        }

        @Test
        @DisplayName("the keys carry the algorithms the cipher and the blind indexer need")
        void keysCarryUsableAlgorithms() {
            // Not decoration: SecretKeySpec's algorithm is what Cipher and Mac validate against, so
            // the wrong string here fails inside the crypto rather than here.
            ConformanceTenantKeys keys = ConformanceTenantKeys.forTenants(TENANT);

            assertThat(keys.kek(TENANT).getAlgorithm()).isEqualTo("AES");
            assertThat(keys.hmacKey(TENANT).getAlgorithm()).isEqualTo("HmacSHA256");
            assertThat(keys.kek(TENANT).getEncoded()).hasSize(32);
            assertThat(keys.hmacKey(TENANT).getEncoded()).hasSize(32);
        }
    }

    @Nested
    @DisplayName("failing closed")
    class FailingClosed {

        @Test
        @DisplayName("an unknown tenant raises from the wrapping-key accessor")
        void unknownTenantRaisesFromKek() {
            ConformanceTenantKeys keys = ConformanceTenantKeys.forTenants(TENANT);

            assertThatExceptionOfType(KeyUnavailableException.class)
                    .isThrownBy(() -> keys.kek(UNKNOWN_TENANT))
                    .satisfies(thrown -> assertThat(thrown.tenantId()).isEqualTo(UNKNOWN_TENANT));
        }

        @Test
        @DisplayName("an unknown tenant raises from the hashing-key accessor too")
        void unknownTenantRaisesFromHmacKey() {
            // Both accessors, separately. A provider that failed closed on one and returned bytes
            // from the other would write a document with a real hash and no envelope, which reads
            // back as a record that never had a value.
            ConformanceTenantKeys keys = ConformanceTenantKeys.forTenants(TENANT);

            assertThatExceptionOfType(KeyUnavailableException.class)
                    .isThrownBy(() -> keys.hmacKey(UNKNOWN_TENANT))
                    .satisfies(thrown -> assertThat(thrown.tenantId()).isEqualTo(UNKNOWN_TENANT));
        }

        @Test
        @DisplayName("a provider that knows nobody refuses everyone")
        void noTenantsMeansNoKeys() {
            ConformanceTenantKeys keys = ConformanceTenantKeys.forTenants();

            assertThatExceptionOfType(KeyUnavailableException.class).isThrownBy(() -> keys.kek(TENANT));
        }

        @ParameterizedTest(name = "a destroyed key and a key that never existed read alike: {0}")
        @ValueSource(strings = {"never-provisioned", "destroyed"})
        @DisplayName("the refusal never says why the key is missing")
        void refusalDoesNotSayWhy(String situation) {
            // The two situations are built differently and must produce the same message. This is
            // the assertion that erasure is not observable: an operator's "we destroyed this
            // tenant's key" must not be readable by anyone who merely asked for a record.
            ConformanceTenantKeys keys = "destroyed".equals(situation)
                    ? ConformanceTenantKeys.forTenants(TENANT, OTHER_TENANT).withoutKeysFor(OTHER_TENANT)
                    : ConformanceTenantKeys.forTenants(TENANT);

            assertThatExceptionOfType(KeyUnavailableException.class)
                    .isThrownBy(() -> keys.kek(OTHER_TENANT))
                    .withMessageContaining(OTHER_TENANT)
                    .satisfies(thrown -> assertThat(thrown.getMessage().toLowerCase())
                            .as("the message must not distinguish erasure from absence")
                            .doesNotContain("destroy", "erase", "revoke", "never", "provision"));
        }

        @Test
        @DisplayName("a null tenant id is refused by name rather than treated as unknown")
        void nullTenantIdIsRefusedByName() {
            ConformanceTenantKeys keys = ConformanceTenantKeys.forTenants(TENANT);

            assertThatNullPointerException().isThrownBy(() -> keys.kek(null))
                    .withMessageContaining("tenantId");
        }

        @Test
        @DisplayName("naming a null tenant at construction is refused immediately")
        void nullTenantAtConstructionIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ConformanceTenantKeys.forTenants(TENANT, null))
                    .withMessageContaining("tenantId");
        }
    }

    @Nested
    @DisplayName("taking key material away")
    class TakingKeysAway {

        @Test
        @DisplayName("withoutKeysFor removes exactly the named tenants and no others")
        void removesOnlyTheNamedTenants() {
            ConformanceTenantKeys all = ConformanceTenantKeys.forTenants(TENANT, OTHER_TENANT, UNKNOWN_TENANT);

            ConformanceTenantKeys reduced = all.withoutKeysFor(OTHER_TENANT);

            assertThat(reduced.kek(TENANT).getEncoded()).isNotEmpty();
            assertThat(reduced.kek(UNKNOWN_TENANT).getEncoded()).isNotEmpty();
            assertThatExceptionOfType(KeyUnavailableException.class)
                    .isThrownBy(() -> reduced.kek(OTHER_TENANT));
        }

        @Test
        @DisplayName("the provider it was derived from still knows the tenant")
        void theOriginalProviderIsUntouched() {
            // A conformance run holds both: one provider to seal with and one to read back with. If
            // withoutKeysFor mutated in place, the sealing side would lose the key too and the
            // fail-closed check would pass for the wrong reason.
            ConformanceTenantKeys all = ConformanceTenantKeys.forTenants(TENANT, OTHER_TENANT);

            ConformanceTenantKeys reduced = all.withoutKeysFor(OTHER_TENANT);

            assertThat(all.kek(OTHER_TENANT).getEncoded())
                    .as("the original must still hold the key the copy gave up")
                    .isNotEmpty();
            assertThatExceptionOfType(KeyUnavailableException.class)
                    .isThrownBy(() -> reduced.kek(OTHER_TENANT));
        }

        @Test
        @DisplayName("a tenant that survives withoutKeysFor derives the same bytes as before")
        void survivingTenantsKeepTheirBytes() {
            ConformanceTenantKeys all = ConformanceTenantKeys.forTenants(TENANT, OTHER_TENANT);

            ConformanceTenantKeys reduced = all.withoutKeysFor(OTHER_TENANT);

            assertThat(reduced.kek(TENANT).getEncoded()).isEqualTo(all.kek(TENANT).getEncoded());
        }

        @Test
        @DisplayName("removing a tenant that was never known changes nothing")
        void removingAnUnknownTenantIsANoOp() {
            ConformanceTenantKeys all = ConformanceTenantKeys.forTenants(TENANT);

            ConformanceTenantKeys reduced = all.withoutKeysFor(UNKNOWN_TENANT);

            assertThat(reduced.kek(TENANT).getEncoded()).isEqualTo(all.kek(TENANT).getEncoded());
        }
    }
}
