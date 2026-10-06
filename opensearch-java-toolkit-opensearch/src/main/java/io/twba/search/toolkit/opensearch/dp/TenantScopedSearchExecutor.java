package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.ErrorSearchingDataException;
import io.twba.search.toolkit.IdentifierProbeThrottledException;
import io.twba.search.toolkit.PrivacyLevel;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.SearchHit;
import io.twba.search.toolkit.SearchResults;
import io.twba.search.toolkit.TenantDocument;
import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.opensearch.TenantIndexResolver;
import io.twba.search.toolkit.opensearch.controls.CallerIdentity;
import io.twba.search.toolkit.opensearch.controls.IdentifierSearchAudit;
import io.twba.search.toolkit.opensearch.controls.IdentifierSearchLimiter;
import io.twba.search.toolkit.opensearch.obs.OpenSearchObservations;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Runs one tenant-scoped search, for any domain and any document type.
 *
 * <p>Everything that must happen on every search regardless of what is being searched for lives
 * here, and nowhere else:
 *
 * <ol>
 *   <li>resolve the search target and the routing value from the {@link TenantRef};</li>
 *   <li>wrap the caller's query in the mandatory tenant filter, in filter context;</li>
 *   <li>pick the deserialization type from the tenant's privacy level;</li>
 *   <li>open sealed fields on the way out, failing closed;</li>
 *   <li>observe the round trip with {@code domain}, {@code operation}, {@code index} and
 *       {@code privacy} tags;</li>
 *   <li>for a declared identifier probe: claim the budget <em>before</em> the cluster is touched and
 *       audit the matched document ids <em>after</em>;</li>
 *   <li>map hits, scores, highlights and match provenance into {@link SearchResults}.</li>
 * </ol>
 *
 * <p><strong>Final, and held rather than extended.</strong> The obvious alternative is an abstract
 * repository an application subclasses, and it fails for one reason: a subclass can override the
 * method that applies the tenant filter. A cross-tenant read is the worst outcome this codebase has,
 * and "nobody would override that" is not a control. There is no protected hook here, no overridable
 * execute, and no way to reach the client except through this class. An application holds one of
 * these and calls it.
 *
 * <p><strong>It builds no queries.</strong> The caller supplies the query and a shaping function for
 * page size, highlighting and source filtering, because which fields exist and what a page is are
 * the application's decisions. The toolkit names exactly one field — {@code tenantId}, the one it is
 * enforcing — and otherwise never mentions an application's schema. The identifier branch is built
 * by {@link SensitiveFieldQueries} from a field spec, which is the only reason it can name fields at
 * all: it derived them.
 */
public final class TenantScopedSearchExecutor<T extends TenantDocument> {

    /**
     * A {@code HIGH} tenant's hit comes back as the raw {@code _source} map, which the secure mapper
     * turns into {@code T}. There is no secure record type to deserialize into, by design — the
     * sealed shape is derived from the field specs, not declared — so the wire type is the map the
     * cluster actually sent. The cast is the standard way to hand {@code Map.class} to an API that
     * wants a {@code Class<X>} and is safe because every JSON object deserializes to exactly this.
     */
    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> SOURCE_MAP = (Class<Map<String, Object>>) (Class<?>) Map.class;

    private final OpenSearchClient client;
    private final SearchDomain domain;
    private final TenantIndexResolver resolver;
    private final Class<T> documentType;
    private final OpenSearchObservations observations;

    /** Null in a {@code NORMAL}-only deployment; {@link #requireSecureMapper} turns that into a refusal. */
    private final SecureDocumentMapper<T> secureMapper;

    /**
     * The two compensating controls, applied on <em>every</em> identifier search rather than only the
     * {@code HIGH} ones. A plaintext lookup is the same act with the same audit obligation; only the
     * storage differs, and an audit trail with a hole in it where the {@code NORMAL} tenants are is
     * not an audit trail.
     */
    private final IdentifierSearchLimiter limiter;
    private final IdentifierSearchAudit audit;
    private final CallerIdentity caller;

    /** {@code NORMAL}-only: a {@code HIGH} tenant is refused rather than searched with the wrong shape. */
    public TenantScopedSearchExecutor(OpenSearchClient client, SearchDomain domain, TenantIndexResolver resolver,
                                      Class<T> documentType) {
        this(client, domain, resolver, documentType, OpenSearchObservations.noop(), null);
    }

    public TenantScopedSearchExecutor(OpenSearchClient client, SearchDomain domain, TenantIndexResolver resolver,
                                      Class<T> documentType, OpenSearchObservations observations,
                                      SecureDocumentMapper<T> secureMapper) {
        this(client, domain, resolver, documentType, observations, secureMapper,
                IdentifierSearchLimiter.withDefaults(), IdentifierSearchAudit.noop(), CallerIdentity.anonymous());
    }

