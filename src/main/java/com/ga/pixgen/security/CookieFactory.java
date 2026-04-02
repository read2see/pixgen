package com.ga.pixgen.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CookieFactory {

    public static final String TOKEN_COOKIE_NAME = "pixgen_token";
    private static final String SAME_SITE = "Lax";
    private static final String PATH = "/";

    private final long expirationMs;
    private final boolean secure;

    public CookieFactory(
            @Value("${jwt.expiration-ms}") long expirationMs,
            @Value("${app.cookie.secure:false}") boolean secure) {
        this.expirationMs = expirationMs;
        this.secure = secure;
    }

    public ResponseCookie createTokenCookie(String token) {
        return ResponseCookie.from(TOKEN_COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE)
                .path(PATH)
                .maxAge(Duration.ofMillis(expirationMs))
                .build();
    }

    public ResponseCookie clearTokenCookie() {
        return ResponseCookie.from(TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(SAME_SITE)
                .path(PATH)
                .maxAge(0)
                .build();
    }
}
