package com.example.urlshortener.sharding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Same approach as ReplicationRoutingDataSourceTest (Phase 1): calls the
 * protected determineCurrentLookupKey() directly, since this test lives in
 * the same package - no Spring context or reflection needed.
 */
class ShardRoutingDataSourceTest {

    private final ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();

    @AfterEach
    void clearShardContext() {
        ShardContext.clear();
    }

    @Test
    void nothingSet_fallsBackToShardZero() {
        assertEquals(0, routingDataSource.determineCurrentLookupKey());
    }

    @Test
    void shardSetToZero_routesToShardZero() {
        ShardContext.set(0);
        assertEquals(0, routingDataSource.determineCurrentLookupKey());
    }

    @Test
    void shardSetToOne_routesToShardOne() {
        ShardContext.set(1);
        assertEquals(1, routingDataSource.determineCurrentLookupKey());
    }
}
