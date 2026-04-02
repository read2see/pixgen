package com.ga.pixgen.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET =
            "test-only-secret-not-used-in-prod-test-only-secret-not-used-in-prod";
    private static final long ONE_HOUR_MS = 60L * 60L * 1000L;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, ONE_HOUR_MS);
    }

    @Test
    void generateToken_returnsThreePartCompactJwt() {
        String token = jwtService.generateToken("alice@example.com");

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void extractSubject_returnsTheSubjectThatWasEncoded() {
        String token = jwtService.generateToken("alice@example.com");

        String subject = jwtService.extractSubject(token);

        assertThat(subject).isEqualTo("alice@example.com");
    }

    @Test
    void extractSubject_throwsExpiredJwtException_whenTokenIsExpired() throws Exception {
        JwtService shortLived = new JwtService(SECRET, 1L);
        String token = shortLived.generateToken("alice@example.com");
        Thread.sleep(50L);

        assertThatThrownBy(() -> shortLived.extractSubject(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void isTokenValid_returnsFalse_whenTokenIsExpired() throws Exception {
        JwtService shortLived = new JwtService(SECRET, 1L);
        String token = shortLived.generateToken("alice@example.com");
        Thread.sleep(50L);

        assertThat(shortLived.isTokenValid(token, "alice@example.com")).isFalse();
    }

    @Test
    void isTokenValid_returnsTrue_whenSubjectMatchesAndNotExpired() {
        String token = jwtService.generateToken("alice@example.com");

        assertThat(jwtService.isTokenValid(token, "alice@example.com")).isTrue();
    }

    @Test
    void isTokenValid_returnsFalse_whenSubjectDiffers() {
        String token = jwtService.generateToken("alice@example.com");

        assertThat(jwtService.isTokenValid(token, "someone-else@example.com")).isFalse();
    }

    @Test
    void extractSubject_throwsSignatureException_whenTokenIsTampered() {
        String token = jwtService.generateToken("alice@example.com");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + flipMiddleChar(parts[2]);

        assertThatThrownBy(() -> jwtService.extractSubject(tampered))
                .isInstanceOf(SignatureException.class);
    }

    private static String flipMiddleChar(String s) {
        int idx = s.length() / 2;
        char current = s.charAt(idx);
        char swapped = (current == 'A') ? 'B' : 'A';
        return s.substring(0, idx) + swapped + s.substring(idx + 1);
    }
}
