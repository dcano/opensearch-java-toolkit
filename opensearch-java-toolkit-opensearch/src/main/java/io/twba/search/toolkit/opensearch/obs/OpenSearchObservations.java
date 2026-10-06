package io.twba.search.toolkit.opensearch.obs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.twba.search.toolkit.BulkStats;
import io.twba.search.toolkit.SearchDomain;
import io.twba.search.toolkit.TenantRef;
import org.opensearch.client.opensearch._types.ShardStatistics;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * The one place OpenSearch calls get observed.
 *
 * <p>Every call produces both a span and a set of meters, and the split is deliberate:
 * <ul>
 *   <li><b>Low-cardinality key values</b> ({@code operation}, {@code index}, {@code privacy},
 *       {@code domain}) become meter tags <em>and</em> span attributes — safe in a time series.</li>
 *   <li><b>High-cardinality key values</b> ({@code tenant.id}, shard counts, document counts) land
 *       on the span only. A per-tenant time series is how you accidentally build 5,000 series per
 *       metric; on a span it is free and still searchable.</li>
 * </ul>
 *
 * <p>{@code domain} joins the low-cardinality set because a shared cluster now serves several
 * applications: without it, one domain's bulk load and another's are one undifferentiated line, and
 * the first question anyone asks about a backpressure spike is which application caused it. There
 * are as many values as there are declared domains, which is a number an operator chose.
 *
 * <p>Nothing sensitive is recorded anywhere: no query text, no canonical value, no hash, no key, no
 * opened value — not in a meter tag, not on a span, not in a log line.
 *
 */
