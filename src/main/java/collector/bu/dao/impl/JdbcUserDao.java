package collector.bu.dao.impl;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserAccount;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserDao implements UserDao {
    private final JdbcTemplate jdbc;

    public JdbcUserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query(
                "SELECT id, username, password_hash, enabled FROM app_user WHERE username = ?",
                (rs, row) -> new UserAccount(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash"), rs.getBoolean("enabled")),
                username).stream().findFirst();
    }

    @Override
    public void insert(String username, String passwordHash) {
        jdbc.update("INSERT INTO app_user (username, password_hash, enabled) VALUES (?, ?, ?)",
                username, passwordHash, true);
    }
}
