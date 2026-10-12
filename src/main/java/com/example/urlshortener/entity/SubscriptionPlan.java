package com.example.urlshortener.entity;

import java.util.List;

/**
 * The subscription tiers and exactly what each one allows. This enum is the
 * single source of truth: the pricing page, the usage meter and the server-side
 * enforcement (see SubscriptionService) all read from here, so changing a limit
 * is a one-line edit.
 *
 * <p>Stored on the user as a STRING (never ordinal) so reordering or adding
 * tiers later can't silently re-map existing accounts.
 */
public enum SubscriptionPlan {

    //            name        $/mo (cents)  links/mo  custom alias  max expiry (days; null = never)  CSV export  tagline
    FREE("Free",     0,    20,    false, 30,   false, "For trying Dispatch out."),
    PRO("Pro",       499,  500,   true,  365,  true,  "For creators and side projects."),
    BUSINESS("Business", 1499, -1 /* = NO_LIMIT; a static can't be referenced before its declaration here */, true, null, true, "For teams that ship links daily.");

    /** Sentinel for "no monthly cap". */
    public static final int NO_LIMIT = -1;


    private final String displayName;
    private final int priceMonthlyCents;
    private final int monthlyLinkLimit;
    private final boolean customAliasAllowed;
    private final Integer maxExpiryDays;   // null => links may never expire
    private final boolean csvExportAllowed;
    private final String tagline;

    SubscriptionPlan(String displayName, int priceMonthlyCents, int monthlyLinkLimit,
                     boolean customAliasAllowed, Integer maxExpiryDays,
                     boolean csvExportAllowed, String tagline) {
        this.displayName = displayName;
        this.priceMonthlyCents = priceMonthlyCents;
        this.monthlyLinkLimit = monthlyLinkLimit;
        this.customAliasAllowed = customAliasAllowed;
        this.maxExpiryDays = maxExpiryDays;
        this.csvExportAllowed = csvExportAllowed;
        this.tagline = tagline;
    }

    public String getDisplayName()        { return displayName; }
    public int getPriceMonthlyCents()     { return priceMonthlyCents; }
    public int getMonthlyLinkLimit()      { return monthlyLinkLimit; }
    public boolean isCustomAliasAllowed() { return customAliasAllowed; }
    public Integer getMaxExpiryDays()     { return maxExpiryDays; }
    public boolean isCsvExportAllowed()   { return csvExportAllowed; }
    public String getTagline()            { return tagline; }

    public boolean hasLinkLimit() { return monthlyLinkLimit != NO_LIMIT; }

    /** True if links on this plan may be created without an expiry. */
    public boolean allowsNeverExpire() { return maxExpiryDays == null; }

    /** Cheapest plan that allows the given expiry (days), used for "upgrade to X" hints. */
    public static SubscriptionPlan cheapestAllowingExpiry(int days) {
        for (SubscriptionPlan p : values()) {
            if (p.maxExpiryDays == null || days <= p.maxExpiryDays) return p;
        }
        return BUSINESS;
    }

    /** Human-readable feature bullets for the pricing cards. */
    public List<String> featureList() {
        return List.of(
                hasLinkLimit() ? monthlyLinkLimit + " new links per month" : "Unlimited new links",
                customAliasAllowed ? "Custom short codes (aliases)" : "Random short codes only",
                maxExpiryDays == null ? "Links that never expire"
                        : "Links expire after up to " + maxExpiryDays + " days",
                csvExportAllowed ? "Export your dispatches to CSV" : "No CSV export",
                "Click tracking with management keys",
                "Safe Browsing link protection"
        );
    }

    public static SubscriptionPlan fromString(String value) {
        if (value == null) throw new IllegalArgumentException("plan is required");
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown plan '" + value + "'. Choose FREE, PRO or BUSINESS.");
        }
    }
}