    public TenantScopedSearchExecutor(OpenSearchClient client, SearchDomain domain, TenantIndexResolver resolver,
                                      Class<T> documentType, OpenSearchObservations observations,
                                      SecureDocumentMapper<T> secureMapper,
                                      IdentifierSearchLimiter limiter, IdentifierSearchAudit audit,
                                      CallerIdentity caller) {
        this.client = Objects.requireNonNull(client, "client");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.documentType = Objects.requireNonNull(documentType, "documentType");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.secureMapper = secureMapper;
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.caller = Objects.requireNonNull(caller, "caller");
    }

    /** The domain this executor searches. Bound once, at construction. */
    public SearchDomain domain() {
        return domain;
    }

    /**
     * One round trip, with the privacy branch in the only place it belongs.
     *
     * <p>For a {@code HIGH} tenant the wire type is the raw source map and each hit's envelopes are
     * opened on the way out; for a {@code NORMAL} tenant the hit deserializes straight into the
     * application's type. Both scope the query to the tenant and route identically — privacy never
     * touches topology.
     *
     * @param operation the application's name for this call site. It becomes a meter tag and a span
     *                  name, so it must be one of a small fixed set and must never be derived from
     *                  user input
     * @param shape     page size, highlighting, source filtering — the application's decisions. It
     *                  is applied first and cannot set the target, the routing value or the query;
     *                  see {@link #buildRequest}
     */
    public SearchResults<T> search(TenantRef tenant, String operation, Query userQuery,
                                   UnaryOperator<SearchRequest.Builder> shape) {
        requireDomain(tenant);
        requireArguments(operation, userQuery, shape);
        return execute(tenant, operation, prepare(tenant, operation, userQuery, shape));
    }

    /**
     * A search that probes a sensitive identifier: budgeted before it runs, audited after.
     *
     * <p>A separate entry point rather than a flag, because which operations probe an identifier is
     * an application judgement that should be visible at the call site. Searching by an accession
     * number is not a probe; searching by name is one, and a criteria search is one only when a name
     * was supplied.
     *
     * <p>The limit is claimed before the cluster is touched, so a throttled caller learns nothing at
     * all — not even how long a query took, which is itself a signal. The audit record is written
     * after, because it names the documents that matched, and that is the fact worth keeping: not
     * that someone searched, but whose records they reached.
     *
     * <p>A search that matched nothing writes no record. It is not an access to anything, and an
     * audit trail padded with empty lookups is one nobody reads.
     */
    public SearchResults<T> identifierSearch(TenantRef tenant, String operation, Query userQuery,
                                             UnaryOperator<SearchRequest.Builder> shape) {
        requireDomain(tenant);
        requireArguments(operation, userQuery, shape);
        // Everything that can fail locally happens before the claim, and nothing but the cluster call
        // happens after it. The budget exists to make probes scarce; a caller bug — a null argument, a
        // shaping function that names a target, a HIGH tenant with no mapper wired — must not spend
        // one. None of this touches the cluster: it is resolver lookups and request construction.
        Prepared prepared = prepare(tenant, operation, userQuery, shape);
        String who = caller.current();
        try {
            limiter.claim(tenant);
        } catch (IdentifierProbeThrottledException throttled) {
            // Both pipelines, each with the part it is allowed to hold: a counter for the dashboard,
            // the caller and the tenant for the audit trail.
            observations.identifierSearchThrottled(domain, operation);
            audit.identifierSearchThrottled(who, tenant, operation);
            throw throttled;
        }

        SearchResults<T> results = execute(tenant, operation, prepared);

        List<String> matched = results.hits().stream()
                .filter(SearchHit::matchedIdentifier)
                .map(SearchHit::documentId)
                .toList();
        if (!matched.isEmpty()) {
            audit.identifierMatched(who, tenant, operation, matched);
        }
        return results;
    }

    /**
     * A search resolved and built but not yet sent: the target, the posture, the mapper that posture
     * requires, and the request itself.
     *
     * <p>It exists so the probe path can do all of this <em>before</em> claiming the rate-limit
     * budget, and so the step between the claim and the cluster call is nothing at all. An inner
     * class rather than a record because it holds the {@code T}-typed mapper, and a record is
     * implicitly static.
     */
    private final class Prepared {
        private final String index;
        private final PrivacyLevel level;
        /** Null for a {@code NORMAL} tenant; never null for a {@code HIGH} one, or preparation failed. */
        private final SecureDocumentMapper<T> mapper;
        private final SearchRequest request;

        private Prepared(String index, PrivacyLevel level, SecureDocumentMapper<T> mapper, SearchRequest request) {
            this.index = index;
            this.level = level;
            this.mapper = mapper;
            this.request = request;
        }
    }

    /**
     * Resolves the tenant's topology and posture and builds the request. Touches no cluster, so every
     * way this can fail is a way the caller's own code is wrong, and all of them are reachable before
     * a probe is spent.
     */
    private Prepared prepare(TenantRef tenant, String operation, Query userQuery,
                             UnaryOperator<SearchRequest.Builder> shape) {
        String index = resolver.searchIndex(tenant);
        PrivacyLevel level = resolver.privacyLevel(tenant);
        return new Prepared(index, level,
                level == PrivacyLevel.HIGH ? requireSecureMapper(tenant) : null,
                buildRequest(tenant, operation, userQuery, shape, index));
    }

