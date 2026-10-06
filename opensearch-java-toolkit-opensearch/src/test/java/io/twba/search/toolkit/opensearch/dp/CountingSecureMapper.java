package io.twba.search.toolkit.opensearch.dp;

import io.twba.search.toolkit.TenantRef;
import io.twba.search.toolkit.crypto.KeyUnavailableException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link SecureDocumentMapper} that stands in for real sealing, and answers two questions the real
 * one cannot be asked cheaply.
 *
 * <p><b>How many times was a document sealed?</b> Every call returns a <em>new</em>
 * {@link SealedStub} with a fresh seal number, so "sealed once, re-sent as-is" and "re-sealed on
 * every attempt" produce visibly different payloads. Real sealing has this property too — a fresh
 * per-document data key means fresh ciphertext — which is why the distinction matters: re-sealing on
 * retry burns key material and makes a retried write a different document than the one that was
 * rejected.
 *
 * <p><b>What happens to a tenant with no key material?</b> Tenants named in {@code keylessTenants}
 * raise {@link KeyUnavailableException}, the same failure the real mapper raises when a key is
 * missing. Nothing here may return a readable document.
 */
final class CountingSecureMapper implements SecureDocumentMapper<FakeDocument> {

    /** What the mapper "sealed". Distinguishable per call, and holds no readable note. */
    record SealedStub(String tenantId, String documentId, int sealNumber) {
    }

    private final Set<String> keylessTenants;
    private final AtomicInteger seals = new AtomicInteger();
    private final List<String> sealedDocumentIds = new ArrayList<>();

    CountingSecureMapper() {
        this(Set.of());
    }

    CountingSecureMapper(Set<String> keylessTenants) {
        this.keylessTenants = Set.copyOf(keylessTenants);
    }

    @Override
    public Object toDocument(TenantRef tenant, FakeDocument source) {
        if (keylessTenants.contains(tenant.tenantId())) {
            throw new KeyUnavailableException(tenant.tenantId(), "no data-encryption key is provisioned");
        }
        synchronized (sealedDocumentIds) {
            sealedDocumentIds.add(source.documentId());
        }
        return new SealedStub(tenant.tenantId(), source.documentId(), seals.incrementAndGet());
    }

    /**
     * Not implemented on purpose. This fixture exists for the write path; the read direction is
     * exercised against the real spec-driven mapper, where opening an envelope is the behaviour
     * under test rather than something to stand in for. A test that reached this method would be
     * asserting against a fake of the very thing it meant to check.
     */
    @Override
    public FakeDocument fromDocument(TenantRef tenant, String documentId, Map<String, Object> source) {
        throw new UnsupportedOperationException("this write-path fixture maps one way only");
    }

    int sealCount() {
        return seals.get();
    }

    /** One entry per sealing call, so a document appearing twice means it was sealed twice. */
    List<String> sealedDocumentIds() {
        synchronized (sealedDocumentIds) {
            return List.copyOf(sealedDocumentIds);
        }
    }
}
