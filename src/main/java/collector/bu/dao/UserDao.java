package collector.bu.dao;

import collector.bu.entity.UserAccount;
import java.util.Optional;

public interface UserDao {
    Optional<UserAccount> findByUsername(String username);

    void insert(String username, String passwordHash);
}
