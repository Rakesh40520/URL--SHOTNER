package com.example.urlshortener.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Wires up a primary + read-replica Postgres setup, active only under the
 * {@code postgres-ha} profile (see application.yml). Every other profile
 * (default H2, or the single-instance {@code postgres} profile) is
 * completely untouched - Spring Boot's normal auto-configured DataSource
 * still applies, since this class (and therefore all its @Bean methods)
 * simply isn't loaded unless postgres-ha is active.
 *
 * <p>Follows Spring Boot's own documented pattern for wiring more than one
 * DataSource (see "How-to: Configure Two DataSources" in the Spring Boot
 * reference docs) rather than anything bespoke: two {@link DataSourceProperties}
 * beans bound from {@code app.datasource.primary}/{@code .replica}, each
 * building a real {@link HikariDataSource}, fed into a
 * {@link ReplicationRoutingDataSource} that's the single DataSource the rest
 * of the app (and JPA) actually sees.
 */
@Configuration
@Profile("postgres-ha")
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("app.datasource.primary")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource primaryDataSource() {
        return primaryDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConfigurationProperties("app.datasource.replica")
    public DataSourceProperties replicaDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource replicaDataSource() {
        return replicaDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * The DataSource JPA/Hibernate actually binds to (marked @Primary so it
     * wins autowiring over the two raw beans above, which still exist as
     * beans only so this method - and nothing else - can reach them).
     *
     * <p>Wrapped in {@link LazyConnectionDataSourceProxy}: without it, some
     * transaction managers can acquire a physical connection at transaction
     * start, before the read-only flag set by {@code @Transactional} is
     * necessarily visible to {@link ReplicationRoutingDataSource}, which
     * would silently route everything to the primary regardless of
     * {@code readOnly}. The proxy defers acquiring a real connection until
     * the first statement actually runs, by which point the flag is
     * reliably in place.
     */
    @Bean
    @Primary
    public DataSource routingDataSource(@Qualifier("primaryDataSource") DataSource primaryDataSource,
                                         @Qualifier("replicaDataSource") DataSource replicaDataSource) {
        ReplicationRoutingDataSource routingDataSource = new ReplicationRoutingDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(ReplicationRoutingDataSource.PRIMARY, primaryDataSource);
        targetDataSources.put(ReplicationRoutingDataSource.REPLICA, replicaDataSource);

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(primaryDataSource);
        // Required by AbstractRoutingDataSource - resolves the target map
        // into its internal lookup structure. Skipping this leaves the
        // routing datasource unusable and throws on first use.
        routingDataSource.afterPropertiesSet();

        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
