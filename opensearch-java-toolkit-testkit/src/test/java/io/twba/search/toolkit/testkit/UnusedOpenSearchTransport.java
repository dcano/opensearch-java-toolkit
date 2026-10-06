package io.twba.search.toolkit.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.transport.Endpoint;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.TransportOptions;

import java.util.concurrent.CompletableFuture;

/**
 * A transport that refuses every request but serializes exactly like a real one, for the checks that
 * need a client without needing a cluster.
 *
 * <p>Two of the six conformance checks — both fail-closed ones — complete before a request is built.
 * They are the ones worth running against a deliberately broken subject, and running them in
 * milliseconds rather than behind a container start is what makes that cheap enough to do for every
 * fault worth planting.
 *
 * <p>The refusal is loud on purpose: a check that silently started depending on a round trip would
 * otherwise show up as a socket timeout rather than as a named failure.
 *
 * <p>The mapper, though, is real. The read-side check narrows the write path's {@code Object} payload
 * by serializing it with the client's own mapper and reading the result back as a map, which is the
 * only honest way to ask what would reach {@code _source}. A fixture that refused to serialize would
 * make that check untestable without a container, and this one is configured like
 * {@link ToolkitOpenSearchCluster}'s so a payload that round-trips here round-trips there.
 */
final class UnusedOpenSearchTransport implements OpenSearchTransport {

    private final JsonpMapper mapper = new JacksonJsonpMapper(
            new ObjectMapper().registerModule(new JavaTimeModule()));

    @Override
    public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options) {
        throw new UnsupportedOperationException(
                "this test builds no request: the transport was asked to send " + request.getClass().getName());
    }

    @Override
    public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "this test builds no request: the transport was asked to send " + request.getClass().getName()));
    }

    @Override
    public JsonpMapper jsonpMapper() {
        return mapper;
    }

    @Override
    public TransportOptions options() {
        return TransportOptions.builder().build();
    }

    @Override
    public void close() {
        // nothing to release
    }
}