    private SearchResults<T> execute(TenantRef tenant, String operation, Prepared prepared) {
        try {
            if (prepared.mapper != null) {
                SearchResponse<Map<String, Object>> response = observations.search(
                        domain, operation, tenant, prepared.index, prepared.level.name(),
                        () -> client.search(prepared.request, SOURCE_MAP));
                return SearchResponseMapper.toSearchResults(response,
                        hit -> prepared.mapper.fromDocument(tenant, hit.id(), requireSource(hit, tenant)));
            }
            SearchResponse<T> response = observations.search(
                    domain, operation, tenant, prepared.index, prepared.level.name(),
                    () -> client.search(prepared.request, documentType));
            return SearchResponseMapper.toSearchResults(response);
        } catch (IOException ex) {
            // The operation name only: the query text is the one thing that must never travel into a
            // message that reaches logs or an error response.
            throw new ErrorSearchingDataException("Error running search '" + operation + "'", ex);
        }
    }

    /**
     * Builds the request the cluster actually receives: the caller's shaping first, then the three
     * values the caller does not get to choose.
     *
     * <p>The order matters and is not enough on its own. {@code routing} and {@code query} are plain
     * setters, so setting them last wins outright and a shaping function that touched either has its
     * value discarded. {@code index} is not — the builder <em>appends</em> to a list — so a shaping
     * function that named an index would have it searched <em>alongside</em> the resolved one. That
     * is not a cross-tenant read, because the tenant filter still applies, but the same tenant id
     * routinely exists in two domains and the only thing separating them is the index; the same hole
     * points a {@code NORMAL} executor at a {@code secure} family. So the built request is checked
     * against the one target that was resolved, and a request that acquired another is refused
     * rather than quietly widened.
     *
     * <p>Refused, not overridden: a shaping function naming an index is a programming error, and the
     * caller should hear about it at the call site rather than have its intent silently dropped.
     */
    private SearchRequest buildRequest(TenantRef tenant, String operation, Query userQuery,
                                       UnaryOperator<SearchRequest.Builder> shape, String index) {
        SearchRequest.Builder builder = shape.apply(new SearchRequest.Builder());
        if (builder == null) {
            throw new IllegalArgumentException(
                    "the shaping function for '%s' returned null instead of the request builder"
                            .formatted(operation));
        }
        SearchRequest request = builder
                .index(index)
                .routing(resolver.routing(tenant).orElse(null))
                .query(TenantScopedQuery.scope(tenant, userQuery))
                .build();
        if (!List.of(index).equals(request.index())) {
            throw new IllegalArgumentException(
                    ("the shaping function for '%s' set the search target: tenant '%s' resolves to %s, "
                            + "but the request would search %s. Targets are the executor's to decide — "
                            + "a second one widens the search past the domain and the privacy family "
                            + "the tenant was placed in.")
                            .formatted(operation, tenant, List.of(index), request.index()));
        }
        return request;
    }

    private static void requireArguments(String operation, Query userQuery,
                                         UnaryOperator<SearchRequest.Builder> shape) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(userQuery, "userQuery");
        Objects.requireNonNull(shape, "shape");
    }

    /**
     * A {@code HIGH} hit with no {@code _source} cannot be opened, and there is no partial answer to
     * give: a document with blank sensitive fields is indistinguishable from one that never had any.
     * The usual cause is a source filter that excluded the envelope components.
     */
    private Map<String, Object> requireSource(Hit<Map<String, Object>> hit, TenantRef tenant) {
        if (hit.source() == null) {
            throw new ErrorSearchingDataException(
                    ("document '%s' for tenant '%s' came back without a _source: its sealed fields "
                            + "cannot be opened, and returning it with them blank would look like a "
                            + "record that never had any").formatted(hit.id(), tenant));
        }
        return hit.source();
    }

    /**
     * The executor is bound to one domain, and a {@link TenantRef} carries its own. An application
     * holding an executor per domain can pass the wrong one, and the mistake is quiet: the resolver
     * would answer for a tenant in a domain this executor does not serve, and the budget and the
     * audit record would name a domain the search did not run in.
     */
    private TenantRef requireDomain(TenantRef tenant) {
        Objects.requireNonNull(tenant, "tenant");
        if (!domain.equals(tenant.domain())) {
            throw new IllegalArgumentException(
                    ("tenant '%s' belongs to domain '%s', but this executor searches '%s'")
                            .formatted(tenant.tenantId(), tenant.domain().name(), domain.name()));
        }
        return tenant;
    }

    private SecureDocumentMapper<T> requireSecureMapper(TenantRef tenant) {
        if (secureMapper == null) {
            throw new IllegalStateException(
                    ("tenant '%s' is HIGH privacy but no SecureDocumentMapper is wired: its documents "
                            + "cannot be read as %s, and guessing would return values that are not "
                            + "values").formatted(tenant, documentType.getSimpleName()));
        }
        return secureMapper;
    }
}
