package com.example.urlshortener.mail;

import com.example.urlshortener.exception.EmailDeliveryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends through Brevo's HTTPS API (free tier: 300 emails/day, and you only need to
 * verify ONE sender address - no domain required). Uses HTTPS rather than SMTP on
 * purpose: Render's free web services block outbound SMTP ports.
 *
 * Setup: create a Brevo account, add and verify your sender email under Senders,
 * create an API key, then set MAIL_PROVIDER=brevo, BREVO_API_KEY and MAIL_FROM_EMAIL
 * (the verified sender).
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "brevo")
public class BrevoEmailSender implements EmailSender {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey, fromEmail, fromName;

    public BrevoEmailSender(@Value("${app.mail.brevo-api-key:}") String apiKey,
                            @Value("${app.mail.from-email}") String fromEmail,
                            @Value("${app.mail.from-name:Dispatch}") String fromName) {
        // Don't crash the whole app on a missing key (e.g. a fresh deploy before the secret
        // is filled in): registration will report "couldn't send email" until it's set.
        if (apiKey == null || apiKey.isBlank()) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("BREVO_API_KEY is not set - verification and reset emails cannot be sent.");
        }
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public void send(String toEmail, String subject, String htmlBody, String textBody) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmailDeliveryException("BREVO_API_KEY is not set", null);
        }
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "sender", Map.of("name", fromName, "email", fromEmail),
                    "to", List.of(Map.of("email", toEmail)),
                    "subject", subject,
                    "htmlContent", htmlBody,
                    "textContent", textBody));

            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
                    .timeout(Duration.ofSeconds(10))
                    .header("api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() / 100 != 2) {
                String body = res.body() == null ? "" : res.body();
                throw new EmailDeliveryException("Brevo HTTP " + res.statusCode() + ": "
                        + body.substring(0, Math.min(body.length(), 300)), null);
            }
        } catch (EmailDeliveryException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmailDeliveryException("interrupted", e);
        } catch (Exception e) {
            throw new EmailDeliveryException(e.toString(), e);
        }
    }
}
