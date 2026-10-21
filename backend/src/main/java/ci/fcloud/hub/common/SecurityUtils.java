package ci.fcloud.hub.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 取当前登录用户。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser cu) {
            return cu;
        }
        return null;
    }

    public static Long currentUserId() {
        CurrentUser u = currentUser();
        return u == null ? null : u.getUid();
    }

    public static String currentUserEmail() {
        CurrentUser u = currentUser();
        return u == null ? null : u.getEmail();
    }

    public static boolean isSystemAdmin() {
        CurrentUser u = currentUser();
        return u != null && u.isSystemAdmin();
    }

    public static boolean isAuthenticated() {
        return currentUser() != null;
    }
}
