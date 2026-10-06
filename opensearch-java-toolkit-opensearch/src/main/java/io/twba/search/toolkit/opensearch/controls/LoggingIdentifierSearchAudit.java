package io.twba.search.toolkit.opensearch.controls;

import io.twba.search.toolkit.TenantRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The default audit adapter: one line per event, on a logger of its own.
 *
 * <p>The dedicated logger name is the whole design. It keeps these records out of the application
 * log by construction, so the routing decision — which sink, which retention, which access controls
 * — is made once in the logging configuration rather than by grepping for a message prefix. Point
 * {@code audit.identifier-search} at an append-only store; do not let it inherit the appender that
 * ships everything else with a 30-day retention.
 *
 * <p>The domain is its own field rather than part of the tenant string, so a query over the trail
 * can filter by either without parsing. It is one logger for every domain on purpose: splitting the
 * logger per domain would make each application's configuration responsible for getting the
 * retention right, and one that forgot would drop its audit trail into the application log.
 *
 * <p>A log file is a starting point, not the destination. It is readable, orderable and trivially
 * shipped, and it is not tamper-evident: anything with write access to the file can rewrite history.
 * That is the gap a real audit sink closes, and it is why the port exists.
 */
public class LoggingIdentifierSearchAudit implements IdentifierSearchAudit {

    /** Not this class's own logger: the name is the routing key, so it is fixed and explicit. */
    public static final String AUDIT_LOGGER = "audit.identifier-search";

    private static final Logger audit = LoggerFactory.getLogger(AUDIT_LOGGER);

    @Override
    public void identifierMatched(String caller, TenantRef tenant, String operation, List<String> matchedDocumentIds) {
        // Ids, not values. A document id is a pointer into a store that already enforces access
        // control; an opened value in a log line is a copy of the data outside every control that
        // protects the original.
        audit.info("event=identifier_match outcome=matched caller={} domain={} tenant={} operation={} matched={} ids={}",
                caller, tenant.domain().name(), tenant.tenantId(), operation,
                matchedDocumentIds.size(), matchedDocumentIds);
    }

    @Override
    public void identifierSearchThrottled(String caller, TenantRef tenant, String operation) {
        audit.warn("event=identifier_match outcome=throttled caller={} domain={} tenant={} operation={}",
                caller, tenant.domain().name(), tenant.tenantId(), operation);
    }
}
