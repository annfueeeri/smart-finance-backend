package collector.bu.controller;

import collector.bu.controller.api.UsersApi;
import collector.bu.controller.model.Role;
import collector.bu.controller.model.RoleUpdateRequest;
import collector.bu.controller.model.UserSummary;
import collector.bu.entity.UserAccount;
import collector.bu.entity.UserRole;
import collector.bu.service.UserManagementService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class UserManagementController implements UsersApi {
    private final UserManagementService users;

    /**
     * 注入用户管理业务组件，供列表查询和身份修改接口调用。
     * @param users 用户管理业务服务
     */
    public UserManagementController(UserManagementService users) { this.users = users; }

    /**
     * 将数据库账号转换为可公开的用户摘要，过滤掉密码哈希。
     * @param account 业务层返回的内部账号数据
     * @return 用户基本信息及创建、修改审计信息，不含密码哈希
     */
    private UserSummary summary(UserAccount account) {
        return new UserSummary(account.id(), account.username(), Role.fromValue(account.role().name()),
                account.enabled(), account.createdAt().toString(), account.createdBy(),
                account.updatedAt().toString(), account.updatedBy(), account.deleted());
    }

    /**
     * 处理 GET /api/users，管理员查看全部未删除用户，一般用户只查看自己的信息。
     * 查询范围由业务层从数据库身份决定，禁止依赖前端过滤隐藏其他用户。
     * @return HTTP 200，返回当前账号可见的用户信息和审计字段
     */
    @Override
    @GetMapping("/api/users")
    public ResponseEntity<List<UserSummary>> listVisibleUsers() {
        return ResponseEntity.ok(users.listVisibleUsers(SecurityContextHolder.getContext().getAuthentication().getName())
                .stream().map(this::summary).toList());
    }

    /**
     * 处理 GET /api/admin/users，以当前登录用户名查询全部账号。
     * 安全过滤器和业务层均检查管理员权限，响应中不包含密码哈希。
     * @return HTTP 200，返回用户摘要列表
     */
    @Override
    @GetMapping("/api/admin/users")
    public ResponseEntity<List<UserSummary>> listUsers() {
        return ResponseEntity.ok(users.listUsers(SecurityContextHolder.getContext().getAuthentication().getName())
                .stream().map(this::summary).toList());
    }

    /**
     * 处理 PUT /api/admin/users/{id}/role，将指定账号的身份写入数据库。
     * 业务层检查操作者权限并保护最后一个启用的管理员；目标账号下次请求使用新身份。
     * @param id 路径中的目标用户 ID
     * @param csrfToken 当前会话的 CSRF 请求头值，由安全过滤器预先校验
     * @param body 新身份，仅允许 ADMIN 或 USER
     * @return HTTP 200，返回修改后的用户摘要
     */
    @Override
    @PutMapping(value = "/api/admin/users/{id}/role", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserSummary> updateUserRole(Long id, String csrfToken, RoleUpdateRequest body) {
        return ResponseEntity.ok(summary(users.updateRole(
                SecurityContextHolder.getContext().getAuthentication().getName(), id,
                UserRole.valueOf(body.getRole().getValue()))));
    }
}
