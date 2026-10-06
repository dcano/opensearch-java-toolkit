package io.twba.search.toolkit.opensearch.controls;

import io.twba.search.toolkit.TenantRef;

import java.util.List;

/**
 * The audit trail for sensitive-identifier searches — who looked a value up, in which domain and
 * tenant, and which records came back.
 *
 * <p>Deliberately not the telemetry pipeline, and the separation is not tidiness. Telemetry is
 * sampled, aggregated, retained for weeks and tagged with low-cardinality values on purpose; an
 * audit trail must be complete, per-event, tamper-evident and retained for as long as the
 * data-protection regime says. Meters already refuse per-tenant labels on cardinality grounds,
 * which alone rules them out as the place to record who searched for whom.
 *
 * <p>A port because the sink is still an open question: an append-only log, a domain event consumed
 * by an audit service, or the cluster's own audit index. What is settled is that the record exists
 * and that it names the caller — everything else is transport.
 *
 * <p>Every record is keyed on a {@link TenantRef}, so the domain is in the trail by construction.
 * A trail that said only {@code tenant=clinic-a} would be ambiguous the moment one organisation
 * appears in two domains, and "which system was this person's data read through" is precisely the
 * question a data-protection enquiry asks.
 *
 * <p>Implementations must not record what was searched for. The query text is the sensitive half:
 * "who read this patient's results" is the audit fact, and "who typed g-a-r-c" is a transcript of a
 * guess that the audit store would then hold in the clear.
 */
public interface IdentifierSearchAudit {

    /**
     * A sensitive-identifier search that matched. Emitted per search, not per hit, so one lookup is
     * one line in the trail.
     *
     * @param matchedDocumentIds the document ids whose identifier branch matched — never empty
     */
    void identifierMatched(String caller, TenantRef tenant, String operation, List<String> matchedDocumentIds);

    /**
     * A search refused because the tenant's probe window was exhausted. Recorded because a throttled
     * caller is the signal that matters most: a legitimate user rarely hits the limit, and someone
     * walking the prefix space hits it constantly.
     */
    void identifierSearchThrottled(String caller, TenantRef tenant, String operation);

    /** For tests and plain-{@code main} usage. Never a production default: silence is not an audit trail. */
    static IdentifierSearchAudit noop() {
        return new IdentifierSearchAudit() {
            @Override
            public void identifierMatched(String caller, TenantRef tenant, String operation, List<String> ids) {
            }

            @Override
            public void identifierSearchThrottled(String caller, TenantRef tenant, String operation) {
            }
        };
    }
}
