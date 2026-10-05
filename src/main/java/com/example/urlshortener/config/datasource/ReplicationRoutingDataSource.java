package com.example.urlshortener.config.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Routes each connection request to either the primary or a read replica,
 * based solely on whether the current Spring transaction is marked
 * read-only (i.e. {@code @Transactional(readOnly = true)} on the calling
 * service method - see UrlShortenerService/AuthService).
 *
 * <p>This is deliberately the only signal used. It means the routing is a
 * direct, auditable consequence of a decision already made in application
 * code (is this operation a read or a write), not a separate piece of
 * config that can drift out of sync with what each method actually does.
 * Get the {@code readOnly} annotation wrong on a method that writes, and
 * that write silently goes to a replica and is later overwritten/ignored -
 * so this class trusts the annotation completely and adds no guessing on
 * top of it (e.g. no SQL-sniffing to detect SELECT vs INSERT).
 *
 * <p>Only registered when the {@code postgres-ha} profile is active (see
 * DataSourceConfig) - every other profile keeps Spring Boot's normal single
 * auto-configured DataSource, untouched.
 */
public class ReplicationRoutingDataSource extends AbstractRoutingDataSource {

    public static final String PRIMARY = "primary";
    public static final String REPLICA = "replica";

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? REPLICA : PRIMARY;
    }
}
