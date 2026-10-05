package com.example.urlshortener.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ClickEventPublisherTest {

    @Mock
    private KafkaTemplate<String, ClickEventMessage> kafkaTemplate;

    private final ClickEventPublisher publisher = new ClickEventPublisher(kafkaTemplate);

    @Test
    void publish_sendsToTheClicksTopic_keyedByShortCode() {
        LocalDateTime clickedAt = LocalDateTime.of(2026, 1, 15, 10, 30, 0);

        publisher.publish("abc1234", "203.0.113.5", clickedAt);

        ArgumentCaptor<ClickEventMessage> messageCaptor = ArgumentCaptor.forClass(ClickEventMessage.class);
        // Keyed by shortCode (the second arg) - see ClickEventPublisher's
        // own doc on why: all clicks for one link must land in the same
        // partition to preserve per-link ordering.
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(ClickEventPublisher.TOPIC),
                org.mockito.ArgumentMatchers.eq("abc1234"), messageCaptor.capture());

        ClickEventMessage sent = messageCaptor.getValue();
        assertEquals("abc1234", sent.shortCode());
        assertEquals("203.0.113.5", sent.ipAddress());

        long expectedEpochMilli = clickedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expectedEpochMilli, sent.clickedAtEpochMilli());
    }

    @Test
    void publish_usesTheUrlClicksTopicConstant() {
        assertEquals("url-clicks", ClickEventPublisher.TOPIC);
    }
}
