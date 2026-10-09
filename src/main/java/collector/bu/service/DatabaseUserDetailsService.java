package collector.bu.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

/** 数据库认证用户加载接口，继承Spring Security契约，实现类位于service/impl。 */
public interface DatabaseUserDetailsService extends UserDetailsService {
    /**
     * 按用户名查询数据库，将密码哈希、角色及启用状态转换为 Spring Security 账号。
     * 被禁用或逻辑删除的账号均标记为不可登录。
     * 密码是否正确由认证提供者使用 BCrypt 校验，此方法只负责加载信息。
     * @param username 需要认证的用户名
     * @return 包含密码哈希、ROLE_ADMIN/ROLE_USER 权限及禁用状态的账号信息
     * @throws org.springframework.security.core.userdetails.UsernameNotFoundException 数据库中不存在该用户名
     */
    @Override
    UserDetails loadUserByUsername(String username);
}
