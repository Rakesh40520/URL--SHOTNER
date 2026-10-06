package com.example.urlshortener.service;

import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.SubscriptionResponse;
import com.example.urlshortener.entity.SubscriptionPlan;
import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.entity.User;
import com.example.urlshortener.exception.ForbiddenException;
import com.example.urlshortener.exception.PlanLimitException;
import com.example.urlshortener.repository.UrlMappingRepository;
import com.example.urlshortener.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UrlMappingRepository urlRepository;

    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(userRepository, urlRepository, true);
    }

    private User user(SubscriptionPlan plan) {
        return User.builder().id(7L).name("Ada").email("ada@example.com")
                .passwordHash("x").createdAt(LocalDateTime.now()).plan(plan).build();
    }

    private ShortenRequest request(String alias, Integer days) {
        ShortenRequest r = new ShortenRequest();
        r.setLongUrl("https://example.com");
        r.setCustomAlias(alias);
        r.setExpiresInDays(days);
        return r;
    }

    private void usage(long used) {
        when(urlRepository.countByUser_IdAndCreatedAtGreaterThanEqual(anyLong(), any())).thenReturn(used);
    }

    @Test
    void accountWithNoPlanIsTreatedAsFree() {
        assertEquals(SubscriptionPlan.FREE, user(null).getEffectivePlan());
    }

    @Test
    void anonymousCallerIsNeverRestricted() {
        assertDoesNotThrow(() -> service.applyPlanRules(request("my-alias", 9999), null));
    }

    @Test
    void free_customAlias_isRejectedWithProAsRequiredPlan() {
        PlanLimitException ex = assertThrows(PlanLimitException.class,
                () -> service.applyPlanRules(request("my-alias", 7), user(SubscriptionPlan.FREE)));
        assertEquals(PlanLimitException.FEATURE_LOCKED, ex.getCode());
        assertEquals(SubscriptionPlan.PRO, ex.getRequiredPlan());
    }

    @Test
    void free_expiryBeyondMax_isRejected_andSuggestsCheapestPlanThatAllowsIt() {
        PlanLimitException ex = assertThrows(PlanLimitException.class,
                () -> service.applyPlanRules(request(null, 90), user(SubscriptionPlan.FREE)));
        assertEquals(SubscriptionPlan.PRO, ex.getRequiredPlan());
    }

    @Test
    void free_withNoExpiry_getsDefaultedToPlanMax() {
        usage(0);
        ShortenRequest r = request(null, null);
        service.applyPlanRules(r, user(SubscriptionPlan.FREE));
        assertEquals(30, r.getExpiresInDays());
    }

    @Test
    void free_atMonthlyQuota_isRejected() {
        usage(20);
        PlanLimitException ex = assertThrows(PlanLimitException.class,
                () -> service.applyPlanRules(request(null, 7), user(SubscriptionPlan.FREE)));
        assertEquals(PlanLimitException.LIMIT_REACHED, ex.getCode());
    }

    @Test
    void free_belowQuota_isAllowed() {
        usage(19);
        assertDoesNotThrow(() -> service.applyPlanRules(request(null, 7), user(SubscriptionPlan.FREE)));
    }

    @Test
    void pro_canUseAliasAndLongExpiry_butNotNeverExpire() {
        usage(0);
        assertDoesNotThrow(() -> service.applyPlanRules(request("my-alias", 365), user(SubscriptionPlan.PRO)));

        ShortenRequest noExpiry = request(null, null);
        service.applyPlanRules(noExpiry, user(SubscriptionPlan.PRO));
        assertEquals(365, noExpiry.getExpiresInDays());
    }

    @Test
    void business_isUnlimitedAndLeavesExpiryAlone() {
        ShortenRequest r = request("team-link", null);
        assertDoesNotThrow(() -> service.applyPlanRules(r, user(SubscriptionPlan.BUSINESS)));
        assertNull(r.getExpiresInDays());
        verify(urlRepository, never()).countByUser_IdAndCreatedAtGreaterThanEqual(anyLong(), any());
    }

    @Test
    void getMySubscription_reportsUsageAndRemaining() {
        usage(5);
        SubscriptionResponse res = service.getMySubscription(user(SubscriptionPlan.FREE));
        assertEquals(5, res.getLinksUsedThisMonth());
        assertEquals(15, res.getLinksRemaining());
        assertEquals("FREE", res.getPlan().getKey());
    }

    @Test
    void changePlan_updatesAndPersistsTheUser() {
        User u = user(SubscriptionPlan.FREE);
        when(userRepository.findById(7L)).thenReturn(Optional.of(u));
        usage(0);

        SubscriptionResponse res = service.changePlan(u, "pro");

        assertEquals("PRO", res.getPlan().getKey());
        assertEquals(SubscriptionPlan.PRO, u.getPlan());
        assertNotNull(u.getPlanChangedAt());
        verify(userRepository).save(u);
    }

    @Test
    void changePlan_unknownPlan_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.changePlan(user(SubscriptionPlan.FREE), "platinum"));
    }

    @Test
    void changePlan_whenSelfServiceDisabled_isForbidden() {
        SubscriptionService locked = new SubscriptionService(userRepository, urlRepository, false);
        assertThrows(ForbiddenException.class, () -> locked.changePlan(user(SubscriptionPlan.FREE), "PRO"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void exportCsv_isLockedOnFree() {
        assertThrows(PlanLimitException.class, () -> service.exportCsv(user(SubscriptionPlan.FREE), "https://x.io"));
    }

    @Test
    void exportCsv_escapesQuotesAndDefusesFormulaInjection() {
        UrlMapping m = UrlMapping.builder().longUrl("=HYPERLINK(\"http://evil\")").shortCode("abc1234")
                .createdAt(LocalDateTime.of(2026, 10, 1, 9, 0)).clickCount(3).build();
        when(urlRepository.findByUser_IdOrderByCreatedAtDesc(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(m)));

        String csv = service.exportCsv(user(SubscriptionPlan.PRO), "https://x.io");

        assertTrue(csv.startsWith("short_code,short_url,destination,status,clicks,created_at,expires_at\n"));
        assertTrue(csv.contains("\"https://x.io/abc1234\""));
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://evil\"\")\""), csv);
        assertTrue(csv.contains(",ACTIVE,3,"));
    }
}
