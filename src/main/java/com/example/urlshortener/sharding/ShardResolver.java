package com.example.urlshortener.sharding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Maps a shortCode to a shard index in [0, numShards). Sharding by
 * shortCode - rather than, say, user id - is what this app's own design
 * makes practical: since RandomCodeGenerator picks a uniformly random
 * Base62 code (not a Base62-encoded auto-increment id - see its own
 * javadoc), hashing the code spreads links evenly across shards with no
 * hot shard from sequential ids landing together. That's specifically
 * NOT true of the classic "shard by id" approach this app used to be
 * exposed to before codes were randomized.
 *
 * <p>Plain modulo hashing, not consistent hashing - deliberately: modulo
 * is the simple, correct starting point, and is what most real systems
 * start with too. Its known weakness is that changing numShards reshuffles
 * almost every key's target shard, which is exactly the problem consistent
 * hashing (a hash ring) solves - worth naming as the upgrade path, not
 * worth building for a fixed 2-shard demo.
 */
@Component
public class ShardResolver {

    private final int numShards;

    public ShardResolver(@Value("${app.sharding.num-shards:2}") int numShards) {
        if (numShards < 1) {
            throw new IllegalArgumentException("app.sharding.num-shards must be >= 1");
        }
        this.numShards = numShards;
    }

    public int resolve(String shortCode) {
        // floorMod, not %, so this stays non-negative regardless of what
        // shortCode.hashCode() returns (String.hashCode() can be negative).
        return Math.floorMod(shortCode.hashCode(), numShards);
    }
}
