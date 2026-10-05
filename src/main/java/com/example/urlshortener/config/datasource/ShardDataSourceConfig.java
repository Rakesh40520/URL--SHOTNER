package com.example.urlshortener.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import com.example.urlshortener.sharding.ShardRoutingDataSource;
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
 * Two-shard Postgres setup, active only under {@code postgres-sharded} (a
 * separate profile from {@code postgres-ha} - deliberately not combined
 * with replicas here, since a sharded+replicated setup is a real thing but
 * doubles the moving parts for no extra teaching value in a demo).
 *
 * <p>Fixed at two shards (not a dynamically-sized N) to keep this concrete
 * and fast to stand up locally - see docker-compose.shards.yml. Going from
 * 2 to N shards is a config/wiring change, not a different pattern.
 */
@Configuration
@Profile("postgres-sharded")
public class ShardDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.datasource.shard0")
    public DataSourceProperties shard0Properties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource shard0DataSource() {
        return shard0Properties().initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("app.datasource.shard1")
    public DataSourceProperties shard1Properties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource shard1DataSource() {
        return shard1Properties().initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public DataSource routingDataSource(@Qualifier("shard0DataSource") DataSource shard0,
                                         @Qualifier("shard1DataSource") DataSource shard1) {
        ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();

        Map<Object, Object> targets = new HashMap<>();
        targets.put(0, shard0);
        targets.put(1, shard1);

        routingDataSource.setTargetDataSources(targets);
        routingDataSource.setDefaultTargetDataSource(shard0);
        routingDataSource.afterPropertiesSet();

        // Same reasoning as Phase 1's routing datasource: defer acquiring a
        // real connection until the first statement runs, by which point
        // ShardContext has definitely been set by the calling service
        // method (see UrlShortenerService.inShard).
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
