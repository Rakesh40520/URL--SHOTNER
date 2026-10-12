package com.example.urlshortener.service;

import com.example.urlshortener.entity.EmailCode;
import com.example.urlshortener.entity.EmailCodePurpose;
import com.example.urlshortener.exception.CodeRateLimitException;
import com.example.urlshortener.exception.EmailDeliveryException;
import com.example.urlshortener.exception.InvalidCodeException;
import com.example.urlshortener.mail.EmailSender;
import com.example.urlshortener.repository.EmailCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailCodeServiceTest {

    private static final String EMAIL = "ada@example.com";
    private static final EmailCodePurpose VERIFY = EmailCodePurpose.VERIFY_EMAIL;

    @Mock private EmailCodeRepository codes;
    @Mock private EmailSender sender;

    private EmailCodeService service;

    @BeforeEach
    void setUp() {
        service = new EmailCodeService(codes, new BCryptPasswordEncoder(4), sender, 10);
    }

    /** Issues a code and returns what was stored plus the plain code that was emailed. */
    private record Issued(EmailCode row, String code) { }

    private Issued issueOne() {
        when(codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VERIFY)).thenReturn(Optional.empty());
        when(codes.countByEmailAndPurposeAndCreatedAtAfter(eq(EMAIL), eq(VERIFY), any())).thenReturn(0L);
        ArgumentCaptor<EmailCode> row = ArgumentCaptor.forClass(EmailCode.class);
        when(codes.save(row.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.issue(EMAIL, VERIFY, "Ada");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(EMAIL), anyString(), anyString(), text.capture());
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(text.getValue());
        assertTrue(m.find(), "email should contain a 6-digit code");
        return new Issued(row.getValue(), m.group(1));
    }

    @Test
    void issue_emailsASixDigitCode_andStoresOnlyItsHash() {
        Issued issued = issueOne();

        assertEquals(6, issued.code().length());
        assertNotEquals(issued.code(), issued.row().getCodeHash());
        assertTrue(new BCryptPasswordEncoder(4).matches(issued.code(), issued.row().getCodeHash()));
        assertEquals(0, issued.row().getAttempts());
        assertTrue(issued.row().getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(9)));
    }

    @Test
    void issue_withinCooldown_isRateLimited_andSendsNothing() {
        EmailCode recent = EmailCode.builder().email(EMAIL).purpose(VERIFY).codeHash("x")
                .createdAt(LocalDateTime.now().minusSeconds(10)).expiresAt(LocalDateTime.now().plusMinutes(9)).build();
        when(codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VERIFY)).thenReturn(Optional.of(recent));

        CodeRateLimitException ex = assertThrows(CodeRateLimitException.class, () -> service.issue(EMAIL, VERIFY, "Ada"));
        assertTrue(ex.getRetryAfterSeconds() > 0 && ex.getRetryAfterSeconds() <= 60);
        verifyNoInteractions(sender);
        verify(codes, never()).save(any());
    }

    @Test
    void issue_overTheHourlyCap_isRateLimited() {
        when(codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VERIFY)).thenReturn(Optional.empty());
        when(codes.countByEmailAndPurposeAndCreatedAtAfter(eq(EMAIL), eq(VERIFY), any())).thenReturn(5L);

        assertThrows(CodeRateLimitException.class, () -> service.issue(EMAIL, VERIFY, "Ada"));
        verifyNoInteractions(sender);
    }

    @Test
    void issue_whenTheProviderFails_theFailureReachesTheCaller() {
        when(codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VERIFY)).thenReturn(Optional.empty());
        when(codes.countByEmailAndPurposeAndCreatedAtAfter(eq(EMAIL), eq(VERIFY), any())).thenReturn(0L);
        when(codes.save(any(EmailCode.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new EmailDeliveryException("HTTP 401", null)).when(sender).send(anyString(), anyString(), anyString(), anyString());

        assertThrows(EmailDeliveryException.class, () -> service.issue(EMAIL, VERIFY, "Ada"));
    }

    @Test
    void verify_correctCode_consumesIt() {
        Issued issued = issueOne();
        when(codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VERIFY)).thenReturn(Optional.of(issued.row()));

        assertDoesNotThrow(() -> service.verify(EMAIL, VERIFY, issued.code()));
        verify(codes).deleteAllFor(EMAIL, VERIFY);
    }

    @Test
    void verify_wrongCode_countsTheAttempt_andReportsHowManyAreLeft() {
        Issued issued = issueOne();
        when(codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VERIFY)).thenReturn(Optional.of(issued.row()));
        String wrong = issued.code().equals("000000") ? "111111" : "000000";

        InvalidCodeException ex = assertThrows(InvalidCodeException.class, () -> service.verify(EMAIL, VERIFY, wrong));

        assertEquals(4, ex.getAttemptsLeft());
        assertEquals(1, issued.row().getAttempts());
        verify(codes, atLeastOnce()).save(issued.row());
        verify(codes, never()).deleteAllFor(anyString(), any());
    }

    @Test
    void verify_afterFiveWrongGuesses_evenTheRightCodeIsRefused() {
        Issued issued = issueOne();
        issued.row().setAttempts(5);
        when(codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VERIFY)).thenReturn(Optional.of(issued.row()));

        assertThrows(InvalidCodeException.class, () -> service.verify(EMAIL, VERIFY, issued.code()));
    }

    @Test
    void verify_expiredCode_isRefused() {
        Issued issued = issueOne();
        issued.row().setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VERIFY)).thenReturn(Optional.of(issued.row()));

        assertThrows(InvalidCodeException.class, () -> service.verify(EMAIL, VERIFY, issued.code()));
    }

    @Test
    void verify_noCodeOnFile_isRefused() {
        when(codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VERIFY)).thenReturn(Optional.empty());
        assertThrows(InvalidCodeException.class, () -> service.verify(EMAIL, VERIFY, "123456"));
    }
}
