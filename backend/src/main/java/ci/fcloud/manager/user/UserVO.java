package ci.fcloud.manager.user;

import lombok.Data;

/**
 * 成员选择器 / 当前用户视图。
 */
@Data
public class UserVO {

    private Long id;
    private String name;
    private String nickname;
    private String email;
    private String avatarUrl;
    private String globalRole;
    private String gitlabId;
    private java.time.Instant lastLoginAt;
    private Integer memberCount;

    public static UserVO of(User u) {
        UserVO v = new UserVO();
        v.setId(u.getId());
        v.setName(u.getName());
        v.setNickname(u.getNickname());
        v.setEmail(u.getEmail());
        v.setAvatarUrl(u.getAvatarUrl());
        v.setGlobalRole(u.getGlobalRole());
        return v;
    }
}
