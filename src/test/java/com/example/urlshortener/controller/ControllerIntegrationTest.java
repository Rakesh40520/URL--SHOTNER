package com.example.urlshortener.controller;

import com.example.urlshortener.entity.User;
import com.example.urlshortener.mail.EmailSender;
import com.example.urlshortener.repository.UrlClickEventRepository;
import com.example.urlshortener.repository.UrlMappingRepository;
import com.example.urlshortener.repository.UserRepository;
import com.example.urlshortener.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration tests using an in-memory H2 database (same config
 * as the default application profile). Each test class gets a fresh
 * application context via @DirtiesContext so rows created in one test don't
 * bleed into another.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ControllerIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UrlMappingRepository urlMappingRepository;
    @Autowired private UrlClickEventRepository urlClickEventRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    // Replaces the real sender so tests can read the emailed code without sending anything.
    @MockBean private EmailSender emailSender;

    private static final String EMAIL    = "ada@example.com";
    private static final String PASSWORD = "password123";
    private static final String NAME     = "Ada";

    private User savedUser;

    @BeforeEach
    void seedUser() {
        urlClickEventRepository.deleteAll();
        urlMappingRepository.deleteAll();
        userRepository.deleteAll();
        savedUser = userRepository.save(User.builder()
                .name(NAME).email(EMAIL)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .createdAt(LocalDateTime.now())
                .build());
    }

    // ── Registration ─────────────────────────────────────────────────────────

    @Test
    void register_validRequest_returns201_withNoToken_andEmailsACode() throws Exception {
        Map<String, String> body = Map.of(
                "name", "Grace", "email", "grace@example.com",
                "password", "password123", "confirmPassword", "password123");

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verificationRequired").value(true))
                .andExpect(jsonPath("$.email").value("grace@example.com"))
                .andExpect(jsonPath("$.token").doesNotExist());

        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE,
                userRepository.findByEmail("grace@example.com").orElseThrow().getEmailVerified());
        emailedCode();   // an email with a 6-digit code was sent
    }

    @Test
    void fullSignupFlow_loginBlockedUntilCodeVerified() throws Exception {
        register("grace@example.com");
        String code = emailedCode();

        // Right password, but not verified yet -> 403 EMAIL_NOT_VERIFIED
        login("grace@example.com", "password123")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.token").doesNotExist());

        // Wrong code -> 400 with attempts left, still blocked
        String wrong = code.equals("000000") ? "111111" : "000000";
        verifyEmail("grace@example.com", wrong)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"))
                .andExpect(jsonPath("$.attemptsLeft").value(4));

        // Right code -> verified and logged in
        verifyEmail("grace@example.com", code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // Now a normal login works, and the code can't be replayed
        login("grace@example.com", "password123").andExpect(status().isOk());
        verifyEmail("grace@example.com", code).andExpect(status().isBadRequest());
    }

    @Test
    void resendCode_rightAfterRegistering_isRateLimited() throws Exception {
        register("grace@example.com");

        mvc.perform(post("/api/v1/auth/resend-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"grace@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CODE_RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void forgotAndResetPassword_flow() throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isOk());
        String code = emailedCode();

        mvc.perform(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", EMAIL, "code", code,
                                "newPassword", "a-brand-new-pass", "confirmPassword", "a-brand-new-pass"))))
                .andExpect(status().isOk());

        login(EMAIL, "a-brand-new-pass").andExpect(status().isOk());
        login(EMAIL, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void forgotPassword_unknownEmail_getsTheSameAnswer_andNothingIsSent() throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
        verify(emailSender, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void resetPassword_withWrongCode_isRejected_andPasswordUnchanged() throws Exception {
        mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\"}"));
        String code = emailedCode();
        String wrong = code.equals("000000") ? "111111" : "000000";

        mvc.perform(post("/api/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", EMAIL, "code", wrong,
                                "newPassword", "a-brand-new-pass", "confirmPassword", "a-brand-new-pass"))))
                .andExpect(status().isBadRequest());

        login(EMAIL, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void existingAccountsFromBeforeVerificationExisted_canStillLogIn() throws Exception {
        // The seeded user has no emailVerified value at all, like every pre-existing account.
        org.junit.jupiter.api.Assertions.assertNull(savedUser.getEmailVerified());
        login(EMAIL, PASSWORD).andExpect(status().isOk());
    }

    // helpers for the flows above
    private void register(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "Grace", "email", email,
                                "password", "password123", "confirmPassword", "password123"))))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private org.springframework.test.web.servlet.ResultActions verifyEmail(String email, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "code", code))));
    }

    /** Reads the 6-digit code out of the most recent email handed to the (mocked) sender. */
    private String emailedCode() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailSender, org.mockito.Mockito.atLeastOnce()).send(anyString(), anyString(), anyString(), text.capture());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(text.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(m.find(), "emailed text should contain a 6-digit code: " + text.getValue());
        return m.group(1);
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        Map<String, String> body = Map.of(
                "name", "Dup", "email", EMAIL,   // EMAIL is already seeded
                "password", "password123", "confirmPassword", "password123");

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void register_missingField_returns400() throws Exception {
        // Missing name
        Map<String, String> body = Map.of(
                "email", "x@example.com",
                "password", "password123", "confirmPassword", "password123");

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_malformedJson_returns400() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest());
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_correctCredentials_returns200WithToken() throws Exception {
        Map<String, String> body = Map.of("email", EMAIL, "password", PASSWORD);

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        Map<String, String> body = Map.of("email", EMAIL, "password", "WRONG");

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void login_unknownEmail_returns401() throws Exception {
        Map<String, String> body = Map.of("email", "nobody@example.com", "password", "anything");

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    // ── Protected endpoints ───────────────────────────────────────────────────

    @Test
    void myUrls_withNoToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/urls/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Please log in to continue."));
    }

    @Test
    void myUrls_withValidToken_returns200() throws Exception {
        String token = jwtService.generateToken(savedUser);

        mvc.perform(get("/api/v1/urls/my")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ── Click location / referrer analytics ──────────────────────────────────

    @Test
    void redirect_recordsReferrerHostAndRefTag_andAnalyticsAggregatesThem() throws Exception {
        String token = jwtService.generateToken(savedUser);
        String created = mvc.perform(post("/api/v1/urls").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com\",\"expiresInDays\":7}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = mapper.readTree(created).get("shortCode").asText();

        mvc.perform(get("/" + code).header("Referer", "https://www.twitter.com/x/status/1?t=secret").param("ref", "alice"))
                .andExpect(status().isFound());
        mvc.perform(get("/" + code).param("ref", "qr")).andExpect(status().isFound());
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        mvc.perform(get("/api/v1/urls/" + code + "/analytics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(3))
                .andExpect(jsonPath("$.topSources[?(@.label=='twitter.com')].count").value(1))
                .andExpect(jsonPath("$.topSources[?(@.label=='Direct / unknown')].count").value(2))
                .andExpect(jsonPath("$.topReferralTags[?(@.label=='alice')].count").value(1))
                .andExpect(jsonPath("$.topReferralTags[?(@.label=='qr')].count").value(1));
    }

    @Test
    void analytics_withoutOwnerOrKey_isForbidden() throws Exception {
        String created = mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com\"}"))
                .andReturn().getResponse().getContentAsString();
        String code = mapper.readTree(created).get("shortCode").asText();

        mvc.perform(get("/api/v1/urls/" + code + "/analytics")).andExpect(status().isForbidden());
    }

    // ── Stored management keys ───────────────────────────────────────────────

    @Test
    void ownedLink_keyIsStoredEncrypted_andReturnedInMyList() throws Exception {
        String token = jwtService.generateToken(savedUser);
        String created = mvc.perform(post("/api/v1/urls").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com\",\"expiresInDays\":7}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String key = mapper.readTree(created).get("managementKey").asText();

        mvc.perform(get("/api/v1/urls/my").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].managementKey").value(key));
    }

    @Test
    void anonymousLink_keyIsNeverStoredOnTheServer() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.managementKey").isNotEmpty());
        // nothing in any owner's list - there is no owner, so there is nowhere the key could be read back
        org.junit.jupiter.api.Assertions.assertTrue(
                urlMappingRepository.findAll().stream().allMatch(m -> m.getUser() != null
                        || m.getManagementKeyEnc() == null));
    }

    // ── Subscription plans ───────────────────────────────────────────────────

    @Test
    void plans_isPublic_andListsAllThreeTiers() throws Exception {
        mvc.perform(get("/api/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].key").value("FREE"))
                .andExpect(jsonPath("$[1].key").value("PRO"));
    }

    @Test
    void subscription_requiresLogin() throws Exception {
        mvc.perform(get("/api/v1/subscription")).andExpect(status().isUnauthorized());
    }

    @Test
    void subscription_defaultsToFree_andUpgradeUnlocksCustomAlias() throws Exception {
        String token = jwtService.generateToken(savedUser);
        Map<String, Object> body = Map.of("longUrl", "https://example.com", "customAlias", "pro-alias", "expiresInDays", 7);

        mvc.perform(get("/api/v1/subscription").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.key").value("FREE"));

        mvc.perform(post("/api/v1/urls").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_FEATURE_LOCKED"))
                .andExpect(jsonPath("$.requiredPlan").value("PRO"));

        mvc.perform(post("/api/v1/subscription").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.key").value("PRO"));

        mvc.perform(post("/api/v1/urls").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void csvExport_isLockedOnFree_andAvailableOnPro() throws Exception {
        String token = jwtService.generateToken(savedUser);

        mvc.perform(get("/api/v1/urls/my/export").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_FEATURE_LOCKED"));

        savedUser.setPlan(com.example.urlshortener.entity.SubscriptionPlan.PRO);
        userRepository.save(savedUser);

        mvc.perform(get("/api/v1/urls/my/export").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("my-dispatches.csv")));
    }

    // ── URL ownership ─────────────────────────────────────────────────────────

    @Test
    void shorten_asLoggedInUser_urlAppearsInMyUrls() throws Exception {
        String token = jwtService.generateToken(savedUser);

        // Create a short URL as this user
        Map<String, Object> body = Map.of("longUrl", "https://example.com/owned");
        mvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // It should appear in their "My Dispatches"
        mvc.perform(get("/api/v1/urls/my")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].longUrl").value("https://example.com/owned"));
    }

    @Test
    void myUrls_userOnlySeesOwnUrls() throws Exception {
        // Create a second user
        User other = userRepository.save(User.builder()
                .name("Bob").email("bob@example.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .createdAt(LocalDateTime.now())
                .build());

        String adaToken = jwtService.generateToken(savedUser);
        String bobToken = jwtService.generateToken(other);

        // Bob creates a URL
        Map<String, Object> body = Map.of("longUrl", "https://bob.example.com");
        mvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // Ada's "My Dispatches" must be empty — she can't see Bob's URL
        mvc.perform(get("/api/v1/urls/my")
                        .header("Authorization", "Bearer " + adaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void deleteUrl_ownerCanDelete() throws Exception {
        String token = jwtService.generateToken(savedUser);

        // Create a URL
        Map<String, Object> body = Map.of("longUrl", "https://example.com/to-delete");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String shortCode = (String) mapper.readValue(responseBody, Map.class).get("shortCode");

        // Owner deletes it
        mvc.perform(delete("/api/v1/urls/" + shortCode)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // It should be gone
        mvc.perform(get("/api/v1/urls/my")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void deleteUrl_nonOwner_returns403() throws Exception {
        // Ada creates a URL
        String adaToken = jwtService.generateToken(savedUser);
        Map<String, Object> body = Map.of("longUrl", "https://ada.example.com");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + adaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        String shortCode = (String) mapper.readValue(responseBody, Map.class).get("shortCode");

        // Bob tries to delete Ada's URL
        User bob = userRepository.save(User.builder()
                .name("Bob").email("bob@example.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .createdAt(LocalDateTime.now()).build());
        String bobToken = jwtService.generateToken(bob);

        mvc.perform(delete("/api/v1/urls/" + shortCode)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUrl_noToken_returns401() throws Exception {
        mvc.perform(delete("/api/v1/urls/someCode"))
                .andExpect(status().isUnauthorized());
    }

    // ── Public redirect ───────────────────────────────────────────────────────

    @Test
    void redirect_publicShortLink_worksWithNoToken() throws Exception {
        // Create a URL anonymously (no auth header)
        Map<String, Object> body = Map.of("longUrl", "https://example.com/public");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String shortCode = (String) mapper.readValue(responseBody, Map.class).get("shortCode");

        // Anyone can redirect — no token needed
        mvc.perform(get("/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/public"));
    }

    @Test
    void shorten_anonymously_urlNotInAnyUsersMyDispatches() throws Exception {
        // Anonymous shorten
        Map<String, Object> body = Map.of("longUrl", "https://example.com/anon");
        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // Ada's "My Dispatches" should still be empty
        String token = jwtService.generateToken(savedUser);
        mvc.perform(get("/api/v1/urls/my")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    // ── URL validation ────────────────────────────────────────────────────────

    @Test
    void shorten_blankUrl_returns400() throws Exception {
        Map<String, Object> body = Map.of("longUrl", "");
        mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shorten_invalidUrl_returns400() throws Exception {
        Map<String, Object> body = Map.of("longUrl", "not-a-url");
        mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shorten_duplicateCustomAlias_returns409() throws Exception {
        Map<String, Object> body = Map.of("longUrl", "https://example.com", "customAlias", "my-alias");

        mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void stats_clickCount_incrementsOnRedirect() throws Exception {
        Map<String, Object> body = Map.of("longUrl", "https://example.com/clickme");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> resp = mapper.readValue(responseBody, Map.class);
        String shortCode = (String) resp.get("shortCode");
        String managementKey = (String) resp.get("managementKey");

        // Hit the redirect twice
        mvc.perform(get("/" + shortCode));
        mvc.perform(get("/" + shortCode));

        mvc.perform(get("/api/v1/urls/" + shortCode + "/stats").header("X-Management-Key", managementKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(2));
    }

    // ── Abuse protection: report / disable / enable ─────────────────────────

    @Test
    void report_incrementsCount_andReturnsOk() throws Exception {
        Map<String, Object> body = Map.of("longUrl", "https://example.com/reportme");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        String shortCode = (String) mapper.readValue(responseBody, Map.class).get("shortCode");

        mvc.perform(post("/api/v1/urls/" + shortCode + "/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value(shortCode))
                .andExpect(jsonPath("$.reportCount").value(1))
                .andExpect(jsonPath("$.autoFlagged").value(false));
    }

    @Test
    void report_unknownCode_returns404() throws Exception {
        mvc.perform(post("/api/v1/urls/doesNotExist/report"))
                .andExpect(status().isNotFound());
    }

    @Test
    void report_withOptionalReasonBody_isAccepted() throws Exception {
        Map<String, Object> body = Map.of("longUrl", "https://example.com/reportme2");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        String shortCode = (String) mapper.readValue(responseBody, Map.class).get("shortCode");

        mvc.perform(post("/api/v1/urls/" + shortCode + "/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("reason", "Looks like a phishing page"))))
                .andExpect(status().isOk());
    }

    @Test
    void disable_ownerCanDisableOwnLink_andRedirectThenReturns403() throws Exception {
        String token = jwtService.generateToken(savedUser);

        Map<String, Object> body = Map.of("longUrl", "https://example.com/to-disable");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        String shortCode = (String) mapper.readValue(responseBody, Map.class).get("shortCode");

        mvc.perform(post("/api/v1/urls/" + shortCode + "/disable")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/" + shortCode))
                .andExpect(status().isForbidden());
    }

    @Test
    void disable_nonOwnerWithoutKey_returns403() throws Exception {
        String adaToken = jwtService.generateToken(savedUser);
        Map<String, Object> body = Map.of("longUrl", "https://example.com/ada-owns-this");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + adaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        String shortCode = (String) mapper.readValue(responseBody, Map.class).get("shortCode");

        User bob = userRepository.save(User.builder()
                .name("Bob").email("bob@example.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .createdAt(LocalDateTime.now()).build());
        String bobToken = jwtService.generateToken(bob);

        mvc.perform(post("/api/v1/urls/" + shortCode + "/disable")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void disable_anonymousLinkWithManagementKey_succeeds() throws Exception {
        Map<String, Object> body = Map.of("longUrl", "https://example.com/anon-disable");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> resp = mapper.readValue(responseBody, Map.class);
        String shortCode = (String) resp.get("shortCode");
        String managementKey = (String) resp.get("managementKey");

        mvc.perform(post("/api/v1/urls/" + shortCode + "/disable")
                        .header("X-Management-Key", managementKey))
                .andExpect(status().isNoContent());

        mvc.perform(get("/" + shortCode))
                .andExpect(status().isForbidden());
    }

    @Test
    void enable_ownerReEnablesDisabledLink_redirectWorksAgain() throws Exception {
        String token = jwtService.generateToken(savedUser);

        Map<String, Object> body = Map.of("longUrl", "https://example.com/to-re-enable");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        String shortCode = (String) mapper.readValue(responseBody, Map.class).get("shortCode");

        mvc.perform(post("/api/v1/urls/" + shortCode + "/disable")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/" + shortCode))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/urls/" + shortCode + "/enable")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/" + shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void report_reachingAutoDisableThreshold_blocksRedirect() throws Exception {
        // Default threshold is 3 (app.report.auto-disable-threshold) - not
        // overridden in test application.yml, so the @Value default applies.
        Map<String, Object> body = Map.of("longUrl", "https://example.com/mass-reported");
        String responseBody = mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        String shortCode = (String) mapper.readValue(responseBody, Map.class).get("shortCode");

        mvc.perform(post("/api/v1/urls/" + shortCode + "/report"));
        mvc.perform(post("/api/v1/urls/" + shortCode + "/report"));
        mvc.perform(post("/api/v1/urls/" + shortCode + "/report"))
                .andExpect(jsonPath("$.autoFlagged").value(true));

        mvc.perform(get("/" + shortCode))
                .andExpect(status().isForbidden());
    }
}
