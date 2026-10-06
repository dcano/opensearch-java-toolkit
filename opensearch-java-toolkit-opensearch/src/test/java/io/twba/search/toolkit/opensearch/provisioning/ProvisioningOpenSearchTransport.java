package io.twba.search.toolkit.opensearch.provisioning;

import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsAliasRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.PutIndexTemplateResponse;
import org.opensearch.client.transport.Endpoint;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.TransportOptions;
import org.opensearch.client.transport.endpoints.BooleanResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A hand-written {@link OpenSearchTransport} the provisioning tests drive a <em>real</em>
 * {@link org.opensearch.client.opensearch.OpenSearchClient} over.
 *
 * <p><b>Why a third fake.</b> Its two siblings in {@code dp} model the write and read halves of the
 * data-plane conversation — bulk operations, search responses — and provisioning speaks none of it.
 * What travels here is a composable template, two kinds of existence probe, an index creation and a
 * raw {@code PUT} through the generic client, and the assertions are about the <em>contents</em> of
 * those: which pattern, which priority, which properties, which aliases, which JSON body. Folding
 * them into either sibling would produce a fixture whose halves never appear together.
 *
 * <p><b>The generic client is observable, and that matters.</b>
 * {@code OpenSearchGenericClient.execute} ends at {@code transport.performRequest(request, ...)}
 * with the {@link Request} itself as the request object, so the endpoint, the method and the whole
 * JSON body are readable here. The ISM installer therefore needs no seam of its own: what it
 * injected into an application's policy is asserted from the bytes that would have gone on the wire.
 * The ISM policy store is modelled as the plugin behaves — a GET that is {@code 404} until a policy
 * exists, and a PUT over an existing policy that is a {@code 409} unless it carries the stored
 * version — and the error path too: {@link #answeringWithStatus} plus the endpoint's own
 * {@code isError} / {@code exceptionConverter}, which is exactly the sequence the real transport
 * runs — so "the cluster refused this policy" is a state a test can put the installer in.
 *
 * <p><b>The cluster state is a set of indices and a set of aliases, kept apart.</b> Idempotency is
 * the whole question for {@link PoolProvisioner}, and which of the two it asks about is the decision
 * that took a working provisioner and made it unable to restart a rolled-over cluster. A fake that
 * conflated them could not tell the two probes apart, so it could not have caught that. The one
 * place they are deliberately conflated is {@code indices().exists}, which on a real cluster
 * resolves aliases as well as indices; {@link #asIfRolledOver} is the state where that distinction
 * decides whether startup survives.
 *
 * <p>Like its siblings it refuses to serialize: {@link #jsonpMapper()} raises. Nothing on this path
 * needs a client-side mapper, and a fake that quietly returned one would hide the day that changed.
 */
final class ProvisioningOpenSearchTransport implements OpenSearchTransport {

    /**
     * One call through the generic client, captured before anything could consume its body. The query
     * parameters are part of it because the ISM update preconditions travel there.
     */
    record GenericCall(String method, String endpoint, Map<String, String> parameters, String body) {
    }

    /** A stored policy as the ISM plugin versions it: a document in its system index. */
    record StoredPolicy(long seqNo, long primaryTerm, String body) {
    }

    private static final int OK = 200;
    private static final int CREATED = 201;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;

    private static final String POLICY_ENDPOINT = "/_plugins/_ism/policies/";

    private final List<PutIndexTemplateRequest> templateRequests = new ArrayList<>();
    private final List<ExistsRequest> existsRequests = new ArrayList<>();
    private final List<ExistsAliasRequest> existsAliasRequests = new ArrayList<>();
    private final List<CreateIndexRequest> createRequests = new ArrayList<>();
    private final List<GenericCall> genericCalls = new ArrayList<>();

    /** Concrete index names the cluster holds. Aliases are not indices and are not in here. */
    private final Set<String> existingIndices = new LinkedHashSet<>();

    /** Alias names the cluster holds, whichever generation they currently point at. */
    private final Set<String> existingAliases = new LinkedHashSet<>();

    /**
     * The ISM plugin's policy store, keyed by policy id. Modelled rather than answered with a blanket
     * 200, because the plugin's real contract is what decides whether a restart works: a PUT to an id
     * that already exists, without the stored document's {@code if_seq_no} and
     * {@code if_primary_term}, is a {@code 409}. A fake that accepted every PUT is why that escaped
     * once.
     */
    private final Map<String, StoredPolicy> policies = new java.util.LinkedHashMap<>();

    /**
     * Sequence numbers are per shard of the ISM system index, not per policy, so they advance across
     * every policy written — which is why a stale value from one install is not the current one after
     * another policy was written in between.
     */
    private long nextSeqNo = 0;

    /** When set, the cluster refuses every policy PUT with this status, whatever the store holds. */
    private Integer refusedPutStatus;

    /** When set, the cluster answers every policy GET with this status instead of reading the store. */
    private Integer failedGetStatus;

    // ---------------------------------------------------------------- seeding

    /** Makes {@code indices().exists} answer true for exactly these concrete index names. */
    ProvisioningOpenSearchTransport alreadyHolding(String... indices) {
        existingIndices.addAll(List.of(indices));
        return this;
    }

    /** Makes {@code indices().existsAlias} answer true for exactly these alias names. */
    ProvisioningOpenSearchTransport alreadyHoldingAliases(String... aliases) {
        existingAliases.addAll(List.of(aliases));
        return this;
    }

    /**
     * One pool as a cluster holds it after a rollover and the lifecycle delete of generation one:
     * both aliases alive and pointing at a later generation, and the first generation gone.
     *
     * <p>This is the state the whole idempotency question turns on. A provisioner that asks about
     * the first generation is told "no" about a pool that is perfectly healthy, re-creates it, and
     * the create fails because the write alias already has a write index — so the cluster cannot be
     * restarted. The names are passed in rather than derived here, so the fake models a cluster
     * without also re-implementing the name grammar it is supposed to be independent of.
     */
    ProvisioningOpenSearchTransport asIfRolledOver(String searchAlias, String writeAlias,
                                                   String currentGeneration) {
        existingAliases.add(searchAlias);
        existingAliases.add(writeAlias);
        existingIndices.add(currentGeneration);
        return this;
    }

    /** The cluster refuses every policy PUT with this status — an unknown action, a plugin failure. */
    ProvisioningOpenSearchTransport answeringWithStatus(int status) {
        this.refusedPutStatus = status;
        return this;
    }

    /** The cluster cannot answer the policy read: a status other than 200 or 404 on every GET. */
    ProvisioningOpenSearchTransport failingPolicyReadsWith(int status) {
        this.failedGetStatus = status;
        return this;
    }

    /** A policy the cluster already holds, at a given version, as an earlier deployment left it. */
    ProvisioningOpenSearchTransport alreadyHoldingPolicy(String policyId, long seqNo, long primaryTerm) {
        policies.put(policyId, new StoredPolicy(seqNo, primaryTerm, "{\"policy\":{}}"));
        nextSeqNo = Math.max(nextSeqNo, seqNo + 1);
        return this;
    }

    /**
     * The same cluster, a fresh process: everything created so far is now simply there — index and
     * aliases both — and the recorded conversation starts empty so the second startup's traffic is
     * read on its own.
     */
    ProvisioningOpenSearchTransport asIfRestarted() {
        createRequests.forEach(request -> {
            existingIndices.add(request.index());
            existingAliases.addAll(request.aliases().keySet());
        });
        templateRequests.clear();
        existsRequests.clear();
        existsAliasRequests.clear();
        createRequests.clear();
        genericCalls.clear();
        // Policies are cluster state: they survive the restart, exactly as indices do.
        return this;
    }

    // ---------------------------------------------------------------- what was sent

    List<PutIndexTemplateRequest> templateRequests() {
        return List.copyOf(templateRequests);
    }

    PutIndexTemplateRequest onlyTemplate() {
        if (templateRequests.size() != 1) {
            throw new AssertionError("expected exactly one template install, got " + templateRequests.size());
        }
        return templateRequests.getFirst();
    }

    /** Names asked about through {@code indices().exists} — the index-existence probe. */
    List<String> probedIndices() {
        return existsRequests.stream().flatMap(request -> request.index().stream()).toList();
    }

    /** Names asked about through {@code indices().existsAlias} — the alias-existence probe. */
    List<String> probedAliases() {
        return existsAliasRequests.stream().flatMap(request -> request.name().stream()).toList();
    }

    List<CreateIndexRequest> createRequests() {
        return List.copyOf(createRequests);
    }

    List<String> createdIndices() {
        return createRequests.stream().map(CreateIndexRequest::index).toList();
    }

    List<GenericCall> genericCalls() {
        return List.copyOf(genericCalls);
    }

    GenericCall onlyGenericCall() {
        if (genericCalls.size() != 1) {
            throw new AssertionError("expected exactly one generic call, got " + genericCalls.size());
        }
        return genericCalls.getFirst();
    }

    /** The policy PUTs, in order — what was actually written, as opposed to read. */
    List<GenericCall> policyPuts() {
        return genericCalls.stream().filter(call -> call.method().equals("PUT")).toList();
    }

    GenericCall onlyPolicyPut() {
        List<GenericCall> puts = policyPuts();
        if (puts.size() != 1) {
            throw new AssertionError("expected exactly one policy PUT, got " + puts.size());
        }
        return puts.getFirst();
    }

    Optional<StoredPolicy> storedPolicy(String policyId) {
        return Optional.ofNullable(policies.get(policyId));
    }

    /** Every request of every kind, so a test can assert that nothing at all was sent. */
    int requestCount() {
        return templateRequests.size() + existsRequests.size() + existsAliasRequests.size()
                + createRequests.size() + genericCalls.size();
    }

    // ---------------------------------------------------------------- transport

    @Override
    @SuppressWarnings("unchecked")
    public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options)
            throws IOException {

        if (request instanceof PutIndexTemplateRequest template) {
            templateRequests.add(template);
            return (ResponseT) PutIndexTemplateResponse.of(r -> r.acknowledged(true));
        }
        if (request instanceof ExistsRequest exists) {
            existsRequests.add(exists);
            // HEAD /<name> resolves aliases as well as concrete indices on a real cluster, so this
            // answers for either. That fidelity is what makes it meaningful that the provisioner
            // stopped using this probe: it did not move because the probe was ambiguous, it moved
            // because the first generation is the wrong thing to ask about.
            return (ResponseT) new BooleanResponse(exists.index().stream()
                    .allMatch(name -> existingIndices.contains(name) || existingAliases.contains(name)));
        }
        if (request instanceof ExistsAliasRequest existsAlias) {
            existsAliasRequests.add(existsAlias);
            return (ResponseT) new BooleanResponse(
                    existsAlias.name().stream().allMatch(existingAliases::contains));
        }
        if (request instanceof CreateIndexRequest create) {
            createRequests.add(create);
            return (ResponseT) CreateIndexResponse.of(r -> r
                    .acknowledged(true)
                    .shardsAcknowledged(true)
                    .index(create.index()));
        }
        if (request instanceof Request generic) {
            String body = generic.getBody().map(Body::bodyAsString).orElse(null);
            genericCalls.add(new GenericCall(
                    generic.getMethod(),
                    generic.getEndpoint(),
                    Map.copyOf(generic.getParameters()),
                    body));
            RecordedResponse answer = answer(generic, body);
            @SuppressWarnings("unchecked")
            ErrorT response = (ErrorT) answer;
            // The real transport's own sequence: build the response, ask the endpoint whether this
            // status is an error, and if so let the endpoint convert it. A fake that returned the
            // response regardless would make every generic call look successful — which is the
            // exact bug the installer was once fixed for.
            if (endpoint.isError(answer.status())) {
                throw endpoint.exceptionConverter(answer.status(), response);
            }
            return (ResponseT) response;
        }
        throw new UnsupportedOperationException(
                "provisioning sent a request this fake does not model: " + request.getClass().getName());
    }

    /**
     * The ISM plugin's answer to one generic request. Only the policy endpoint is modelled; anything
     * else the provisioning path sends through the generic client is a request this fake was not
     * written for, and says so.
     */
    private RecordedResponse answer(Request generic, String body) {
        String endpoint = generic.getEndpoint();
        if (!endpoint.startsWith(POLICY_ENDPOINT)) {
            throw new UnsupportedOperationException("a generic request this fake does not model: " + endpoint);
        }
        String policyId = endpoint.substring(POLICY_ENDPOINT.length());
        StoredPolicy stored = policies.get(policyId);
        return switch (generic.getMethod()) {
            case "GET" -> {
                if (failedGetStatus != null) {
                    yield new RecordedResponse(generic, failedGetStatus, null);
                }
                if (stored == null) {
                    yield new RecordedResponse(generic, NOT_FOUND,
                            "{\"error\":{\"type\":\"status_exception\",\"reason\":\"Policy not found\"},\"status\":404}");
                }
                yield new RecordedResponse(generic, OK, ("{\"_id\":\"%s\",\"_version\":1,\"_seq_no\":%d,"
                        + "\"_primary_term\":%d,\"policy\":%s}").formatted(policyId, stored.seqNo(),
                        stored.primaryTerm(), stored.body()));
            }
            case "PUT" -> put(generic, policyId, stored, body);
            default -> throw new UnsupportedOperationException(
                    "a policy request this fake does not model: " + generic.getMethod() + " " + endpoint);
        };
    }

    /**
     * A create when the id is free and no precondition is given; an update only when the
     * preconditions name the stored version exactly; a {@code 409} otherwise — the plugin's own rule,
     * and the one a restart meets.
     */
    private RecordedResponse put(Request generic, String policyId, StoredPolicy stored, String body) {
        if (refusedPutStatus != null) {
            return new RecordedResponse(generic, refusedPutStatus, null);
        }
        String seqNo = generic.getParameters().get("if_seq_no");
        String primaryTerm = generic.getParameters().get("if_primary_term");
        boolean conditional = seqNo != null || primaryTerm != null;
        if (stored == null) {
            if (conditional) {
                return new RecordedResponse(generic, CONFLICT, null);   // no document to match
            }
            policies.put(policyId, new StoredPolicy(nextSeqNo++, 1, body));
            return new RecordedResponse(generic, CREATED, null);
        }
        if (!conditional
                || !String.valueOf(stored.seqNo()).equals(seqNo)
                || !String.valueOf(stored.primaryTerm()).equals(primaryTerm)) {
            return new RecordedResponse(generic, CONFLICT, null);
        }
        policies.put(policyId, new StoredPolicy(nextSeqNo++, stored.primaryTerm(), body));
        return new RecordedResponse(generic, OK, null);
    }

    @Override
    public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options) {
        try {
            return CompletableFuture.completedFuture(performRequest(request, endpoint, options));
        } catch (IOException | RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    @Override
    public JsonpMapper jsonpMapper() {
        throw new UnsupportedOperationException(
                "provisioning hands the client fully built request objects and a JSON string; "
                        + "this fake records them rather than serializing");
    }

    @Override
    public TransportOptions options() {
        return TransportOptions.builder().build();
    }

    @Override
    public void close() {
        // nothing to release
    }

    /**
     * The cluster's answer. A body only where the installer reads one — the policy GET — in the shape
     * the plugin returns. The reason phrase is real because {@code OpenSearchClientException} puts it
     * in its message, which is what a reader of a failed startup actually sees.
     */
    private record RecordedResponse(Request request, int status, String json) implements Response {

        @Override
        public Optional<Body> getBody() {
            return json == null ? Optional.empty()
                    : Optional.of(Body.from(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json"));
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public String getMethod() {
            return request.getMethod();
        }

        @Override
        public String getReason() {
            return switch (status) {
                case OK -> "OK";
                case CREATED -> "Created";
                case 400 -> "Bad Request";
                case NOT_FOUND -> "Not Found";
                case CONFLICT -> "Conflict";
                case 500 -> "Internal Server Error";
                default -> "Unexpected";
            };
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public String getUri() {
            return request.getEndpoint();
        }

        @Override
        public Collection<Map.Entry<String, String>> getHeaders() {
            return List.of();
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
