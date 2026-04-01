package com.ga.pixgen.security;

import com.ga.pixgen.model.Permission;
import com.ga.pixgen.model.Role;
import com.ga.pixgen.model.User;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@EqualsAndHashCode(of = "user")
@RequiredArgsConstructor
public class CustomUserDetails implements UserDetails {

    private final User user;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        Role role = user.getRole();
        if (role == null) {
            return authorities;
        }
        if (role.getName() != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));
        }
        if (role.getPermissions() != null) {
            for (Permission permission : role.getPermissions()) {
                if (permission != null && permission.getPermission() != null) {
                    authorities.add(new SimpleGrantedAuthority(permission.getPermission()));
                }
            }
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return user.isEnabled();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.isEnabled();
    }
}
