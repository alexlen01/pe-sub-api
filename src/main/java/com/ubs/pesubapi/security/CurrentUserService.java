package com.ubs.pesubapi.security;

import com.ubs.pesubapi.dto.CurrentUserDto;
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
        if (auth.getPrincipal() instanceof UserIdentity user) {
            String fullName = fullName(user);
            return fullName.isBlank() ? user.uuName() : fullName;
        }
        String name = auth.getName();
        return (name == null || name.isBlank() || "anonymousUser".equals(name)) ? SYSTEM : name;
    }

    public String auditDisplayName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return SYSTEM;
        if (auth.getPrincipal() instanceof UserIdentity user) {
            String uuName = (user.uuName() == null || user.uuName().isBlank()) ? SYSTEM : user.uuName();
            String fullName = fullName(user);
            return fullName.isBlank() || SYSTEM.equals(uuName) ? uuName : fullName + " (" + uuName + ")";
        }
        String name = auth.getName();
        return (name == null || name.isBlank() || "anonymousUser".equals(name)) ? SYSTEM : name;
    }

    /**
     * Stable, unique authentication identity (UBS uuName) — the correct key for authorization
     * decisions, ownership, and maker-checker separation. Unlike {@link #displayName()} this is
     * never a human-readable name, so two people who share a name do not collide. Returns
     * {@code "system"} when no principal is bound.
     */
    public String uuName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return SYSTEM;
        if (auth.getPrincipal() instanceof UserIdentity user) {
            String uuName = user.uuName();
            return (uuName == null || uuName.isBlank()) ? SYSTEM : uuName;
        }
        String name = auth.getName();
        return (name == null || name.isBlank() || "anonymousUser".equals(name)) ? SYSTEM : name;
    }

    public CurrentUserDto currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return new CurrentUserDto(SYSTEM, "", "", "", "Viewer");
        UserIdentity identity = auth.getPrincipal() instanceof UserIdentity user
            ? user : new UserIdentity(auth.getName(), "", "", auth.getName());
        String role = auth.getAuthorities().stream().map(a -> a.getAuthority()).map(this::displayRole)
            .filter(r -> r != null).findFirst().orElse("Viewer");
        return new CurrentUserDto(identity.uuName(), identity.firstName(), identity.lastName(), identity.email(), role);
    }

    private String fullName(UserIdentity user) {
        return ((user.firstName() == null ? "" : user.firstName()) + " "
            + (user.lastName() == null ? "" : user.lastName())).trim();
    }

    private String displayRole(String authority) {
        return switch (authority) {
            case "ROLE_ANALYST" -> "Analyst";
            case "ROLE_MANAGER" -> "Account/Transaction Manager";
            case "ROLE_VIEWER" -> "Viewer";
            case "ROLE_SERVICE" -> "Service";
            default -> null;
        };
    }
}
