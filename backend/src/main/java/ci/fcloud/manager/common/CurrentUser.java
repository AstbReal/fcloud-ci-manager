package ci.fcloud.manager.common;

import ci.fcloud.manager.user.GlobalRole;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 已登录用户主体。uid/email/name/role 直接来自 JWT claims（§6.2）。
 */
@Getter
public class CurrentUser implements UserDetails {

    private final Long uid;
    private final String email;
    private final String name;
    private final GlobalRole role;
    private final String nickname;
    private final String avatarUrl;

    public CurrentUser(Long uid, String email, String name, GlobalRole role,
                       String nickname, String avatarUrl) {
        this.uid = uid;
        this.email = email;
        this.name = name;
        this.role = role;
        this.nickname = nickname;
        this.avatarUrl = avatarUrl;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public boolean isSystemAdmin() {
        return role == GlobalRole.SYSTEM_ADMIN;
    }
}
