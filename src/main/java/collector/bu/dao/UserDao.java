package collector.bu.dao;

import collector.bu.entity.UserAccount;
import collector.bu.entity.UserRole;
import java.util.List;
import java.util.Optional;

public interface UserDao {
    Optional<UserAccount> findByUsername(String username);

    default void insert(String username, String passwordHash) {
        insert(username, passwordHash, UserRole.USER);
    }

    void insert(String username, String passwordHash, UserRole role);

    Optional<UserAccount> findById(long id);

    List<UserAccount> findAll();

    List<UserAccount> lockEnabledAdmins();

    void updateRole(long id, UserRole role);
}
