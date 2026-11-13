package com.hospital.planner.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}

    public static String username() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || !a.isAuthenticated() ? "system" : a.getName();
    }

    public static String role() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return "NONE";
        return a.getAuthorities().stream().map(g -> g.getAuthority().replace("ROLE_", "")).findFirst().orElse("NONE");
    }

    public static boolean is(String role) {
        return role().equals(role);
    }

    public static boolean isAdmin() {
        return is("ADMIN");
    }

    /** True when the signed-in user is `owner`, or an ADMIN. */
    public static boolean ownerOrAdmin(String owner) {
        return isAdmin() || username().equals(owner);
    }
}
