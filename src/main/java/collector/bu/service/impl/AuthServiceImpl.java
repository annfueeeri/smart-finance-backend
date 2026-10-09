package collector.bu.service.impl;

import collector.bu.service.AuthService;
import collector.bu.service.RegistrationException;
import collector.bu.dao.UserDao;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {
    private final AuthenticationManager authenticationManager;
    private final UserDao users;
    private final PasswordEncoder encoder;

    /**
     * 注入数据库认证、账号存储和密码哈希组件。
     * @param authenticationManager 用户名密码认证管理器
     * @param users 账号数据库访问组件
     * @param encoder 密码哈希生成及校验组件
     */
    public AuthServiceImpl(AuthenticationManager authenticationManager, UserDao users, PasswordEncoder encoder) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.encoder = encoder;
    }

    /**
     * 校验用户名、密码和确认密码，创建启用的一般用户账号，不自动登录。
     * 用户名为 1–64 个非空白字符；密码至少 8 个字符且不超过 72 个 UTF-8 字节。
     * 保存 BCrypt 哈希，并将唯一键冲突转为用户名重复异常。
     * @param username 新账号用户名
     * @param password 待哈希保存的明文密码
     * @param confirmPassword 必须与密码完全一致的确认密码
     * @throws collector.bu.service.RegistrationException 参数不合法或用户名已存在
     */
    @Override
    @Transactional
    public void register(String username, String password, String confirmPassword) {
        if (username == null || !username.matches("\\S+") || username.length() > 64
                || password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !password.equals(confirmPassword)) {
            throw new RegistrationException(RegistrationException.Reason.INVALID_REQUEST);
        }
        try {
            // 数据库唯一约束也能拒绝并发请求创建同名账号。
            users.insert(username, encoder.encode(password));
        } catch (DuplicateKeyException exception) {
            throw new RegistrationException(RegistrationException.Reason.USERNAME_TAKEN);
        }
    }

    /**
     * 使用数据库中的账号信息和 BCrypt 密码哈希进行认证，拒绝超过 72 字节的密码。
     * 此方法只返回认证结果，会话登录状态由 Controller 保存。
     * @param username 要登录的用户名
     * @param password 本次认证使用的明文密码
     * @return 认证成功后的用户名和权限信息
     * @throws org.springframework.security.core.AuthenticationException 账号不存在、密码错误或账号状态不允许登录
     */
    @Override
    public Authentication login(String username, String password) {
        // BCrypt 最多接受 72 字节；超限直接拒绝，避免静默截断密码。
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password));
    }

    /**
     * 从已认证的登录信息中取得用户名，不额外查询数据库。
     * @param authentication 调用方提供的有效认证信息
     * @return 当前登录用户名
     */
    @Override
    public String currentUsername(Authentication authentication) {
        return authentication.getName();
    }
}
