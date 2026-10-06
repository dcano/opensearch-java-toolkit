package io.twba.search.toolkit.opensearch.controls;

import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.testlog.RecordedLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default audit adapter, read back off a recording SLF4J provider.
 *
 * <p>This is one of the few places where asserting on a log line is the requirement rather than a
 * proxy for one: the log line <em>is</em> the audit record, and the requirement states exactly what
 * it must name — the caller, the tenant reference, the operation and the matched document ids — and
 * what it must not: what was searched for.
 *
 * <p>The logger name is asserted too, and it is not cosmetic. It is the routing key: the whole
 * design is that these records can be pointed at an append-only store with its own retention
 * without grepping for a message prefix, and a record that went to the class's own logger would
 * inherit the appender that ships everything else with a 30-day retention.
 */
class LoggingIdentifierSearchAuditTest {

    private static final TenantRef TENANT = TenantRef.of(new SearchDomain("lab-results"), "clinic-a");
    private static final String CALLER = "test-caller";
    private static final String OPERATION = "search-by-subject";

    private final LoggingIdentifierSearchAudit audit = new LoggingIdentifierSearchAudit();

    @BeforeEach
    void clearLogs() {
        RecordedLogs.clear();
    }

    @Test
    @DisplayName("a match is recorded on the dedicated audit logger, naming caller, domain, tenant, operation and ids")
    void aMatchIsRecorded() {
        audit.identifierMatched(CALLER, TENANT, OPERATION, List.of("doc-1", "doc-2"));

        assertThat(auditLines()).singleElement().satisfies(event -> {
            assertThat(event.level()).isEqualTo(Level.INFO);
            assertThat(event.message()).contains(CALLER, "lab-results", "clinic-a", OPERATION,
                    "doc-1", "doc-2");
        });
        // Nothing else logged it: a copy on the application logger would defeat the routing.
        assertThat(RecordedLogs.events()).hasSize(1);
    }

    @Test
    @DisplayName("the domain is its own field, so the trail can be filtered without parsing the tenant")
    void theDomainIsItsOwnField() {
        audit.identifierMatched(CALLER, TENANT, OPERATION, List.of("doc-1"));

        // "which system was this person's data read through" is precisely what a data-protection
        // enquiry asks, and it is unanswerable if the domain is glued to the tenant id.
        assertThat(auditLines().getFirst().message()).contains("domain=lab-results", "tenant=clinic-a");
    }

    @Test
    @DisplayName("a throttled probe is recorded at warn, naming the caller and the tenant")
    void aThrottledProbeIsRecorded() {
        audit.identifierSearchThrottled(CALLER, TENANT, OPERATION);

        assertThat(auditLines()).singleElement().satisfies(event -> {
            // A throttled caller is the signal that matters most: a legitimate user rarely hits the
            // limit, and someone walking the prefix space hits it constantly. Warn, not info.
            assertThat(event.level()).isEqualTo(Level.WARN);
            assertThat(event.message()).contains(CALLER, "clinic-a", OPERATION, "throttled");
        });
    }

    @Test
    @DisplayName("the two outcomes are distinguishable in the trail")
    void outcomesAreDistinguishable() {
        audit.identifierMatched(CALLER, TENANT, OPERATION, List.of("doc-1"));
        audit.identifierSearchThrottled(CALLER, TENANT, OPERATION);

        assertThat(auditLines()).extracting(RecordedLogs.Event::message)
                .satisfiesExactly(
                        matched -> assertThat(matched).contains("outcome=matched"),
                        throttled -> assertThat(throttled).contains("outcome=throttled"));
    }

    private List<RecordedLogs.Event> auditLines() {
        return RecordedLogs.from(LoggingIdentifierSearchAudit.AUDIT_LOGGER);
    }
}
