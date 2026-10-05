package com.example.urlshortener.sharding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShardContextTest {

    @AfterEach
    void clearAfterEachTest() {
        // Static ThreadLocal - must reset after every test or state leaks
        // onto whichever test runs next on this thread (same reasoning as
        // ReplicationRoutingDataSourceTest's cleanup for Phase 1).
        ShardContext.clear();
    }

    @Test
    void get_withNothingSet_returnsNull() {
        assertNull(ShardContext.get());
    }

    @Test
    void set_thenGet_returnsWhatWasSet() {
        ShardContext.set(1);
        assertEquals(1, ShardContext.get());
    }

    @Test
    void set_canBeOverwritten() {
        ShardContext.set(0);
        ShardContext.set(1);
        assertEquals(1, ShardContext.get());
    }

    @Test
    void clear_removesTheValue() {
        ShardContext.set(1);
        ShardContext.clear();
        assertNull(ShardContext.get());
    }
}
