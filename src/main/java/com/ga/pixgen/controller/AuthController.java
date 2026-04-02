package com.ga.pixgen.controller;

import com.ga.pixgen.dto.LoginRequest;
import com.ga.pixgen.dto.RegisterRequest;
import com.ga.pixgen.dto.SendVerificationRequest;
import com.ga.pixgen.dto.UserResponse;
import com.ga.pixgen.exception.ExpiredTokenException;
import com.ga.pixgen.exception.InvalidTokenException;
import com.ga.pixgen.model.User;
import com.ga.pixgen.security.CookieFactory;
import com.ga.pixgen.security.CustomUserDetails;
import com.ga.pixgen.security.JwtService;
import com.ga.pixgen.service.AuthService;
import com.ga.pixgen.service.EmailVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final CookieFactory cookieFactory;
    private final EmailVerificationService emailVerificationService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@RequestBody RegisterRequest request) {
        User created = authService.register(request);
        emailVerificationService.issueToken(created.getEmail());
        return UserResponse.fromEntity(created);
    }

    @PostMapping("/send-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void sendVerification(@RequestBody SendVerificationRequest request) {
        emailVerificationService.issueToken(request.email());
    }

    @GetMapping("/verify-email")
    public Map<String, Boolean> verifyEmail(@RequestParam("token") UUID token) {
        emailVerificationService.verify(token);
        return Map.of("verified", true);
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
        String token = jwtService.generateToken(principal.getUsername());
        ResponseCookie cookie = cookieFactory.createTokenCookie(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(UserResponse.fromEntity(principal.getUser()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie cleared = cookieFactory.clearTokenCookie();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal CustomUserDetails principal) {
        return UserResponse.fromEntity(principal.getUser());
    }

    /**
     * Until the global exception handler ships in Phase 1 / Day 5, translate
     * failed AuthenticationManager attempts into 401 at the controller boundary
     * so /api/auth/login does not bleed a 500 to the client.
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public void handleAuthenticationException() {
    }

    @ExceptionHandler(InvalidTokenException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void handleInvalidToken() {
    }

    @ExceptionHandler(ExpiredTokenException.class)
    @ResponseStatus(HttpStatus.GONE)
    public void handleExpiredToken() {
    }
}
