package com.example.urlshortener.kafka;

import com.example.urlshortener.entity.UrlClickEvent;
import com.example.urlshortener.repository.UrlClickEventRepository;
import com.example.urlshortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClickEventConsumerTest {

    @Mock
    private UrlMappingRepository urlMappingRepository;

    @Mock
    private UrlClickEventRepository clickEventRepository;

    private final ClickEventConsumer consumer = new ClickEventConsumer(urlMappingRepository, clickEventRepository);

    @Test
    void onClickEvent_incrementsCountAndSavesClickEvent_whenLinkStillExists() {
        long epochMilli = 1_768_000_000_000L; // arbitrary fixed instant
        ClickEventMessage message = new ClickEventMessage("abc1234", "203.0.113.5", epochMilli, null, null);

        when(urlMappingRepository.incrementClickCount(eq("abc1234"), any(LocalDateTime.class))).thenReturn(1);

        consumer.onClickEvent(message);

        LocalDateTime expectedClickedAt = Instant.ofEpochMilli(epochMilli)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        verify(urlMappingRepository).incrementClickCount("abc1234", expectedClickedAt);

        ArgumentCaptor<UrlClickEvent> eventCaptor = ArgumentCaptor.forClass(UrlClickEvent.class);
        verify(clickEventRepository).save(eventCaptor.capture());

        UrlClickEvent saved = eventCaptor.getValue();
        assertEquals("abc1234", saved.getShortCode());
        assertEquals("203.0.113.5", saved.getIpAddress());
        assertEquals(expectedClickedAt, saved.getClickedAt());
    }

    @Test
    void onClickEvent_skipsClickEventInsert_whenLinkWasAlreadyDeleted() {
        // incrementClickCount returning 0 means no row matched - the link
        // was deleted between the redirect happening and this message
        // being processed (see ClickEventConsumer's own doc).
        when(urlMappingRepository.incrementClickCount(anyString(), any(LocalDateTime.class))).thenReturn(0);

        consumer.onClickEvent(new ClickEventMessage("gone0000", "203.0.113.5", 1_768_000_000_000L, null, null));

        verify(clickEventRepository, never()).save(any(UrlClickEvent.class));
    }
}
