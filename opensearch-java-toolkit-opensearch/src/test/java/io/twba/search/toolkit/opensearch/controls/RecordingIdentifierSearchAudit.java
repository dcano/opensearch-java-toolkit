package io.twba.search.toolkit.opensearch.controls;

import io.twba.search.toolkit.TenantRef;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link IdentifierSearchAudit} that keeps every record it is handed, so a test can ask what the
 * trail says.
 *
 * <p>A real implementation of the port rather than a mock: the interesting assertions are about the
 * <em>content</em> of a record — which caller, which tenant reference, which document ids — and a
 * record is a value, so keeping the values is both simpler and more honest than counting calls.
 *
 * <p>Public because the executor's tests live in the {@code dp} package while the port lives here,
 * exactly as {@code RecordingTelemetry} is public for the same reason.
 */
public final class RecordingIdentifierSearchAudit implements IdentifierSearchAudit {

    /** One line of the trail, with everything the port promises it names. */
    public record Record(String outcome, String caller, TenantRef tenant, String operation,
                         List<String> matchedDocumentIds) {
    }

    public static final String MATCHED = "matched";
    public static final String THROTTLED = "throttled";

    private final List<Record> records = new ArrayList<>();

    @Override
    public void identifierMatched(String caller, TenantRef tenant, String operation,
                                  List<String> matchedDocumentIds) {
        records.add(new Record(MATCHED, caller, tenant, operation, List.copyOf(matchedDocumentIds)));
    }

    @Override
    public void identifierSearchThrottled(String caller, TenantRef tenant, String operation) {
        records.add(new Record(THROTTLED, caller, tenant, operation, List.of()));
    }

    public List<Record> records() {
        return List.copyOf(records);
    }

    public List<Record> recordsOf(String outcome) {
        return records.stream().filter(record -> outcome.equals(record.outcome())).toList();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }
}
