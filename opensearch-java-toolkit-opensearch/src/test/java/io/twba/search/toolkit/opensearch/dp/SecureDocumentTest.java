package io.twba.search.toolkit.opensearch.dp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wrapper around a {@code HIGH} tenant's stored fields, and the two things about it that are not
 * mere plumbing.
 *
 * <p><strong>It must serialize as the map, not as an object containing one.</strong> A wrapper level
 * in the JSON would put every field one level below where the strict mapping defines it, so every
 * write to the secure family would be rejected — or worse, accepted into a dynamic object and stored
 * somewhere the query builder never looks.
 *
 * <p><strong>Its {@code toString} must reveal nothing.</strong> The values it carries are ciphertexts
 * and keyed hashes rather than plaintext, which is exactly why the temptation to print them in a
 * debug line is real. A ciphertext in a log outlives the index's retention policy and still
 * correlates two records; a stable keyed hash does the same, more cheaply.
 */
class SecureDocumentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Obviously fake, and distinctive enough that its appearance anywhere is unambiguous. */
    private static final String SENTINEL = "sealed-sentinel-Zx91";

    @Nested
    @DisplayName("serialization")
    class Serialization {

        /**
         * If this ever produces {@code {"fields":{...}}}, every secure write breaks against a strict
         * mapping — and the failure arrives from the cluster, far from this class.
         */
        @Test
        @DisplayName("serializes as the bare map, with no wrapper object around it")
        void serializesAsTheBareMap() throws Exception {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("tenantId", "tenant-high");
            stored.put("subjectNameBidxTokens", List.of("hash-one", "hash-two"));
            stored.put("subjectNameCipher", SENTINEL);

            String json = JSON.writeValueAsString(new SecureDocument(stored));

            // Parsed rather than compared as text: the copy the document holds is not
            // order-preserving, so a literal comparison would be asserting hash-map iteration order.
            assertThat(JSON.readValue(json, Map.class)).isEqualTo(stored);
            assertThat(json).doesNotContain("fields");
        }

        @Test
        @DisplayName("an empty document serializes as an empty object, not as null")
        void emptySerializesAsAnEmptyObject() throws Exception {
            assertThat(JSON.writeValueAsString(new SecureDocument(Map.of()))).isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("what it says about itself")
    class Disclosure {

        @Test
        @DisplayName("toString names no field and prints no value")
        void toStringRevealsNothing() {
            SecureDocument document = new SecureDocument(Map.of(
                    "subjectNameCipher", SENTINEL,
                    "subjectNameBidxTokens", List.of("hash-one")));

            assertThat(document.toString())
                    .doesNotContain(SENTINEL, "hash-one", "subjectNameCipher")
                    .contains("2");
        }
    }

    @Nested
    @DisplayName("what it holds")
    class Contents {

        /**
         * The map is copied, so an extractor that keeps a reference to the map it handed over cannot
         * add a plaintext field to a document after it was sealed.
         */
        @Test
        @DisplayName("later changes to the caller's map do not reach the document")
        void theMapIsCopied() {
            Map<String, Object> mutable = new LinkedHashMap<>();
            mutable.put("subjectNameCipher", SENTINEL);
            SecureDocument document = new SecureDocument(mutable);

            mutable.put("subjectName", "a plaintext value added afterwards");

            assertThat(document.has("subjectName")).isFalse();
            assertThat(document.fields()).hasSize(1);
        }

        @Test
        @DisplayName("the exposed field map cannot be modified in place")
        void theExposedMapIsUnmodifiable() {
            SecureDocument document = new SecureDocument(Map.of("subjectNameCipher", SENTINEL));

            assertThatThrownBy(() -> document.fields().put("subjectName", "a plaintext value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a mutable copy is a copy, not a view")
        void theMutableCopyIsDetached() {
            SecureDocument document = new SecureDocument(Map.of("subjectNameCipher", SENTINEL));

            Map<String, Object> copy = document.toMutableMap();
            copy.put("subjectName", "a plaintext value");

            assertThat(document.has("subjectName")).isFalse();
        }

        @Test
        @DisplayName("a null field map is refused")
        void nullFieldsAreRefused() {
            assertThatNullPointerException().isThrownBy(() -> new SecureDocument(null));
        }
    }
}
