package com.example.urlshortener.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** Where a link's clicks come from and who sent them. */
@Getter
@Builder
public class ClickAnalyticsResponse {
    private long totalClicks;
    private long clicksWithLocation;       // how many clicks we managed to place on a map
    private List<LabelCount> topCountries;
    private List<LabelCount> topCities;
    private List<LabelCount> topSources;   // website the visitor came from (Referer), or "Direct / unknown"
    private List<LabelCount> topReferralTags; // ?ref=name tags, e.g. who a link was shared by
}
