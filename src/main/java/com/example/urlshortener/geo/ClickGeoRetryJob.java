package com.example.urlshortener.geo;

import com.example.urlshortener.entity.UrlClickEvent;
import com.example.urlshortener.repository.UrlClickEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Safety net for click locations. If the first background lookup failed (provider
 * down, rate limited, brief network issue) the click would otherwise stay
 * "unknown" forever. Every few minutes this retries recent unlocated clicks, at
 * most once an hour each, newest first, in small batches.
 *
 * <p>Clicks whose stored IP is private (e.g. a proxy's address, because the real
 * visitor IP wasn't forwarded) are skipped quickly: no lookup is ever sent for them.
 */
@Component
public class ClickGeoRetryJob {

    private static final Logger log = LoggerFactory.getLogger(ClickGeoRetryJob.class);
    private static final int BATCH = 25;

    private final UrlClickEventRepository clickEvents;
    private final GeoLocationService geo;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public ClickGeoRetryJob(UrlClickEventRepository clickEvents, GeoLocationService geo,
                            TransactionTemplate tx, @Value("${app.geo.enabled:true}") boolean enabled) {
        this.clickEvents = clickEvents;
        this.geo = geo;
        this.tx = tx;
        this.enabled = enabled;
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)   // 2 min after start, then every 5 min
    public void retryUnlocated() {
        if (!enabled) return;
        LocalDateTime now = LocalDateTime.now();
        List<UrlClickEvent> batch;
        try {
            batch = clickEvents.findUnlocated(now.minusDays(3), now.minusHours(1), PageRequest.of(0, BATCH));
        } catch (Exception e) {
            log.debug("Geo retry skipped: {}", e.toString());
            return;
        }

        int located = 0;
        for (UrlClickEvent c : batch) {
            var info = geo.lookup(c.getIpAddress());   // network call, outside any transaction
            final Long id = c.getId();
            if (info.isPresent()) {
                GeoInfo g = info.get();
                tx.executeWithoutResult(s -> {
                    clickEvents.updateGeo(id, g.country(), g.countryCode(), g.region(), g.city());
                    clickEvents.markGeoChecked(id, LocalDateTime.now());
                });
                located++;
            } else {
                tx.executeWithoutResult(s -> clickEvents.markGeoChecked(id, LocalDateTime.now()));
            }
        }
        if (!batch.isEmpty()) log.info("Geo retry: located {} of {} previously unknown clicks.", located, batch.size());
    }
}
