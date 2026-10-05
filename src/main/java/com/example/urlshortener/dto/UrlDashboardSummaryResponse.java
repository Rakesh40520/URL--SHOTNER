package com.example.urlshortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Aggregate counts for the "My Dispatches" dashboard's summary cards
 * (total/active/expired links, total clicks). Computed with COUNT/SUM
 * queries in the database (see UrlMappingRepository) rather than by paging
 * through every one of a user's links and summing them in Java, so the
 * summary stays O(1) regardless of how many links the user has.
 */
@Getter
@AllArgsConstructor
@Builder
public class UrlDashboardSummaryResponse {
    private long total;
    private long active;
    private long expired;
    private long blocked;
    private long totalClicks;
}
