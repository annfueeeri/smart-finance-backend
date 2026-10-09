package collector.bu.controller;

import collector.bu.controller.api.UsersApi;
import collector.bu.controller.model.Role;
import collector.bu.controller.model.RoleUpdateRequest;
import collector.bu.controller.model.UserSummary;
import collector.bu.entity.UserAccount;
import collector.bu.entity.UserRole;
import collector.bu.service.UserManagementService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserManagementController implements UsersApi {
    private final UserManagementService users;

    public UserManagementController(UserManagementService users) { this.users = users; }

    private UserSummary summary(UserAccount account) {
        return new UserSummary(account.id(), account.username(), Role.fromValue(account.role().name()),
                account.enabled());
    }

    @Override
    public ResponseEntity<List<UserSummary>> listUsers() {
        return ResponseEntity.ok(users.listUsers(SecurityContextHolder.getContext().getAuthentication().getName())
                .stream().map(this::summary).toList());
    }

    @Override
    public ResponseEntity<UserSummary> updateUserRole(Long id, String csrfToken, RoleUpdateRequest body) {
        return ResponseEntity.ok(summary(users.updateRole(
                SecurityContextHolder.getContext().getAuthentication().getName(), id,
                UserRole.valueOf(body.getRole().getValue()))));
    }
}
