package collector.bu.service.impl;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserAccount;
import collector.bu.entity.UserRole;
import collector.bu.service.UserManagementException;
import collector.bu.service.UserManagementService;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementServiceImpl implements UserManagementService {
    private final UserDao users;

    public UserManagementServiceImpl(UserDao users) { this.users = users; }

    private void requireAdmin(String actor) {
        var account = users.findByUsername(actor);
        if (account.isEmpty() || !account.get().enabled() || account.get().role() != UserRole.ADMIN) {
            throw new AccessDeniedException("Administrator required");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> listUsers(String actor) {
        requireAdmin(actor);
        return users.findAll();
    }

    @Override
    @Transactional
    public UserAccount updateRole(String actor, long id, UserRole role) {
        // Serialize role changes so concurrent requests cannot remove every enabled admin.
        var admins = users.lockEnabledAdmins();
        requireAdmin(actor);
        var target = users.findById(id).orElseThrow(() ->
                new UserManagementException(UserManagementException.Reason.USER_NOT_FOUND));
        if (target.role() == UserRole.ADMIN && target.enabled() && role == UserRole.USER && admins.size() <= 1) {
            throw new UserManagementException(UserManagementException.Reason.LAST_ADMIN);
        }
        users.updateRole(id, role);
        return new UserAccount(target.id(), target.username(), target.passwordHash(), target.enabled(), role);
    }
}
