package io.twba.search.toolkit.opensearch;

import io.twba.search.toolkit.TenantRef;

public interface TenantPlacementCatalogRepository {

    TenantPlacement retrieve(TenantRef tenant);
}
