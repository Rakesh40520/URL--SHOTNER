package com.example.urlshortener.service;

import com.example.urlshortener.dto.ClickResponse;
import com.example.urlshortener.dto.ReportResponse;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.dto.UrlStatsResponse;
import com.example.urlshortener.dto.UrlSummaryResponse;
import com.example.urlshortener.entity.UrlClickEvent;
import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.entity.UrlStatus;
import com.example.urlshortener.entity.User;
import com.example.urlshortener.exception.AliasAlreadyExistsException;
import com.example.urlshortener.exception.ForbiddenException;
import com.example.urlshortener.exception.InvalidManagementKeyException;
import com.example.urlshortener.exception.InvalidUrlException;
import com.example.urlshortener.exception.UnsafeUrlException;
import com.example.urlshortener.exception.UrlBlockedException;
import com.example.urlshortener.exception.UrlNotFoundException;
import com.example.urlshortener.repository.UrlClickEventRepository;
import com.example.urlshortener.repository.UrlMappingRepository;
import com.example.urlshortener.safebrowsing.SafeBrowsingClient;
import com.example.urlshortener.safebrowsing.SafeBrowsingResult;
import com.example.urlshortener.sharding.ShardResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    private static final String BASE_URL = "http://localhost:8080";

    @Mock
    private UrlMappingRepository repository;

    @Mock
    private UrlClickEventRepository clickEventRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SafeBrowsingClient safeBrowsingClient;

    // Unlike cacheManager/clickEventPublisher/meterRegistry (all
    // constructor-injected but explicitly null-checked before use inside
    // the service, so a plain Mockito test leaving them unmocked is safe -
    // see the service's own comments on those fields), every shard-aware
    // method calls shardResolver.resolve(...) completely unconditionally.
    // Leaving this unmocked means @InjectMocks passes null for it and
    // EVERY test that reaches shorten()/resolve()/report()/setLinkEnabled()/
    // getStats()/getClicks()/deleteUrl() NPEs immediately - this mock is
    // not optional the way the others are.
    @Mock
    private ShardResolver shardResolver;

    @InjectMocks
    private UrlShortenerService service;

    // @Value fields aren't populated by Mockito (there's no Spring context
    // in this test), and a plain Mockito mock's check() returns null unless
    // stubbed - so every test that reaches shorten() needs both handled.
    // Individual safety tests below override these defaults as needed.
    @BeforeEach
    void setUpSafeBrowsingDefaults() {
        lenient().when(safeBrowsingClient.check(anyString())).thenReturn(SafeBrowsingResult.safe());
        ReflectionTestUtils.setField(service, "safeBrowsingFailOpen", true);
        ReflectionTestUtils.setField(service, "reportAutoDisableThreshold", 3);
        // Single-shard behavior by default (every code resolves to shard 0)
        // so existing tests, written before sharding existed, don't need to
        // know or care about it. Tests that specifically exercise sharding
        // behavior can override this per-test.
        lenient().when(shardResolver.resolve(anyString())).thenReturn(0);
    }

    private static User user(long id) {
        return User.builder().id(id).name("Ada").email("ada@example.com")
                .passwordHash("hashed").createdAt(LocalDateTime.now()).build();
    }

    @Test
    void shorten_withCustomAlias_savesAndReturnsIt() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://example.com/some/very/long/path");
        request.setCustomAlias("my-alias");

        when(repository.existsByShortCode("my-alias")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-key");
        when(repository.saveAndFlush(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortenResponse response = service.shorten(request, BASE_URL, null);

        assertEquals("my-alias", response.getShortCode());
        assertEquals("http://localhost:8080/my-alias", response.getShortUrl());
        assertNotNull(response.getManagementKey()); // the RAW key, returned exactly once
        verify(repository).saveAndFlush(any(UrlMapping.class));
    }

    @Test
    void shorten_asLoggedInUser_attachesOwner() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://example.com/owned");
        User owner = user(7L);

        when(passwordEncoder.encode(anyString())).thenReturn("hashed-key");
        when(repository.existsByShortCode(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(UrlMapping.class))).thenAnswer(inv -> {
            UrlMapping m = inv.getArgument(0);
            m.setId(1L);
            return m;
        });

        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);

        service.shorten(request, BASE_URL, owner);

        verify(repository).saveAndFlush(captor.capture());
        assertEquals(owner, captor.getValue().getUser());
        assertNotNull(captor.getValue().getManagementKeyHash());
    }

    @Test
    void shorten_anonymously_leavesOwnerNull() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://example.com/anon");

        when(passwordEncoder.encode(anyString())).thenReturn("hashed-key");
        when(repository.existsByShortCode(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(UrlMapping.class))).thenAnswer(inv -> {
            UrlMapping m = inv.getArgument(0);
            m.setId(2L);
            return m;
        });

        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);

        service.shorten(request, BASE_URL, null);

        verify(repository).saveAndFlush(captor.capture());
        assertNull(captor.getValue().getUser());
    }

    @Test
    void shorten_withTakenAlias_throwsConflict() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://example.com");
        request.setCustomAlias("taken");

        when(repository.existsByShortCode("taken")).thenReturn(true);

        assertThrows(AliasAlreadyExistsException.class, () -> service.shorten(request, BASE_URL, null));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void shorten_withAliasRaceCondition_dbConstraintAlsoRejectsAsConflict() {
        // Simulates two concurrent requests for the same alias both passing
        // the pre-check before either commits: the pre-check says "free",
        // but the database's unique constraint rejects the actual insert.
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://example.com");
        request.setCustomAlias("race-condition");

        when(repository.existsByShortCode("race-condition")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-key");
        when(repository.saveAndFlush(any(UrlMapping.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violated"));

        assertThrows(AliasAlreadyExistsException.class, () -> service.shorten(request, BASE_URL, null));
    }

    @Test
    void shorten_withoutAlias_generatesRandomCodeInOneWrite() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://example.com/x");

        when(passwordEncoder.encode(anyString())).thenReturn("hashed-key");
        when(repository.existsByShortCode(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(UrlMapping.class))).thenAnswer(inv -> {
            UrlMapping m = inv.getArgument(0);
            m.setId(125L);
            return m;
        });

        ShortenResponse response = service.shorten(request, BASE_URL, null);

        assertNotNull(response.getShortCode());
        assertEquals(7, response.getShortCode().length());
        // Exactly one write: no more "insert a placeholder, then update it"
        // second round trip.
        verify(repository, times(1)).saveAndFlush(any(UrlMapping.class));
        verify(repository, never()).save(any(UrlMapping.class));
    }

    @Test
    void shorten_withoutAlias_retriesOnRandomCodeCollision() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://example.com/y");

        when(passwordEncoder.encode(anyString())).thenReturn("hashed-key");
        // First generated code "collides", second is free.
        when(repository.existsByShortCode(anyString())).thenReturn(true, false);
        when(repository.saveAndFlush(any(UrlMapping.class))).thenAnswer(inv -> {
            UrlMapping m = inv.getArgument(0);
            m.setId(126L);
            return m;
        });

        ShortenResponse response = service.shorten(request, BASE_URL, null);

        assertNotNull(response.getShortCode());
        verify(repository, times(2)).existsByShortCode(anyString());
        verify(repository, times(1)).saveAndFlush(any(UrlMapping.class));
    }

    @Test
    void shorten_withoutAlias_exhaustsAttempts_throwsIllegalState() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://example.com/z");

        when(passwordEncoder.encode(anyString())).thenReturn("hashed-key");
        when(repository.existsByShortCode(anyString())).thenReturn(true); // always collides

        assertThrows(IllegalStateException.class, () -> service.shorten(request, BASE_URL, null));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void shorten_urlPointingBackToThisServer_isRejected() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl(BASE_URL + "/someOtherCode");

        assertThrows(InvalidUrlException.class, () -> service.shorten(request, BASE_URL, null));
        verifyNoInteractions(repository);
    }

    @Test
    void resolve_unknownCode_throwsNotFound() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());
        assertThrows(UrlNotFoundException.class, () -> service.resolve("missing", "127.0.0.1"));
    }

    @Test
    void resolve_validCode_incrementsCountAndRecordsClickEvent() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L)
                .shortCode("abc123")
                .longUrl("https://example.com")
                .createdAt(LocalDateTime.now())
                .clickCount(4L)
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        String longUrl = service.resolve("abc123", "203.0.113.5");

        assertEquals("https://example.com", longUrl);
        assertEquals(5L, mapping.getClickCount());
        verify(clickEventRepository).save(argThat(event ->
                event.getShortCode().equals("abc123") && "203.0.113.5".equals(event.getIpAddress())));
    }

    // ── Management key / analytics access control ──────────────────────────

    @Test
    void getStats_correctManagementKey_succeeds() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).managementKeyHash("hashed-key")
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));
        when(passwordEncoder.matches("right-key", "hashed-key")).thenReturn(true);

        UrlStatsResponse response = service.getStats("abc123", "right-key", null);

        assertEquals("abc123", response.getShortCode());
    }

    @Test
    void getStats_wrongManagementKey_isRejected() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).managementKeyHash("hashed-key")
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));
        when(passwordEncoder.matches("wrong-key", "hashed-key")).thenReturn(false);

        assertThrows(InvalidManagementKeyException.class, () -> service.getStats("abc123", "wrong-key", null));
    }

    @Test
    void getStats_noKeyAndNotOwner_isRejected() {
        // A stranger who merely knows/guessed the short code, with no key
        // and no login, should NOT be able to see this link's stats - even
        // though the short code itself would still redirect for anyone.
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).managementKeyHash("hashed-key")
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        assertThrows(InvalidManagementKeyException.class, () -> service.getStats("abc123", null, null));
    }

    @Test
    void getStats_authenticatedOwner_succeedsWithoutKey() {
        User owner = user(3L);
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).user(owner).managementKeyHash("hashed-key")
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        UrlStatsResponse response = service.getStats("abc123", null, owner);

        assertEquals("abc123", response.getShortCode());
    }

    @Test
    void getStats_loggedInAsDifferentUser_stillNeedsKey() {
        // Being logged in isn't enough on its own - you must own THIS link.
        User owner = user(3L);
        User someoneElse = user(4L);
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).user(owner).managementKeyHash("hashed-key")
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        assertThrows(InvalidManagementKeyException.class,
                () -> service.getStats("abc123", null, someoneElse));
    }

    @Test
    void getClicks_unknownCode_throwsNotFound() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());
        Pageable pageable = PageRequest.of(0, 20);
        assertThrows(UrlNotFoundException.class, () -> service.getClicks("missing", pageable, null, null));
    }

    @Test
    void getClicks_ownerCanViewWithoutKey() {
        Pageable pageable = PageRequest.of(0, 20);
        User owner = user(3L);
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).user(owner).managementKeyHash("hashed-key")
                .build();
        UrlClickEvent event = UrlClickEvent.builder()
                .id(1L).shortCode("abc123").clickedAt(LocalDateTime.now()).ipAddress("203.0.113.5")
                .build();
        Page<UrlClickEvent> page = new PageImpl<>(List.of(event), pageable, 1);

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));
        when(clickEventRepository.findByShortCodeOrderByClickedAtDesc(eq("abc123"), eq(pageable)))
                .thenReturn(page);

        Page<ClickResponse> result = service.getClicks("abc123", pageable, null, owner);

        assertEquals(1, result.getTotalElements());
        assertEquals("203.0.113.5", result.getContent().get(0).getIpAddress());
    }

    @Test
    void getClicks_strangerWithoutKey_isRejected() {
        Pageable pageable = PageRequest.of(0, 20);
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).managementKeyHash("hashed-key")
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        assertThrows(InvalidManagementKeyException.class,
                () -> service.getClicks("abc123", pageable, null, null));
    }

    @Test
    void getMyUrls_returnsOnlyThatUsersUrls() {
        User owner = user(3L);
        UrlMapping mapping = UrlMapping.builder()
                .id(10L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).clickCount(2L).user(owner)
                .build();

        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findByUser_IdOrderByCreatedAtDesc(3L, pageable))
                .thenReturn(new PageImpl<>(List.of(mapping)));

        Page<UrlSummaryResponse> result = service.getMyUrls(owner, BASE_URL, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("abc123", result.getContent().get(0).getShortCode());
        assertEquals("Active", result.getContent().get(0).getStatus());
    }

    @Test
    void getMyUrlsSummary_computesCountsFromAggregateQueries() {
        User owner = user(4L);
        when(repository.countByUser_Id(4L)).thenReturn(5L);
        when(repository.countByUser_IdAndExpiresAtBefore(eq(4L), any(LocalDateTime.class))).thenReturn(2L);
        when(repository.countByUser_IdAndStatusNotAndNotExpired(eq(4L), eq(UrlStatus.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(1L);
        when(repository.sumClickCountByUser_Id(4L)).thenReturn(37L);

        var summary = service.getMyUrlsSummary(owner);

        assertEquals(5, summary.getTotal());
        assertEquals(2, summary.getExpired());
        assertEquals(1, summary.getBlocked());
        assertEquals(2, summary.getActive()); // 5 total - 2 expired - 1 blocked
        assertEquals(37, summary.getTotalClicks());
    }

    @Test
    void deleteUrl_ownerDeletingOwnUrl_succeeds() {
        User owner = user(5L);
        UrlMapping mapping = UrlMapping.builder()
                .id(20L).shortCode("mine").longUrl("https://example.com").user(owner)
                .createdAt(LocalDateTime.now()).build();

        when(repository.findByShortCode("mine")).thenReturn(Optional.of(mapping));

        service.deleteUrl("mine", owner);

        verify(repository).delete(mapping);
    }

    @Test
    void deleteUrl_differentUser_throwsForbidden() {
        User owner = user(5L);
        User someoneElse = user(6L);
        UrlMapping mapping = UrlMapping.builder()
                .id(21L).shortCode("mine").longUrl("https://example.com").user(owner)
                .createdAt(LocalDateTime.now()).build();

        when(repository.findByShortCode("mine")).thenReturn(Optional.of(mapping));

        assertThrows(ForbiddenException.class, () -> service.deleteUrl("mine", someoneElse));
        verify(repository, never()).delete(any(UrlMapping.class));
    }

    @Test
    void deleteUrl_anonymousUrl_cannotBeDeletedByAnyAccount() {
        User someone = user(9L);
        UrlMapping mapping = UrlMapping.builder()
                .id(22L).shortCode("anon").longUrl("https://example.com").user(null)
                .createdAt(LocalDateTime.now()).build();

        when(repository.findByShortCode("anon")).thenReturn(Optional.of(mapping));

        assertThrows(ForbiddenException.class, () -> service.deleteUrl("anon", someone));
    }

    // ── Safe Browsing / abuse protection ────────────────────────────────────

    @Test
    void shorten_flaggedBySafeBrowsing_isRejected() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://malicious.example.com/phish");

        when(safeBrowsingClient.check("https://malicious.example.com/phish"))
                .thenReturn(SafeBrowsingResult.unsafe(List.of("SOCIAL_ENGINEERING")));

        assertThrows(UnsafeUrlException.class, () -> service.shorten(request, BASE_URL, null));
        verifyNoInteractions(repository);
    }

    @Test
    void shorten_safeBrowsingUnavailable_failOpen_allowsCreation() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://example.com/fine");

        when(safeBrowsingClient.check(anyString())).thenReturn(SafeBrowsingResult.unavailable());
        ReflectionTestUtils.setField(service, "safeBrowsingFailOpen", true);
        when(repository.existsByShortCode(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-key");
        when(repository.saveAndFlush(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> service.shorten(request, BASE_URL, null));
    }

    @Test
    void shorten_safeBrowsingUnavailable_failClosed_blocksCreation() {
        ShortenRequest request = new ShortenRequest();
        request.setLongUrl("https://example.com/fine");

        when(safeBrowsingClient.check(anyString())).thenReturn(SafeBrowsingResult.unavailable());
        ReflectionTestUtils.setField(service, "safeBrowsingFailOpen", false);

        assertThrows(UnsafeUrlException.class, () -> service.shorten(request, BASE_URL, null));
        verifyNoInteractions(repository);
    }

    @Test
    void resolve_disabledLink_throwsUrlBlockedException_andDoesNotCountClick() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("blocked").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).clickCount(4L)
                .status(UrlStatus.DISABLED).statusReason("Disabled by owner")
                .build();

        when(repository.findByShortCode("blocked")).thenReturn(Optional.of(mapping));

        assertThrows(UrlBlockedException.class, () -> service.resolve("blocked", "203.0.113.5"));
        assertEquals(4L, mapping.getClickCount()); // unchanged
        verifyNoInteractions(clickEventRepository);
    }

    @Test
    void resolve_flaggedLink_throwsUrlBlockedException() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("flagged").longUrl("https://example.com")
                .createdAt(LocalDateTime.now())
                .status(UrlStatus.FLAGGED).statusReason("Flagged by scheduled Safe Browsing re-check")
                .build();

        when(repository.findByShortCode("flagged")).thenReturn(Optional.of(mapping));

        assertThrows(UrlBlockedException.class, () -> service.resolve("flagged", "203.0.113.5"));
    }

    @Test
    void report_belowThreshold_incrementsButDoesNotFlag() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).reportCount(1)
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        ReportResponse response = service.report("abc123");

        assertEquals(2, response.getReportCount());
        assertFalse(response.isAutoFlagged());
        assertEquals(UrlStatus.ACTIVE, mapping.getStatus());
    }

    @Test
    void report_reachesThreshold_autoFlagsLink() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).reportCount(2) // threshold is 3 - see setUp
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        ReportResponse response = service.report("abc123");

        assertEquals(3, response.getReportCount());
        assertTrue(response.isAutoFlagged());
        assertEquals(UrlStatus.FLAGGED, mapping.getStatus());
        assertNotNull(mapping.getStatusReason());
    }

    @Test
    void report_unknownCode_throwsNotFound() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());
        assertThrows(UrlNotFoundException.class, () -> service.report("missing"));
    }

    @Test
    void setLinkEnabled_ownerDisablesOwnLink_succeeds() {
        User owner = user(3L);
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).user(owner)
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        service.setLinkEnabled("abc123", false, null, owner);

        assertEquals(UrlStatus.DISABLED, mapping.getStatus());
        assertNotNull(mapping.getStatusReason());
        verify(repository).save(mapping);
    }

    @Test
    void setLinkEnabled_ownerReEnablesLink_clearsStatusAndReason() {
        User owner = user(3L);
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).user(owner)
                .status(UrlStatus.FLAGGED).statusReason("Flagged by scheduled Safe Browsing re-check")
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        service.setLinkEnabled("abc123", true, null, owner);

        assertEquals(UrlStatus.ACTIVE, mapping.getStatus());
        assertNull(mapping.getStatusReason());
    }

    @Test
    void setLinkEnabled_nonOwnerWithoutKey_isRejected() {
        User owner = user(3L);
        User someoneElse = user(4L);
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).user(owner)
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        assertThrows(InvalidManagementKeyException.class,
                () -> service.setLinkEnabled("abc123", false, null, someoneElse));
        verify(repository, never()).save(any(UrlMapping.class));
    }

    @Test
    void setLinkEnabled_anonymousLinkWithCorrectManagementKey_succeeds() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L).shortCode("abc123").longUrl("https://example.com")
                .createdAt(LocalDateTime.now()).managementKeyHash("hashed-key")
                .build();

        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));
        when(passwordEncoder.matches("right-key", "hashed-key")).thenReturn(true);

        service.setLinkEnabled("abc123", false, "right-key", null);

        assertEquals(UrlStatus.DISABLED, mapping.getStatus());
    }
}
