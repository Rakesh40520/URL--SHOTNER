package com.example.urlshortener.kafka;

/**
 * What goes on the wire for one click, when app.kafka.enabled=true (see
 * UrlShortenerService.recordClick / ClickEventPublisher / ClickEventConsumer).
 * Deliberately just the three fields the consumer needs to reconstruct the
 * DB write - not the UrlClickEvent entity itself, same reasoning as
 * CachedShortUrl not being the full UrlMapping: keeps the message small and
 * avoids serializing a JPA entity straight onto a Kafka topic.
 */
public record ClickEventMessage(String shortCode, String ipAddress, long clickedAtEpochMilli,
                                String referrerHost, String referralTag) {
}
