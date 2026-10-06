package io.twba.search.toolkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These six literals are the contract between three components that never call each other: the
 * template installer, the document mapper and the query builder. Pinning them here is what turns
 * "they agree by discipline" into "they agree or this fails".
 */
class SecureFieldSpecTest {

    private static final SecureFieldSpec PATIENT_NAME = new SecureFieldSpec("patientName");

    @Test
    @DisplayName("derives exactly the field names the kata's secure mapping uses today")
    void derivesTheKataFieldNames() {
        assertThat(PATIENT_NAME.tokensField()).isEqualTo("patientNameBidxTokens");
        assertThat(PATIENT_NAME.prefixesField()).isEqualTo("patientNameBidxPrefixes");
        assertThat(PATIENT_NAME.cipherField()).isEqualTo("patientNameCipher");
        assertThat(PATIENT_NAME.ivField()).isEqualTo("patientNameIv");
        assertThat(PATIENT_NAME.dekWrappedField()).isEqualTo("patientNameDekWrapped");
        assertThat(PATIENT_NAME.dekIvField()).isEqualTo("patientNameDekIv");
    }

    @Test
    @DisplayName("envelope fields are the four carried components, in write order")
    void envelopeFields() {
        assertThat(PATIENT_NAME.envelopeFields()).containsExactly(
                "patientNameCipher", "patientNameIv", "patientNameDekWrapped", "patientNameDekIv");
    }

    @Test
    @DisplayName("hashed fields are the two searchable components, and nothing else")
    void hashedFields() {
        assertThat(PATIENT_NAME.hashedFields())
                .containsExactly("patientNameBidxTokens", "patientNameBidxPrefixes");
    }

    @Test
    @DisplayName("derived fields are all six, with no duplicates and no plaintext name")
    void derivedFields() {
        assertThat(PATIENT_NAME.derivedFields())
                .hasSize(6)
                .doesNotHaveDuplicates()
                .containsAll(PATIENT_NAME.hashedFields())
                .containsAll(PATIENT_NAME.envelopeFields())
                .doesNotContain("patientName");
    }

    @Test
    @DisplayName("two specs occupy disjoint storage fields")
    void twoSpecsAreDisjoint() {
        SecureFieldSpec other = new SecureFieldSpec("guardianName");
        assertThat(PATIENT_NAME.derivedFields()).doesNotContainAnyElementsOf(other.derivedFields());
    }

    @Test
    @DisplayName("rejects a field name the derived components could not be built from")
    void rejectsUnusableFieldNames() {
        for (String bad : new String[] {"", "  ", "patient.name", "patient name"}) {
            assertThatThrownBy(() -> new SecureFieldSpec(bad))
                    .as("field name '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * {@code derivedNamesOf} is the single check two components depend on — the template installer
     * through {@code DomainMapping}, and the secure document mapper — so it is tested here rather
     * than twice over in the adapter module. Its value is that it refuses a list at the point the
     * fields were <em>declared</em>: the installer would never report a collision, because it would
     * simply map one property fewer than it was handed and look entirely successful.
     */
    @Nested
    @DisplayName("derivedNamesOf, over a list of specs")
    class DerivedNamesOfAList {

        @Test
        @DisplayName("returns every storage name of every spec, six each, and no declared name")
        void returnsEveryStorageName() {
            SecureFieldSpec guardian = new SecureFieldSpec("guardianName");

            Set<String> names = SecureFieldSpec.derivedNamesOf(List.of(PATIENT_NAME, guardian));

            assertThat(names)
                    .hasSize(12)
                    .containsAll(PATIENT_NAME.derivedFields())
                    .containsAll(guardian.derivedFields())
                    .doesNotContain("patientName", "guardianName");
        }

        @Test
        @DisplayName("an empty list occupies no storage names at all")
        void anEmptyListIsUsable() {
            // A domain with no sensitive fields is a supported configuration, not an error.
            assertThat(SecureFieldSpec.derivedNamesOf(List.of())).isEmpty();
        }

        @Test
        @DisplayName("the same field declared twice is refused, naming the field")
        void aRepeatedDeclarationIsRefused() {
            assertThatThrownBy(() -> SecureFieldSpec.derivedNamesOf(
                    List.of(PATIENT_NAME, new SecureFieldSpec("patientName"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("patientName");
        }

        @Test
        @DisplayName("two distinct fields deriving one storage name are refused, naming that name")
        void aDerivedCollisionIsRefused() {
            // The non-obvious case, and the reason this lives in one place: "value" derives
            // valueDekIv for its DEK's initialization vector, and "valueDek" derives valueDekIv for
            // its own. Two legitimate-looking field names, one storage field, and whichever is
            // written second wins — silently, in the direction that loses a sealed value.
            assertThatThrownBy(() -> SecureFieldSpec.derivedNamesOf(
                    List.of(new SecureFieldSpec("value"), new SecureFieldSpec("valueDek"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("valueDekIv");
        }

        @Test
        @DisplayName("the collision is refused whichever order the two are declared in")
        void theDerivedCollisionIsOrderIndependent() {
            // The check walks the list; a version that only compared each spec with its
            // predecessors would still catch this, but one that compared with its successors only
            // would pass for one of the two orders.
            assertThatThrownBy(() -> SecureFieldSpec.derivedNamesOf(
                    List.of(new SecureFieldSpec("valueDek"), new SecureFieldSpec("value"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("valueDekIv");
        }

        @Test
        @DisplayName("a declared field equal to another's derived name is refused, naming the field")
        void aDeclaredNameInsideAnothersStorageIsRefused() {
            // "a" derives aCipher. Declaring aCipher as a sensitive field of its own means one
            // name is a plaintext property on the NORMAL family and a ciphertext component on the
            // HIGH family, which is the one confusion this toolkit exists to make impossible.
            assertThatThrownBy(() -> SecureFieldSpec.derivedNamesOf(
                    List.of(new SecureFieldSpec("a"), new SecureFieldSpec("aCipher"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("aCipher");
        }

        @Test
        @DisplayName("that refusal holds when the derived name is declared first")
        void theDeclaredNameCollisionIsOrderIndependent() {
            assertThatThrownBy(() -> SecureFieldSpec.derivedNamesOf(
                    List.of(new SecureFieldSpec("aCipher"), new SecureFieldSpec("a"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("aCipher");
        }

        @Test
        @DisplayName("the returned set cannot be added to, and a null list is refused")
        void theResultIsItsOwn() {
            Set<String> names = SecureFieldSpec.derivedNamesOf(List.of(PATIENT_NAME));

            assertThatThrownBy(() -> names.add("somethingElse"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> SecureFieldSpec.derivedNamesOf(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
