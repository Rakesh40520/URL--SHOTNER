package com.example.urlshortener.service;

import com.example.urlshortener.dto.PlanResponse;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.SubscriptionResponse;
import com.example.urlshortener.entity.SubscriptionPlan;
import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.entity.User;
import com.example.urlshortener.exception.ForbiddenException;
import com.example.urlshortener.exception.PlanLimitException;
import com.example.urlshortener.repository.UrlMappingRepository;
import com.example.urlshortener.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Subscription plans: listing tiers, reporting a user's usage, switching plans,
 * and enforcing plan rules before a link is created.
 *
 * <p>Payments are NOT integrated. changePlan() is a self-service switch guarded
 * by app.subscription.self-service-enabled (default true, for demos). Before
 * charging real money, set it to false and call changePlan() from a verified
 * payment webhook (Stripe/Razorpay) instead of from the browser.
 *
 * <p>Plan rules apply to signed-in users only; anonymous shortening keeps its
 * original behavior (see applyPlanRules).
 */
@Service
public class SubscriptionService {

    private final UserRepository userRepository;
    private final UrlMappingRepository urlRepository;
    private final boolean selfServiceEnabled;

    public SubscriptionService(UserRepository userRepository,
                               UrlMappingRepository urlRepository,
                               @Value("${app.subscription.self-service-enabled:true}") boolean selfServiceEnabled) {
        this.userRepository = userRepository;
        this.urlRepository = urlRepository;
        this.selfServiceEnabled = selfServiceEnabled;
    }

    public List<PlanResponse> listPlans() {
        return Arrays.stream(SubscriptionPlan.values()).map(PlanResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getMySubscription(User user) {
        SubscriptionPlan plan = user.getEffectivePlan();
        long used = linksUsedThisMonth(user);
        boolean unlimited = !plan.hasLinkLimit();

        return SubscriptionResponse.builder()
                .plan(PlanResponse.from(plan))
                .linksUsedThisMonth(used)
                .monthlyLinkLimit(plan.getMonthlyLinkLimit())
                .linksRemaining(unlimited ? -1 : Math.max(0, plan.getMonthlyLinkLimit() - used))
                .quotaResetsOn(startOfMonth().toLocalDate().plusMonths(1))
                .planChangedAt(user.getPlanChangedAt())
                .selfServiceEnabled(selfServiceEnabled)
                .build();
    }

    @Transactional
    public SubscriptionResponse changePlan(User principal, String planName) {
        if (!selfServiceEnabled) {
            throw new ForbiddenException("Plan changes must go through checkout.");
        }
        SubscriptionPlan target = SubscriptionPlan.fromString(planName);

        // Reload instead of mutating the principal: it was loaded by the JWT
        // filter outside this transaction and is detached.
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ForbiddenException("Account not found."));

        if (user.getEffectivePlan() != target) {
            user.setPlan(target);
            user.setPlanChangedAt(LocalDateTime.now());
            userRepository.save(user);
        }
        return getMySubscription(user);
    }

    /**
     * Checks a create-link request against the signed-in user's plan, and
     * applies plan defaults (e.g. Free links always get an expiry). Throws
     * PlanLimitException if the request isn't allowed. No-op for anonymous
     * callers (user == null).
     */
    @Transactional(readOnly = true)
    public void applyPlanRules(ShortenRequest request, User user) {
        if (user == null) return;
        SubscriptionPlan plan = user.getEffectivePlan();

        String alias = request.getCustomAlias();
        if (alias != null && !alias.isBlank() && !plan.isCustomAliasAllowed()) {
            throw new PlanLimitException(PlanLimitException.FEATURE_LOCKED,
                    "Custom short codes are available on the Pro and Business plans.",
                    SubscriptionPlan.PRO);
        }

        Integer maxDays = plan.getMaxExpiryDays();
        if (maxDays != null) {
            Integer requested = request.getExpiresInDays();
            if (requested == null) {
                request.setExpiresInDays(maxDays);        // no "never expires" on this plan
            } else if (requested > maxDays) {
                throw new PlanLimitException(PlanLimitException.FEATURE_LOCKED,
                        "The " + plan.getDisplayName() + " plan allows links to last up to "
                                + maxDays + " days.",
                        SubscriptionPlan.cheapestAllowingExpiry(requested));
            }
        }

        if (plan.hasLinkLimit() && linksUsedThisMonth(user) >= plan.getMonthlyLinkLimit()) {
            SubscriptionPlan next = plan == SubscriptionPlan.FREE ? SubscriptionPlan.PRO : SubscriptionPlan.BUSINESS;
            throw new PlanLimitException(PlanLimitException.LIMIT_REACHED,
                    "You've used all " + plan.getMonthlyLinkLimit() + " links on the "
                            + plan.getDisplayName() + " plan this month. Upgrade for more.",
                    next);
        }
    }

    /** All of the user's links as CSV. Pro/Business only. */
    @Transactional(readOnly = true)
    public String exportCsv(User user, String baseUrl) {
        if (!user.getEffectivePlan().isCsvExportAllowed()) {
            throw new PlanLimitException(PlanLimitException.FEATURE_LOCKED,
                    "CSV export is available on the Pro and Business plans.", SubscriptionPlan.PRO);
        }

        StringBuilder csv = new StringBuilder("short_code,short_url,destination,status,clicks,created_at,expires_at\n");
        int page = 0;
        Page<UrlMapping> batch;
        do {
            batch = urlRepository.findByUser_IdOrderByCreatedAtDesc(user.getId(), PageRequest.of(page++, 500));
            for (UrlMapping m : batch.getContent()) {
                String status = m.isExpired() ? "EXPIRED" : m.getStatus().name();
                csv.append(cell(m.getShortCode())).append(',')
                   .append(cell(baseUrl + "/" + m.getShortCode())).append(',')
                   .append(cell(m.getLongUrl())).append(',')
                   .append(status).append(',')
                   .append(m.getClickCount()).append(',')
                   .append(m.getCreatedAt()).append(',')
                   .append(m.getExpiresAt() == null ? "" : m.getExpiresAt()).append('\n');
            }
        } while (batch.hasNext());
        return csv.toString();
    }

    private long linksUsedThisMonth(User user) {
        return urlRepository.countByUser_IdAndCreatedAtGreaterThanEqual(user.getId(), startOfMonth());
    }

    private static LocalDateTime startOfMonth() {
        return LocalDate.now().withDayOfMonth(1).atStartOfDay();
    }

    /** Quotes the value, and defuses spreadsheet formula injection (=, +, -, @ prefixes). */
    private static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
