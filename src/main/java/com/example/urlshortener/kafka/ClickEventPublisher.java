package com.example.urlshortener.kafka;

import com.example.urlshortener.dto.ClickSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Fire-and-forget publish side of async click tracking. Only ever called
 * from UrlShortenerService.recordClick, and only when app.kafka.enabled is
 * true - see that method for the synchronous fallback.
 *
 * <p>Always registered as a bean (unlike the consumer), because
 * Spring Boot's auto-configured KafkaTemplate is lazy by default - building
 * it doesn't touch a broker, only actually sending a message does. That
 * mirrors how the Redis-backed beans elsewhere in this app are left in
 * place but unused when their own feature flag is off.
 */
@Component
public class ClickEventPublisher {

    // Partitioned by shortCode as the message key (see publish()) so every
    // click for one link lands in the same partition, preserving per-link
    // click ordering if that's ever needed downstream - not needed by
    // ClickEventConsumer today, but free to keep given the key was already
    // the natural partitioning choice.
    public static final String TOPIC = "url-clicks";

    private final KafkaTemplate<String, ClickEventMessage> kafkaTemplate;

    public ClickEventPublisher(KafkaTemplate<String, ClickEventMessage> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(String shortCode, String ipAddress, LocalDateTime clickedAt) {
        publish(shortCode, ipAddress, ClickSource.NONE, clickedAt);
    }

    public void publish(String shortCode, String ipAddress, ClickSource source, LocalDateTime clickedAt) {
        long epochMilli = clickedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        ClickSource src = source != null ? source : ClickSource.NONE;
        kafkaTemplate.send(TOPIC, shortCode, new ClickEventMessage(shortCode, ipAddress, epochMilli,
                src.referrerHost(), src.referralTag()));
    }
}
