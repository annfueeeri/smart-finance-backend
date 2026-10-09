package collector.bu.service;

import org.springframework.security.core.Authentication;

public interface AuthService {
    /**
     * 校验用户名、密码和确认密码，创建启用的一般用户账号，不自动登录。
     * 用户名为 1–64 个非空白字符；密码至少 8 个字符且不超过 72 个 UTF-8 字节。
     * @param username 新账号用户名
     * @param password 待哈希保存的明文密码
     * @param confirmPassword 必须与密码完全一致的确认密码
     * @throws collector.bu.service.RegistrationException 参数不合法或用户名已存在
     */
    void register(String username, String password, String confirmPassword);

    /**
     * 使用数据库中的账号信息和 BCrypt 密码哈希进行认证，拒绝超过 72 字节的密码。
     * 此方法只返回认证结果，会话登录状态由 Controller 保存。
     * @param username 要登录的用户名
     * @param password 本次认证使用的明文密码
     * @return 认证成功后的用户名和权限信息
     * @throws org.springframework.security.core.AuthenticationException 账号不存在、密码错误或账号状态不允许登录
     */
    Authentication login(String username, String password);

    /**
     * 从已认证的登录信息中取得用户名，不额外查询数据库。
     * @param authentication 调用方提供的有效认证信息
     * @return 当前登录用户名
     */
    String currentUsername(Authentication authentication);
}
