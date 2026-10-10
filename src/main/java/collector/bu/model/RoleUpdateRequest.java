package collector.bu.model;

import collector.bu.entity.UserRole;
import jakarta.validation.constraints.NotNull;

/** 管理员修改用户身份的请求，身份必须为 ADMIN 或 USER。 */
public record RoleUpdateRequest(@NotNull UserRole role) { }
