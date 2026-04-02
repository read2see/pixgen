package com.ga.pixgen.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('user.read')")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of("name", authentication.getName());
    }
}
