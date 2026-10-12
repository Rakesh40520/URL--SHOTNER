package com.example.urlshortener.mail;

/** Sends one transactional email. Implementations are chosen by app.mail.provider. */
public interface EmailSender {

    /**
     * @throws com.example.urlshortener.exception.EmailDeliveryException if the provider
     *         rejects the message or can't be reached
     */
    void send(String toEmail, String subject, String htmlBody, String textBody);
}
