package io.twba.search.toolkit.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.utility.DockerImageName;

import javax.net.ssl.SSLContext;
import java.util.Objects;

/**
 * A single-node OpenSearch container with the security plugin enabled, and a client for it.
 *
 * <p>Security on, not off. A conformance suite run against an unsecured cluster proves less than it
 * appears to: the two checks that matter most here — that a tenant cannot read another tenant's
 * documents, and that the secure family refuses a readable value — both involve the cluster enforcing
 * something, and a cluster with the plugin disabled is a different cluster. The implementation this
 * generalizes made the same call for the same reason, and its suites are the evidence that the cost
 * is a slower startup rather than a broken test.
 *
 * <p>The image tag is pinned. A floating tag would make a conformance run's result depend on when it
 * was run, which is the opposite of what a conformance run is for.
 *
 * <p><strong>The password here is a throwaway for a container that lives for one test class.</strong>
 * It is deliberately not the development cluster's, and nothing outside this file should learn either:
 * a credential that appears in two places is one that will eventually be copied to a third.
 */
public final class ToolkitOpenSearchCluster {

    /** Pinned on purpose; see the class comment. */
    public static final DockerImageName IMAGE =
            DockerImageName.parse("opensearchproject/opensearch:3.7.0");

    /** Test-local, for a container discarded at the end of the class that started it. */
    private static final String ADMIN_PASSWORD = "Toolkit!Conformance#2026";

    private static final int HTTP_PORT = 9200;

    private ToolkitOpenSearchCluster() {
    }

    /**
     * A container ready to be managed by the caller's own lifecycle.
     *
     * <p>Returned unstarted, and not held in a static field here, so the test class that declares it
     * owns when it starts and stops. A shared singleton would be faster and would also let one suite's
     * leftover indices decide another suite's result.
     */
    public static OpenSearchContainer<?> container() {
        return new OpenSearchContainer<>(IMAGE)
                .withSecurityEnabled()
                .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", ADMIN_PASSWORD);
    }

    /**
     * A client for a started container, built the way an application builds one: HTTPS, basic auth,
     * a pooled async connection manager, and JSR-310 dates.
     *
     * <p>The JSR-310 module is not optional dressing. The client's default mapper is a bare
     * {@code ObjectMapper}, which cannot serialize an {@code Instant}; an application document with a
     * timestamp would fail to index, and the suite would report a wiring problem that belongs to the
     * fixture.
     *
     * <p>TLS verification is disabled because the container generates its own certificate on startup.
     * <strong>This is a property of a throwaway test container and must never be copied into anything
     * that talks to a real cluster.</strong>
     */
    public static OpenSearchClient clientFor(OpenSearchContainer<?> container) {
        Objects.requireNonNull(container, "container");
        if (!container.isRunning()) {
            throw new IllegalStateException(
                    "the OpenSearch container is not running: start it before asking for a client, or "
                            + "let the JUnit Testcontainers extension manage it");
        }
        try {
            HttpHost host = new HttpHost("https", container.getHost(), container.getMappedPort(HTTP_PORT));

            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(new AuthScope(host), new UsernamePasswordCredentials(
                    container.getUsername(), container.getPassword().toCharArray()));

            SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(null, (chain, authType) -> true)   // test container only
                    .build();

            return new OpenSearchClient(ApacheHttpClient5TransportBuilder
                    .builder(host)
                    .setMapper(new JacksonJsonpMapper(jsonMapper()))
                    .setHttpClientConfigCallback(http -> http
                            .setDefaultCredentialsProvider(credentials)
                            .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                                    .setTlsStrategy(ClientTlsStrategyBuilder.create()
                                            .setSslContext(sslContext)
                                            .build())
                                    .build()))
                    .build());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("could not build a client for the OpenSearch container", ex);
        }
    }

    private static ObjectMapper jsonMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
