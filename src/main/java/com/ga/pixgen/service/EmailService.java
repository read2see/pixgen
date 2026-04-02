package com.ga.pixgen.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    @Value("${app.mail.from}")
    private String from;

    @Value("${app.mail.base-url}")
    private String baseUrl;

    public void sendVerificationEmail(String to, UUID tokenId) {
        String verifyUrl = baseUrl + "/api/auth/verify-email?token=" + tokenId;
        Context context = new Context();
        context.setVariable("verifyUrl", verifyUrl);
        context.setVariable("recipient", to);
        String html = templateEngine.process("emails/verify-email", context);
        sendHtml(to, "Verify your Pixgen email", html);
    }

    public void sendPasswordResetEmail(String to, UUID tokenId) {
        String resetUrl = baseUrl + "/api/auth/reset-password?token=" + tokenId;
        Context context = new Context();
        context.setVariable("resetUrl", resetUrl);
        context.setVariable("recipient", to);
        String html = templateEngine.process("emails/reset-password", context);
        sendHtml(to, "Reset your Pixgen password", html);
    }

    private void sendHtml(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException | MailException ex) {
            log.error("Failed to send email to {}: {}", to, ex.getMessage(), ex);
        }
    }
}
