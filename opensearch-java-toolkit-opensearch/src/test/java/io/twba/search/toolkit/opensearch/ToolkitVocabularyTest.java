package io.twba.search.toolkit.opensearch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The toolkit is reusable only while it names no application's vocabulary, and prose does not
 * enforce that — the first time someone needs "just one" field name, the module stops being a
 * toolkit and becomes the lab-results search it was extracted from.
 *
 * <p>Compiled classes are scanned rather than sources, deliberately. Javadoc and comments in these
 * modules <em>do</em> mention the kata's field names, as illustrations of what a spec-driven field
 * is for, and that is fine: a comment cannot couple a build. What must be absent is a name in the
 * code — a constant, a field, a method, a class — and javac strips comments, so what survives into
 * {@code target/classes} is exactly the set this rule is about.
 *
 * <p>The one field the toolkit does name is {@code tenantId}, because it is the field the toolkit
 * itself enforces. Everything else about a document's shape is the application's.
 */
class ToolkitVocabularyTest {

    /**
     * Both toolkit modules. The sibling path is resolved from this module's directory, which
     * surefire makes the working directory; if the layout moves the test fails rather than
     * silently scanning nothing.
     */
    private static final List<Path> TOOLKIT_CLASSES = List.of(
            Path.of("target", "classes"),
            Path.of("..", "opensearch-java-toolkit-core", "target", "classes"));

    /**
     * The kata's own vocabulary — types and field names from the application this toolkit was
     * generalized out of. Any of them appearing in a compiled toolkit class means the extraction
     * leaked back.
     */
    private static final List<String> APPLICATION_VOCABULARY = List.of(
            "LabResult", "labResult", "labresult",
            "patientName", "PatientName",
            "accessionNumber", "testCode", "hemolyzed", "specimen");

    @Test
    @DisplayName("no compiled toolkit class names an application type or field")
    void theToolkitNamesNoApplicationField() {
        List<Path> classFiles = classFiles();
        assertThat(classFiles).as("compiled toolkit classes to scan").isNotEmpty();

        for (Path classFile : classFiles) {
            String bytecode = read(classFile);
            assertThat(APPLICATION_VOCABULARY)
                    .as("application vocabulary found in %s", classFile)
                    .allSatisfy(word -> assertThat(bytecode).doesNotContain(word));
        }
    }

    @Test
    @DisplayName("the one field name the toolkit does own is the one it enforces")
    void theOnlyDocumentFieldTheToolkitNamesIsTheTenantId() {
        assertThat(io.twba.search.toolkit.TenantDocument.TENANT_ID_FIELD).isEqualTo("tenantId");
    }

    private static List<Path> classFiles() {
        return TOOLKIT_CLASSES.stream()
                .peek(root -> assertThat(root).as("compiled classes of a toolkit module").exists())
                .flatMap(ToolkitVocabularyTest::walk)
                .filter(path -> path.getFileName().toString().endsWith(".class"))
                .toList();
    }

    private static Stream<Path> walk(Path root) {
        try {
            return Files.walk(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** ISO-8859-1 so every byte maps to a character: the constant pool is scanned, not parsed. */
    private static String read(Path classFile) {
        try {
            return new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
