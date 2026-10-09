package collector.bu.service;

import collector.bu.entity.UserAccount;
import collector.bu.entity.UserRole;
import java.util.List;

public interface UserManagementService {
    List<UserAccount> listUsers(String actor);
    UserAccount updateRole(String actor, long id, UserRole role);
}
