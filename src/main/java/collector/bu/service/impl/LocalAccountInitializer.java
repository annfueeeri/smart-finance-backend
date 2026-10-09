package collector.bu.service.impl;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 可选的本地账号初始化器，没有内置密码，也不会覆盖已有账号。 */
@Service
@Profile("local")
public class LocalAccountInitializer implements ApplicationRunner {
    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final UserRole role;

    /**
     * 创建默认 USER 身份的本地初始化器，委托给可指定身份的构造方法。
     * @param userDao 账号数据库访问组件
     * @param passwordEncoder 密码哈希生成组件
     * @param username 要初始化的用户名
     * @param password 要初始化的明文密码
     */
    public LocalAccountInitializer(UserDao userDao, PasswordEncoder passwordEncoder,
            String username, String password) {
        this(userDao, passwordEncoder, username, password, UserRole.USER);
    }

    /**
     * 从本地启动配置注入可选初始账号信息，缺省身份为 USER。
     * 仅在启用 local 配置环境时由 Spring 创建；只有显式指定 ADMIN 才创建管理员。
     * @param userDao 账号数据库访问组件
     * @param passwordEncoder 密码哈希生成组件
     * @param username LOGIN_BOOTSTRAP_USERNAME 配置，默认空字符串
     * @param password LOGIN_BOOTSTRAP_PASSWORD 配置，默认空字符串
     * @param role LOGIN_BOOTSTRAP_ROLE 配置，默认 USER
     */
    @Autowired
    public LocalAccountInitializer(UserDao userDao, PasswordEncoder passwordEncoder,
            @Value("${LOGIN_BOOTSTRAP_USERNAME:}") String username,
            @Value("${LOGIN_BOOTSTRAP_PASSWORD:}") String password,
            @Value("${LOGIN_BOOTSTRAP_ROLE:USER}") UserRole role) {
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.role = role;
    }

    /**
     * 应用启动后按可选配置创建本地初始账号，仅保存密码哈希。
     * 未配置用户名和密码时跳过；已有账号的密码和身份都不会被覆盖。
     * @param args Spring Boot 启动参数，本方法不使用
     * @throws IllegalArgumentException 只配置了部分凭据或凭据不满足长度要求
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isEmpty() && password.isEmpty()) {
            return;
        }
        if (!username.matches("\\S+") || username.length() > 64 || password.isBlank()
                || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Local bootstrap requires a username (1-64 characters)"
                    + " and password (at least 8 characters, at most 72 UTF-8 bytes)");
        }
        if (userDao.findByUsername(username).isEmpty()) {
            userDao.insert(username, passwordEncoder.encode(password), role, "SYSTEM");
        }
    }
}
