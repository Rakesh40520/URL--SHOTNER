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
 * Sends through Resend's HTTPS API. Note: until you verify your own domain at Resend,
 * it only delivers to your own account email, so Brevo is easier for a demo with
 * real users. Set MAIL_PROVIDER=resend, RESEND_API_KEY and MAIL_FROM_EMAIL.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "resend")
public class ResendEmailSender implements EmailSender {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey, from, fromEmail;

    public ResendEmailSender(@Value("${app.mail.resend-api-key:}") String apiKey,
                             @Value("${app.mail.from-email}") String fromEmail,
                             @Value("${app.mail.from-name:Dispatch}") String fromName) {
        // Don't crash the whole app on a missing key (e.g. a fresh deploy before the secret
        // is filled in): registration will report "couldn't send email" until it's set.
        if (apiKey == null || apiKey.isBlank()) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("RESEND_API_KEY is not set - verification and reset emails cannot be sent.");
        }
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
        this.from = fromName + " <" + fromEmail + ">";
    }

    @Override
    public void send(String toEmail, String subject, String htmlBody, String textBody) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmailDeliveryException("RESEND_API_KEY is not configured in environment variables.", null);
        }
        if (fromEmail == null || fromEmail.isBlank() || "no-reply@example.com".equalsIgnoreCase(fromEmail.trim())) {
            throw new EmailDeliveryException("MAIL_FROM_EMAIL must be set to your verified sender or onboarding@resend.dev.", null);
        }
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "from", from, "to", List.of(toEmail), "subject", subject, "html", htmlBody, "text", textBody));

            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() / 100 != 2) {
                String body = res.body() == null ? "" : res.body();
                throw new EmailDeliveryException("Resend HTTP " + res.statusCode() + ": "
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
