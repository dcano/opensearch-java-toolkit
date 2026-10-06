package io.twba.search.toolkit.testkit;

import io.twba.search.toolkit.TenantDocument;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Reaches one named check out of {@link ToolkitConformance#tests}, so a test can run it alone.
 *
 * <p>Two of the six checks — both fail-closed ones — touch no cluster at all: they call the write
 * path and the secure mapper directly and assert that neither produces anything. Being able to run
 * exactly those, against a deliberately broken subject, is what turns "the suite compiled" into "the
 * suite would have caught it".
 */
final class ChecksUnderTest {

    static final String TENANT_ISOLATION = "a tenant's search returns no other tenant's documents";
    static final String TELEMETRY = "nothing sensitive reaches meters or spans";
    static final String KEYLESS_WRITE = "a HIGH tenant with no key material fails closed on write";
    static final String KEYLESS_READ = "a HIGH tenant with no key material fails closed on read";
    static final String NO_READABLE_VALUE_STORED = "the secure family stores no readable sensitive value";
    static final String CLUSTER_REFUSES = "the cluster refuses a readable sensitive value";
    static final String NO_SENSITIVE_FIELDS =
            "this domain declares no sensitive fields, so the HIGH checks do not apply";

    private ChecksUnderTest() {
    }

    static List<String> namesOf(ConformanceSubject<? extends TenantDocument> subject) {
        return ToolkitConformance.tests(subject).map(DynamicTest::getDisplayName).toList();
    }

    /** Runs one named check and lets whatever it raises escape, for a test that asserts on the run. */
    static void run(ConformanceSubject<? extends TenantDocument> subject, String displayName) {
        try {
            check(subject, displayName).execute();
        } catch (Throwable thrown) {
            throw thrown instanceof RuntimeException runtime ? runtime : new IllegalStateException(thrown);
        }
    }

    static Executable check(ConformanceSubject<? extends TenantDocument> subject, String displayName) {
        return ToolkitConformance.tests(subject)
                .filter(test -> displayName.equals(test.getDisplayName()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "the suite published no check named '%s'".formatted(displayName)))
                .getExecutable();
    }
}
