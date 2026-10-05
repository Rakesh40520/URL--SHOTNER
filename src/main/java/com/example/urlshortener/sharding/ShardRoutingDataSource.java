package com.example.urlshortener.sharding;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Routes each connection to the shard set by ShardContext for the current
 * thread - see that class for why this can't be inferred automatically the
 * way Phase 1's primary/replica routing is. Falls back to shard 0 if
 * nothing set it (see the "known gap" note on UrlShortenerService's
 * cross-shard read methods - getMyUrls/getMyUrlsSummary can't resolve a
 * single shard from a userId, so they don't set one at all).
 */
public class ShardRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        Integer shard = ShardContext.get();
        return shard != null ? shard : 0;
    }
}
