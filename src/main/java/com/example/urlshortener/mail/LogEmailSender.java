package com.example.urlshortener.mail;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development fallback used when no real provider is configured: instead of sending
 * anything, prints the email (including the code) to the server log. Fine on a laptop;
 * on a deployed server anyone with log access could read users' codes, so set
 * MAIL_PROVIDER=brevo or resend for real use.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "log", matchIfMissing = true)
public class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @PostConstruct
    void warn() {
        log.warn("app.mail.provider=log: emails are NOT sent, they are printed to this log. "
                + "Set MAIL_PROVIDER=brevo (or resend) plus an API key for real delivery.");
    }

    @Override
    public void send(String toEmail, String subject, String htmlBody, String textBody) {
        log.info("[DEV EMAIL] to={} subject=\"{}\"\n{}", toEmail, subject, textBody);
    }
}
