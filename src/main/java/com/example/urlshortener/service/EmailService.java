package com.example.urlshortener.service;

import com.example.urlshortener.entity.OtpPurpose;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailService {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${app.mail.from:Dispatch <noreply@dispatch.io>}")
    private String fromAddress;

    @Async
    public void sendOtpEmail(String toEmail, String otp, OtpPurpose purpose, int validityMinutes) {
        String subject = purpose == OtpPurpose.PASSWORD_RESET
                ? "Your Password Reset Code — Dispatch"
                : "Verify Your Email Address — Dispatch";

        String title = purpose == OtpPurpose.PASSWORD_RESET
                ? "Password Reset Request"
                : "Email Verification";

        String description = purpose == OtpPurpose.PASSWORD_RESET
                ? "We received a request to reset your Dispatch account password. Use the verification code below to complete your reset:"
                : "Thank you for creating an account with Dispatch. Please enter the verification code below to confirm your email:";

        String htmlContent = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f7f5ef; color: #211d18; margin: 0; padding: 24px; }
                        .card { max-width: 480px; margin: 0 auto; background: #ffffff; border: 1.5px solid #211d18; border-radius: 4px; box-shadow: 4px 4px 0 rgba(33,29,24,0.12); padding: 32px 28px; }
                        .header { font-family: monospace; font-size: 13px; letter-spacing: 0.14em; color: #b23a2e; text-transform: uppercase; margin-bottom: 8px; font-weight: 600; }
                        h1 { font-size: 22px; margin: 0 0 16px; font-weight: 700; color: #211d18; }
                        p { font-size: 14px; line-height: 1.55; color: #575249; margin: 0 0 20px; }
                        .otp-box { background: #fbf8f0; border: 1.5px dashed #211d18; border-radius: 4px; text-align: center; padding: 18px; margin: 24px 0; }
                        .otp-code { font-family: monospace; font-size: 32px; letter-spacing: 0.28em; font-weight: 700; color: #211d18; margin: 0; }
                        .expiry { font-size: 12px; color: #8a8275; margin-top: 8px; }
                        .footer { margin-top: 28px; padding-top: 18px; border-top: 1px solid #e8e2d5; font-size: 12px; color: #8a8275; line-height: 1.5; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="header">DISPATCH SECURITY</div>
                        <h1>%s</h1>
                        <p>%s</p>
                        <div class="otp-box">
                            <div class="otp-code">%s</div>
                            <div class="expiry">Expires in %d minutes</div>
                        </div>
                        <p>If you did not request this code, you can safely ignore this email. No changes will be made to your account.</p>
                        <div class="footer">
                            Sent securely by Dispatch URL Shortener.<br>
                            For security reasons, never share this code with anyone.
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(title, description, otp, validityMinutes);

        // Always log OTP in server logs for convenient local testing / staging
        log.info("[OTP NOTIFICATION] Email: {} | Purpose: {} | OTP Code: {} (Expires in {} mins)",
                toEmail, purpose, otp, validityMinutes);

        if (mailSender == null || mailUsername == null || mailUsername.isBlank()) {
            log.info("SMTP username is not configured. Running in DEV MODE: email delivery simulated.");
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Successfully sent OTP email to {}", toEmail);
        } catch (Exception ex) {
            log.error("Failed to send email via SMTP to {}: {}. Note: Check your SMTP credentials in application.yml.",
                    toEmail, ex.getMessage());
        }
    }
}
