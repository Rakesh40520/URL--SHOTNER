package com.example.urlshortener.service;

import com.example.urlshortener.entity.EmailCode;
import com.example.urlshortener.entity.EmailCodePurpose;
import com.example.urlshortener.exception.CodeRateLimitException;
import com.example.urlshortener.exception.EmailDeliveryException;
import com.example.urlshortener.exception.InvalidCodeException;
import com.example.urlshortener.mail.EmailSender;
import com.example.urlshortener.mail.EmailTemplates;
import com.example.urlshortener.repository.EmailCodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Issues and checks the 6-digit codes emailed for "verify your email" and "forgot
 * password". Rules:
 *  - a code lives 10 minutes (app.auth.code-ttl-minutes) and works once;
 *  - 5 wrong guesses burn it, so 1,000,000 possibilities can't be brute-forced;
 *  - at most one email per 60 seconds and 5 per hour for each address and purpose;
 *  - only a bcrypt hash of the code is stored;
 *  - the newest code replaces any earlier one.
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    static final int MAX_ATTEMPTS = 5;
    static final long COOLDOWN_SECONDS = 60;
    static final int MAX_PER_HOUR = 5;

    private final EmailCodeRepository codes;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final int ttlMinutes;

    public EmailCodeService(EmailCodeRepository codes, PasswordEncoder passwordEncoder, EmailSender emailSender,
                            @Value("${app.auth.code-ttl-minutes:10}") int ttlMinutes) {
        this.codes = codes;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.ttlMinutes = ttlMinutes;
    }

    /**
     * Creates a fresh code and emails it. If the email can't be sent the whole
     * operation rolls back, so a failed send doesn't use up the user's cooldown.
     */
    @Transactional
    public void issue(String email, EmailCodePurpose purpose, String recipientName) {
        LocalDateTime now = LocalDateTime.now();

        Optional<EmailCode> latest = codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);
        if (latest.isPresent()) {
            long since = Duration.between(latest.get().getCreatedAt(), now).getSeconds();
            if (since < COOLDOWN_SECONDS) throw new CodeRateLimitException(COOLDOWN_SECONDS - since);
        }
        if (codes.countByEmailAndPurposeAndCreatedAtAfter(email, purpose, now.minusHours(1)) >= MAX_PER_HOUR) {
            throw new CodeRateLimitException(Duration.ofMinutes(10).getSeconds());
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        codes.save(EmailCode.builder()
                .email(email)
                .purpose(purpose)
                .codeHash(passwordEncoder.encode(code))
                .createdAt(now)
                .expiresAt(now.plusMinutes(ttlMinutes))
                .attempts(0)
                .build());

        EmailTemplates.Message m = EmailTemplates.codeEmail(purpose, recipientName, code, ttlMinutes);
        try {
            emailSender.send(email, m.subject(), m.html(), m.text());
        } catch (EmailDeliveryException ex) {
            log.error("Could not send {} email to {}: {}", purpose, email, ex.getDetail());
            throw ex;
        }
    }

    /**
     * Checks a submitted code. On success the code is consumed. On failure an
     * InvalidCodeException is thrown AFTER the wrong-attempt count is saved
     * (hence noRollbackFor - otherwise the exception would undo the count).
     */
    @Transactional(noRollbackFor = InvalidCodeException.class)
    public void verify(String email, EmailCodePurpose purpose, String submitted) {
        EmailCode latest = codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(InvalidCodeException::expiredOrUnknown);

        if (latest.getExpiresAt().isBefore(LocalDateTime.now()) || latest.getAttempts() >= MAX_ATTEMPTS) {
            throw InvalidCodeException.expiredOrUnknown();
        }

        if (submitted == null || !passwordEncoder.matches(submitted.trim(), latest.getCodeHash())) {
            latest.setAttempts(latest.getAttempts() + 1);
            codes.save(latest);
            int left = MAX_ATTEMPTS - latest.getAttempts();
            throw left > 0
                    ? new InvalidCodeException("Incorrect code. " + left + (left == 1 ? " attempt" : " attempts") + " left.", left)
                    : InvalidCodeException.expiredOrUnknown();
        }

        codes.deleteAllFor(email, purpose);   // used up - can't be replayed
    }

    /** Housekeeping: keeps the table small. Codes older than a day are useless anyway. */
    @Scheduled(initialDelay = 300_000, fixedDelay = 3_600_000)
    @Transactional
    public void purgeOld() {
        codes.deleteOlderThan(LocalDateTime.now().minusDays(1));
    }
}
