package com.ubs.pesubapi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Resolves the authenticated principal for audit attribution. Replaces the hardcoded operator
 * name that business code previously stamped onto every audit entry.
 */
@Service
public class CurrentUserService {

    private static final String SYSTEM = "system";

    /** Display name of the current user, or {@code "system"} when no principal is bound. */
    public String displayName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return SYSTEM;
        String name = auth.getName();
        return (name == null || name.isBlank() || "anonymousUser".equals(name)) ? SYSTEM : name;
    }
}
