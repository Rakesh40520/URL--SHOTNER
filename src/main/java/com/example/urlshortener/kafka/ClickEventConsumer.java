package com.example.urlshortener.kafka;

import com.example.urlshortener.entity.UrlClickEvent;
import com.example.urlshortener.repository.UrlClickEventRepository;
import com.example.urlshortener.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Consumes what UrlShortenerService.recordClick published instead of
 * writing directly, and does the actual persistence (the same atomic bulk
 * UPDATE + click-event insert the redirect path used to do inline) - see
 * ClickEventPublisher for the producer side.
 *
 * <p>@ConditionalOnProperty-gated (unlike the publisher, which is always
 * registered): a @KafkaListener container actively connects to and polls a
 * broker on startup, so - unlike the lazy KafkaTemplate producer - leaving
 * this registered with app.kafka.enabled=false would mean every app
 * startup tries to reach a Kafka broker that was never meant to be there.
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true")
public class ClickEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventConsumer.class);

    private final UrlMappingRepository urlMappingRepository;
    private final UrlClickEventRepository clickEventRepository;

    public ClickEventConsumer(UrlMappingRepository urlMappingRepository,
                               UrlClickEventRepository clickEventRepository) {
        this.urlMappingRepository = urlMappingRepository;
        this.clickEventRepository = clickEventRepository;
    }

    @KafkaListener(topics = ClickEventPublisher.TOPIC, groupId = "url-shortener-click-consumer")
    @Transactional
    public void onClickEvent(ClickEventMessage message) {
        LocalDateTime clickedAt = Instant.ofEpochMilli(message.clickedAtEpochMilli())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        int updated = urlMappingRepository.incrementClickCount(message.shortCode(), clickedAt);
        if (updated == 0) {
            // Link was deleted between the redirect happening and this
            // message being processed - the click already served its
            // purpose (the user got redirected), so there's nothing left
            // to increment. Log and move on rather than fail the message.
            log.warn("Click event for unknown/deleted short code '{}' - skipping.", message.shortCode());
            return;
        }

        clickEventRepository.save(UrlClickEvent.builder()
                .shortCode(message.shortCode())
                .clickedAt(clickedAt)
                .ipAddress(message.ipAddress())
                .build());
    }
}
