package com.ga.pixgen.security;

import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private CustomUserDetailsService userDetailsService;

    @Mock
    private FilterChain filterChain;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtService, userDetailsService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_doesNothing_whenNoCookiePresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_doesNothing_whenPixgenTokenCookieMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other_cookie", "value"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_setsAuthentication_whenValidPixgenTokenCookiePresent() throws Exception {
        String token = "valid.jwt.token";
        String email = "alice@example.com";
        User user = sampleUser(email);
        CustomUserDetails details = new CustomUserDetails(user);

        when(jwtService.extractSubject(token)).thenReturn(email);
        when(userDetailsService.loadUserByUsername(email)).thenReturn(details);
        when(jwtService.isTokenValid(token, email)).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("pixgen_token", token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(details);
        assertThat(auth.isAuthenticated()).isTrue();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_doesNotAuthenticate_whenTokenIsInvalid() throws Exception {
        String token = "invalid.jwt.token";
        String email = "alice@example.com";
        User user = sampleUser(email);
        CustomUserDetails details = new CustomUserDetails(user);

        when(jwtService.extractSubject(token)).thenReturn(email);
        when(userDetailsService.loadUserByUsername(email)).thenReturn(details);
        when(jwtService.isTokenValid(token, email)).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("pixgen_token", token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_swallowsParseException_andContinuesChain() throws Exception {
        String token = "tampered.jwt.token";
        when(jwtService.extractSubject(token)).thenThrow(new RuntimeException("bad signature"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("pixgen_token", token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(filterChain).doFilter(request, response);
    }

    private static User sampleUser(String email) {
        Role role = new Role();
        role.setId(1L);
        role.setName("USER");
        User user = new User();
        user.setId(7L);
        user.setEmail(email);
        user.setPassword("ENC");
        user.setEnabled(true);
        user.setVerified(true);
        user.setRole(role);
        return user;
    }
}
