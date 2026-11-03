package ci.fcloud.manager.user;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * 用户：首次登录自动建号；用户表为空时第一个登录者自动获得 SYSTEM_ADMIN（§6.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService extends ServiceImpl<UserMapper, User> {

    /**
     * 按 GitLab 用户信息查找或创建用户。
     *
     * @param gitlabId GitLab id
     * @param email    邮箱；为空时调用方已回退为 {username}@{host}
     * @param name     姓名
     * @param username 用作 nickname
     * @param avatar   头像
     */
    public User findOrCreate(String gitlabId, String email, String name,
                             String username, String avatar) {
        Optional<User> existing = lambdaQuery().eq(User::getEmail, email).oneOpt();
        if (existing.isPresent()) {
            User u = existing.get();
            u.setGitlabId(gitlabId);
            u.setName(name);
            u.setNickname(username != null && !username.isBlank() ? username : u.getNickname());
            u.setAvatarUrl(avatar != null ? avatar : u.getAvatarUrl());
            u.setLastLoginAt(Instant.now());
            updateById(u);
            return u;
        }

        // 空库自举：第一个登录者自动成为系统管理员
        boolean emptyTable = baseMapper.selectCount(null) == 0;
        User u = new User();
        u.setEmail(email);
        u.setName(name);
        u.setNickname(username);
        u.setAvatarUrl(avatar);
        u.setGitlabId(gitlabId);
        u.setGlobalRole(emptyTable ? GlobalRole.SYSTEM_ADMIN.name() : GlobalRole.NONE.name());
        u.setLastLoginAt(Instant.now());
        save(u);
        if (emptyTable) {
            log.info("bootstrap: first user {} granted SYSTEM_ADMIN", email);
        }
        return u;
    }

    public void touchLogin(Long userId) {
        User u = getById(userId);
        if (u != null) {
            u.setLastLoginAt(Instant.now());
            updateById(u);
        }
    }

    public void updateNickname(Long userId, String nickname) {
        User u = getById(userId);
        if (u == null) {
            throw new IllegalArgumentException("user not found");
        }
        u.setNickname(nickname);
        updateById(u);
    }

    public void setGlobalRole(Long userId, GlobalRole role) {
        User u = getById(userId);
        if (u == null) {
            throw new IllegalArgumentException("user not found");
        }
        u.setGlobalRole(role.name());
        updateById(u);
    }

    public User requireById(Long id) {
        User u = getById(id);
        if (u == null) {
            throw new IllegalArgumentException("user not found");
        }
        return u;
    }
}
