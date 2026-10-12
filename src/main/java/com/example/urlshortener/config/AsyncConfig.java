package com.example.urlshortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Small, bounded pool for click geo-enrichment. If a burst of clicks outpaces the
 * lookups, the overflow is dropped (those clicks just keep an unknown location)
 * instead of queueing without limit or slowing redirects down.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "clickEnrichmentExecutor")
    public ThreadPoolTaskExecutor clickEnrichmentExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("click-geo-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        return ex;
    }
}
