package com.example.urlshortener.sharding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardResolverTest {

    @Test
    void resolve_isDeterministic_forTheSameCode() {
        ShardResolver resolver = new ShardResolver(4);

        int first = resolver.resolve("abc1234");
        int second = resolver.resolve("abc1234");

        assertEquals(first, second);
    }

    @Test
    void resolve_alwaysReturnsAnIndexWithinRange() {
        ShardResolver resolver = new ShardResolver(3);

        // A spread of codes, not just one - floorMod is the actual
        // behavior under test, and String.hashCode() can be negative, so
        // this exercises that without relying on knowing exact hash values.
        String[] codes = {"abc1234", "xyz9876", "q1w2e3r", "ZZZZZZZ", "0000000", "aB3dE5f"};
        for (String code : codes) {
            int shard = resolver.resolve(code);
            assertTrue(shard >= 0 && shard < 3, "shard " + shard + " out of range for code " + code);
        }
    }

    @Test
    void resolve_withOneShardConfigured_alwaysReturnsZero() {
        ShardResolver resolver = new ShardResolver(1);

        assertEquals(0, resolver.resolve("anything"));
        assertEquals(0, resolver.resolve("something-else"));
    }

    @Test
    void constructor_rejectsZeroOrNegativeShardCount() {
        assertThrows(IllegalArgumentException.class, () -> new ShardResolver(0));
        assertThrows(IllegalArgumentException.class, () -> new ShardResolver(-1));
    }
}
