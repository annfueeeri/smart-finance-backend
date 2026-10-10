package collector.bu.model;

import collector.bu.entity.UserRole;

/** 登录或注册响应，仅公开账号和身份。 */
public record UserResponse(String username, UserRole role) { }
