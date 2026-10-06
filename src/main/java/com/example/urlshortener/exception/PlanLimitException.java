package com.example.urlshortener.exception;

import com.example.urlshortener.entity.SubscriptionPlan;

/**
 * Thrown when an action isn't allowed on the user's current subscription plan
 * (monthly quota used up, or a feature their tier doesn't include). Surfaces as
 * HTTP 403 with a machine-readable "code" and the cheapest plan that would
 * unlock the action, so the UI can show a precise upgrade prompt.
 */
public class PlanLimitException extends RuntimeException {

    public static final String LIMIT_REACHED  = "PLAN_LIMIT_REACHED";
    public static final String FEATURE_LOCKED = "PLAN_FEATURE_LOCKED";

    private final String code;
    private final SubscriptionPlan requiredPlan;

    public PlanLimitException(String code, String message, SubscriptionPlan requiredPlan) {
        super(message);
        this.code = code;
        this.requiredPlan = requiredPlan;
    }

    public String getCode() { return code; }
    public SubscriptionPlan getRequiredPlan() { return requiredPlan; }
}
