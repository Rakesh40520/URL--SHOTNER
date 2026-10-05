package com.example.urlshortener.config.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises determineCurrentLookupKey() directly (it's protected, but this
 * test lives in the same package, so no Spring context or reflection is
 * needed) against the three states TransactionSynchronizationManager can
 * actually be in: no transaction, a read-write transaction, and a
 * read-only one. These three are the entire contract
 * ReplicationRoutingDataSource relies on - get this right and every
 * @Transactional(readOnly = ...) in UrlShortenerService/AuthService routes
 * correctly without any further testing needed at the datasource layer.
 */
class ReplicationRoutingDataSourceTest {

    private final ReplicationRoutingDataSource routingDataSource = new ReplicationRoutingDataSource();

    @AfterEach
    void clearTransactionState() {
        // TransactionSynchronizationManager's flags are thread-local statics -
        // must reset them after every test or state leaks into whichever
        // test happens to run next on this thread.
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void noActiveTransaction_routesToPrimary() {
        // The default/safe state - e.g. a bean init method or anything
        // running outside a @Transactional boundary. Deliberately fails
        // toward the primary, never the replica, when in doubt.
        assertEquals(ReplicationRoutingDataSource.PRIMARY, routingDataSource.determineCurrentLookupKey());
    }

    @Test
    void readWriteTransaction_routesToPrimary() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        assertEquals(ReplicationRoutingDataSource.PRIMARY, routingDataSource.determineCurrentLookupKey());
    }

    @Test
    void readOnlyTransaction_routesToReplica() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertEquals(ReplicationRoutingDataSource.REPLICA, routingDataSource.determineCurrentLookupKey());
    }
}
