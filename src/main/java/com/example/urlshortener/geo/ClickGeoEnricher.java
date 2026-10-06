package com.example.urlshortener.geo;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Schedules a click's country/region/city lookup. The lookup itself runs on a
 * background thread (see ClickGeoWorker) so a redirect never waits on it.
 */
@Component
public class ClickGeoEnricher {

    private final ClickGeoWorker worker;

    public ClickGeoEnricher(ClickGeoWorker worker) {
        this.worker = worker;
    }

    /**
     * Runs after the surrounding transaction commits, so the background update can
     * actually see the click row. Outside a transaction it starts immediately.
     */
    public void enrichAfterCommit(Long eventId, String shortCode, String ip) {
        if (eventId == null || ip == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { worker.enrich(eventId, shortCode, ip); }
            });
        } else {
            worker.enrich(eventId, shortCode, ip);
        }
    }
}