public class OpenSearchObservations {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchObservations.class);

    public static final String SEARCH_OBSERVATION = "opensearch.search";
    public static final String BULK_OBSERVATION = "opensearch.bulk";

    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    public OpenSearchObservations(ObservationRegistry observations, MeterRegistry meters) {
        this.observations = observations;
        this.meters = meters;
    }

    /** For tests and plain-{@code main} usage: records nowhere, costs nothing to call. */
    public static OpenSearchObservations noop() {
        return new OpenSearchObservations(ObservationRegistry.NOOP, new SimpleMeterRegistry());
    }

    @FunctionalInterface
    public interface SearchCall<T> {
        SearchResponse<T> execute() throws IOException;
    }

    @FunctionalInterface
    public interface BulkCall {
        BulkStats execute() throws IOException;
    }

    /**
     * Wraps one {@code _search} round trip: times it, spans it, and records what the cluster
     * reported back about shards, hits and its own {@code took}.
     *
     * <p>Nothing about the query itself is recorded — not the text, not the field, not a hash, not a
     * token count. {@code operation} is the application's own name for the call site, and the rest
     * comes from the response.
     *
     * @param privacy the tenant's privacy posture, {@code NORMAL} or {@code HIGH}. Two values for
     *                the lifetime of the system, so it costs one extra time series per meter and
     *                answers the question the posture actually raises: what does blind indexing cost
     *                in latency and shard fan-out? Without the tag that comparison is folklore; with
     *                it, it is a panel.
     */
    public <T> SearchResponse<T> search(SearchDomain domain, String operation, TenantRef tenant,
                                        String index, String privacy, SearchCall<T> call) throws IOException {

        Observation observation = Observation.createNotStarted(SEARCH_OBSERVATION, observations)
                .contextualName("opensearch " + operation)          // span name
                .lowCardinalityKeyValue("domain", domain.name())
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("index", index)
                .lowCardinalityKeyValue("privacy", privacy)
                .highCardinalityKeyValue("db.system", "opensearch")
                // The span, and only the span. A per-tenant meter tag is how a shared cluster grows
                // a time series per tenant per meter; on a span it is free and still queryable.
                .highCardinalityKeyValue("tenant.id", tenant.tenantId());

        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            SearchResponse<T> response = call.execute();
            record(observation, domain, operation, index, privacy, response);
            return response;
        } catch (IOException | RuntimeException ex) {
            observation.error(ex);
            throw ex;
        } finally {
            observation.stop();
        }
    }

    private void record(Observation observation, SearchDomain domain, String operation, String index,
                        String privacy, SearchResponse<?> response) {
        // One tag list, built once: every meter below carries the same four low-cardinality values,
        // and a meter that carried a different set would silently fork the series.
        String[] tags = {"domain", domain.name(), "operation", operation, "index", index, "privacy", privacy};
        ShardStatistics shards = response.shards();
        long hits = response.hits().total() != null ? response.hits().total().value() : response.hits().hits().size();
        int skipped = shards != null && shards.skipped() != null ? shards.skipped() : 0;

        if (shards != null) {
            observation.highCardinalityKeyValue("opensearch.shards.total", String.valueOf(shards.total()))
                    .highCardinalityKeyValue("opensearch.shards.successful", String.valueOf(shards.successful()))
                    .highCardinalityKeyValue("opensearch.shards.skipped", String.valueOf(skipped))
                    .highCardinalityKeyValue("opensearch.shards.failed", String.valueOf(shards.failed()));

            DistributionSummary.builder("opensearch.search.shards")
                    .description("Shards the coordinating node fanned the query out to")
                    .tags(tags)
                    .register(meters).record(shards.total());
            DistributionSummary.builder("opensearch.search.shards.skipped")
                    .description("Shards pruned by routing / can-match before executing the query")
                    .tags(tags)
                    .register(meters).record(skipped);
            if (shards.failed() > 0) {
                Counter.builder("opensearch.search.shards.failed")
                        .description("Shard-level failures inside an otherwise successful search")
                        .tags(tags)
                        .register(meters).increment(shards.failed());
            }
        }

        observation.highCardinalityKeyValue("opensearch.hits.total", String.valueOf(hits))
                .highCardinalityKeyValue("opensearch.took_ms", String.valueOf(response.took()))
                .highCardinalityKeyValue("opensearch.timed_out", String.valueOf(response.timedOut()));

        // Cluster-reported took, i.e. latency WITHOUT the network + deserialization the observation
        // timer includes. The gap between the two is the client's own overhead.
        Timer.builder("opensearch.search.took")
                .description("Query execution time as reported by the cluster")
                .tags(tags)
                .register(meters).record(response.took(), TimeUnit.MILLISECONDS);

        DistributionSummary.builder("opensearch.search.hits")
                .description("Total hits matched per query")
                .tags(tags)
                .register(meters).record(hits);

        // One line per search, inside the span's scope: traceId/spanId are in the MDC, so a log
        // backend can link the line straight to its trace. Counts and identifiers only — no query
        // text, no field values, nothing derived from either.
        log.debug("search domain={} op={} index={} privacy={} shards={}/{} skipped={} took={}ms hits={}",
                domain.name(), operation, index, privacy,
                shards != null ? shards.successful() : 0,
                shards != null ? shards.total() : 0,
                skipped, response.took(), hits);

        if (response.timedOut()) {
            Counter.builder("opensearch.search.timed.out")
                    .description("Searches that returned partial results because a shard ran out of time")
                    .tags(tags)
                    .register(meters).increment();
        }
    }

    /**
     * A sensitive-identifier search refused by the per-tenant probe limit.
     *
     * <p>A meter, not a log line with a tenant in it: the tenant belongs on the audit record, which
     * has the retention and the access controls for it. This counter answers "is anyone hitting the
     * limit" for a dashboard; the audit trail answers "who".
     */
    public void identifierSearchThrottled(SearchDomain domain, String operation) {
        Counter.builder("opensearch.search.identifier.throttled")
                .description("Identifier searches refused because the tenant's probe window was exhausted")
                .tag("domain", domain.name())
                .tag("operation", operation)
                .register(meters).increment();
    }

    /** Wraps a whole bulk load, all retries included, in one span plus outcome counters. */
    public BulkStats bulk(SearchDomain domain, int documents, BulkCall call) throws IOException {
        Observation observation = Observation.createNotStarted(BULK_OBSERVATION, observations)
                .contextualName("opensearch bulk")
                .lowCardinalityKeyValue("domain", domain.name())
                .highCardinalityKeyValue("db.system", "opensearch")
                .highCardinalityKeyValue("opensearch.bulk.documents", String.valueOf(documents));

        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            BulkStats stats = call.execute();
            observation.highCardinalityKeyValue("opensearch.bulk.succeeded", String.valueOf(stats.succeeded()))
                    .highCardinalityKeyValue("opensearch.bulk.failed", String.valueOf(stats.failed()))
                    .highCardinalityKeyValue("opensearch.took_ms", String.valueOf(stats.tookMillis()));

            meters.counter("opensearch.bulk.documents", "domain", domain.name(), "outcome", "succeeded")
                    .increment(stats.succeeded());
            if (stats.failed() > 0) {
                meters.counter("opensearch.bulk.documents", "domain", domain.name(), "outcome", "failed")
                        .increment(stats.failed());
            }
            Timer.builder("opensearch.bulk.took")
                    .description("Bulk execution time as reported by the cluster")
                    .tag("domain", domain.name())
                    .register(meters).record(stats.tookMillis(), TimeUnit.MILLISECONDS);
            return stats;
        } catch (IOException | RuntimeException ex) {
            observation.error(ex);
            throw ex;
        } finally {
            observation.stop();
        }
    }

    /**
     * A 429 from the cluster. {@code scope} is {@code request} when the whole bulk was refused,
     * {@code item} when individual documents were. This is the backpressure signal — graph it next
     * to the write thread pool's rejected counter.
     */
    public void bulkRejection(SearchDomain domain, String scope, int documents) {
        Counter.builder("opensearch.bulk.rejected")
                .description("Documents rejected with HTTP 429 / es_rejected_execution_exception")
                .tag("domain", domain.name())
                .tag("scope", scope)
                .register(meters).increment(documents);
    }

    /** One backoff-and-retry cycle, with the batch size we shrank to. */
    public void bulkRetry(SearchDomain domain, int attempt, int chunkSize) {
        meters.counter("opensearch.bulk.retries", "domain", domain.name(), "attempt", String.valueOf(attempt))
                .increment();
        DistributionSummary.builder("opensearch.bulk.chunk.size")
                .description("Bulk chunk size after backpressure shrinking")
                .tag("domain", domain.name())
                .register(meters).record(chunkSize);
    }
}
