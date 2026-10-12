package com.example.urlshortener.geo;

import com.example.urlshortener.repository.UrlClickEventRepository;
import com.example.urlshortener.sharding.ShardContext;
import com.example.urlshortener.sharding.ShardResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The background half of geo-enrichment. Kept as its own bean on purpose:
 * @Async only works when the call crosses a Spring proxy, so ClickGeoEnricher
 * (a different bean) must be the caller - calling this from within the same
 * class would silently run on the caller's thread.
 */
@Component
public class ClickGeoWorker {

    private static final Logger log = LoggerFactory.getLogger(ClickGeoWorker.class);

    private final GeoLocationService geo;
    private final UrlClickEventRepository clickEvents;
    private final ShardResolver shardResolver;

    public ClickGeoWorker(GeoLocationService geo, UrlClickEventRepository clickEvents, ShardResolver shardResolver) {
        this.geo = geo;
        this.clickEvents = clickEvents;
        this.shardResolver = shardResolver;
    }

    @Async("clickEnrichmentExecutor")
    @Transactional
    public void enrich(Long eventId, String shortCode, String ip) {
        var info = geo.lookup(ip);          // network call, before any datasource routing
        if (info.isEmpty()) return;

        // Same shard routing as the click insert; otherwise under postgres-sharded this
        // could update a different shard's row that happens to share the id.
        ShardContext.set(shardResolver.resolve(shortCode));
        try {
            GeoInfo g = info.get();
            clickEvents.updateGeo(eventId, g.country(), g.countryCode(), g.region(), g.city());
        } catch (Exception ex) {
            log.debug("Could not save geo for click {}: {}", eventId, ex.toString());
        } finally {
            ShardContext.clear();
        }
    }
}
