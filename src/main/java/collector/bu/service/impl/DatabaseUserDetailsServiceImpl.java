package collector.bu.service.impl;

import collector.bu.dao.UserDao;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import collector.bu.service.DatabaseUserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserDetailsServiceImpl implements DatabaseUserDetailsService {
    private final UserDao userDao;

    /**
     * 注入账号查询组件，为 Spring Security 的数据库认证提供账号数据。
     * @param userDao 账号数据库访问组件
     */
    public DatabaseUserDetailsServiceImpl(UserDao userDao) {
        this.userDao = userDao;
    }

    /**
     * 按用户名查询数据库，将密码哈希、角色及启用状态转换为 Spring Security 账号。
     * 被禁用或逻辑删除的账号均标记为不可登录。
     * 密码是否正确由认证提供者使用 BCrypt 校验，此方法只负责加载信息。
     * @param username 需要认证的用户名
     * @return 包含密码哈希、ROLE_ADMIN/ROLE_USER 权限及禁用状态的账号信息
     * @throws org.springframework.security.core.userdetails.UsernameNotFoundException 数据库中不存在该用户名
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        var account = userDao.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        return User.withUsername(account.username()).password(account.passwordHash())
                .roles(account.role().name()).disabled(!account.enabled() || account.deleted()).build();
    }
}
