package com.openpay.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;

/**
 * Sends a best-effort notification email.
 *
 * <p>Two things here are deliberate, the same way {@code AuditRecorder} is deliberate about never
 * breaking the thing it records.
 *
 * <p><strong>Never on the caller's thread.</strong> {@link Async} hands the send to a small
 * dedicated pool, so a slow or unreachable SMTP relay adds no latency to a login, a token refresh,
 * or a webhook dispatch cycle — none of which should ever wait on a mail server.
 *
 * <p><strong>Never propagates a failure.</strong> A bounced send is logged at WARN and swallowed.
 * The alternative — a security alert that cannot be delivered somehow blocking the security
 * response it was reporting on — would be worse than a notification nobody got.
 */
public class EmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailNotifier(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Async("emailNotifierExecutor")
    public void sendBestEffort(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Sent '{}' to {}", subject, to);
        } catch (MailException exception) {
            log.warn("Could not send '{}' to {}: {}", subject, to, exception.getMessage());
        }
    }
}
