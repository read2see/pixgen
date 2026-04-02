package com.ga.pixgen.service;

import com.ga.pixgen.exception.ExpiredTokenException;
import com.ga.pixgen.exception.InvalidTokenException;
import com.ga.pixgen.model.Token;
import com.ga.pixgen.model.TokenType;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.TokenRepository;
import com.ga.pixgen.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final TokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.token.email-verification-ttl-minutes}")
    private long ttlMinutes;

    @Transactional
    public Token issueToken(String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.isVerified()) {
            return null;
        }

        Token token = new Token();
        token.setId(UUID.randomUUID());
        token.setEmail(email);
        token.setType(TokenType.EMAIL_VERIFICATION);
        Instant now = Instant.now();
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(ttlMinutes, ChronoUnit.MINUTES));
        token.setUsed(false);

        Token saved = tokenRepository.save(token);
        emailService.sendVerificationEmail(email, saved.getId());
        return saved;
    }

    @Transactional
    public void verify(UUID tokenId) {
        Token token = tokenRepository.findById(tokenId)
                .orElseThrow(() -> new InvalidTokenException("Token not found"));

        if (token.getType() != TokenType.EMAIL_VERIFICATION) {
            throw new InvalidTokenException("Token is not an email-verification token");
        }
        if (token.isUsed()) {
            throw new InvalidTokenException("Token has already been used");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new ExpiredTokenException("Token has expired");
        }

        User user = userRepository.findByEmail(token.getEmail())
                .orElseThrow(() -> new InvalidTokenException("Token references unknown user"));

        token.setUsed(true);
        tokenRepository.save(token);

        user.setVerified(true);
        userRepository.save(user);
    }
}
