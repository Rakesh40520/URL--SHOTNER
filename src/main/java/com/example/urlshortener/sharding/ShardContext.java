package com.example.urlshortener.sharding;

/**
 * Carries "which shard should the next DB call go to" for the current
 * thread. Unlike Phase 1's read/write routing - which Spring already
 * exposes via {@code TransactionSynchronizationManager.isCurrentTransactionReadOnly()}
 * - there's no built-in signal for "which shard", since that depends on
 * request data (a shortCode), not on how the transaction was declared. So
 * this has to be set explicitly, by the caller, before the repository call
 * it applies to - see UrlShortenerService's withShard/inShard helpers.
 *
 * <p>Only consulted when the {@code postgres-sharded} profile is active
 * (see ShardRoutingDataSource / ShardDataSourceConfig); every other profile
 * never touches this class at all.
 */
public final class ShardContext {

    private static final ThreadLocal<Integer> CURRENT_SHARD = new ThreadLocal<>();

    private ShardContext() {
    }

    public static void set(int shard) {
        CURRENT_SHARD.set(shard);
    }

    public static Integer get() {
        return CURRENT_SHARD.get();
    }

    // Always called from a finally block by whatever set() - a ThreadLocal
    // left set after a request finishes would leak onto whatever the next
    // request handled by that pooled thread happens to do.
    public static void clear() {
        CURRENT_SHARD.remove();
    }
}
