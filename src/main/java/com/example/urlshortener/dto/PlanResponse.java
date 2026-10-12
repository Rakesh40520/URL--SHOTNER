package com.example.urlshortener.dto;

import com.example.urlshortener.entity.SubscriptionPlan;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** One tier as shown on the pricing page. Built straight from the SubscriptionPlan enum. */
@Getter
@Builder
public class PlanResponse {
    private String key;               // "FREE" | "PRO" | "BUSINESS"
    private String name;
    private String tagline;
    private int priceMonthlyCents;
    private int priceYearlyCents;     // 10 x monthly: two months free when billed yearly
    private int monthlyLinkLimit;     // -1 = unlimited
    private boolean customAlias;
    private Integer maxExpiryDays;    // null = links never expire
    private boolean csvExport;
    private boolean popular;
    private List<String> features;

    public static PlanResponse from(SubscriptionPlan p) {
        return PlanResponse.builder()
                .key(p.name())
                .name(p.getDisplayName())
                .tagline(p.getTagline())
                .priceMonthlyCents(p.getPriceMonthlyCents())
                .priceYearlyCents(p.getPriceMonthlyCents() * 10)
                .monthlyLinkLimit(p.getMonthlyLinkLimit())
                .customAlias(p.isCustomAliasAllowed())
                .maxExpiryDays(p.getMaxExpiryDays())
                .csvExport(p.isCsvExportAllowed())
                .popular(p == SubscriptionPlan.PRO)
                .features(p.featureList())
                .build();
    }
}
