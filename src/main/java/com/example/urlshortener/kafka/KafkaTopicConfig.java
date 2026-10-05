package com.example.urlshortener.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Auto-provisions the url-clicks topic on startup (via Spring Kafka's
 * KafkaAdmin, already auto-configured once spring-kafka is on the
 * classpath) instead of relying on broker auto-create-topics settings,
 * which many real Kafka deployments turn off. Only meaningful - and only
 * registered - when app.kafka.enabled=true.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    public NewTopic urlClicksTopic() {
        return TopicBuilder.name(ClickEventPublisher.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
