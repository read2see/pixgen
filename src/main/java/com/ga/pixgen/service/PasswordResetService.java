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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final TokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.token.password-reset-ttl-minutes}")
    private long ttlMinutes;

    /**
     * Silently no-ops for unknown emails so the endpoint cannot be used to
     * enumerate registered accounts.
     *
     * @param email the email value
     */
    @Transactional
    public void requestReset(String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return;
        }

        Token token = new Token();
        token.setId(UUID.randomUUID());
        token.setEmail(email);
        token.setType(TokenType.PASSWORD_RESET);
        Instant now = Instant.now();
        token.setCreatedAt(now);
        token.setExpiresAt(now.plus(ttlMinutes, ChronoUnit.MINUTES));
        token.setUsed(false);

        Token saved = tokenRepository.save(token);
        emailService.sendPasswordResetEmail(email, saved.getId());
    }

    @Transactional
    public void resetPassword(UUID tokenId, String newPassword) {
        Token token = tokenRepository.findById(tokenId)
                .orElseThrow(() -> new InvalidTokenException("Token not found"));

        if (token.getType() != TokenType.PASSWORD_RESET) {
            throw new InvalidTokenException("Token is not a password-reset token");
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

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}
