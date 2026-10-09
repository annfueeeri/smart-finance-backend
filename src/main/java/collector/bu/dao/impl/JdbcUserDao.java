package collector.bu.dao.impl;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserAccount;
import collector.bu.entity.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserDao implements UserDao {
    private final JdbcTemplate jdbc;
    private static final RowMapper<UserAccount> ROW = (rs, row) -> new UserAccount(
            rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
            rs.getBoolean("enabled"), UserRole.valueOf(rs.getString("role")));

    public JdbcUserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query(
                "SELECT id, username, password_hash, enabled, role FROM app_user WHERE username = ?", ROW,
                username).stream().findFirst();
    }

    @Override
    public void insert(String username, String passwordHash, UserRole role) {
        jdbc.update("INSERT INTO app_user (username, password_hash, enabled, role) VALUES (?, ?, ?, ?)",
                username, passwordHash, true, role.name());
    }

    @Override
    public Optional<UserAccount> findById(long id) {
        return jdbc.query("SELECT id, username, password_hash, enabled, role FROM app_user WHERE id = ?",
                ROW, id).stream().findFirst();
    }

    @Override
    public List<UserAccount> findAll() {
        return jdbc.query("SELECT id, username, password_hash, enabled, role FROM app_user ORDER BY id", ROW);
    }

    @Override
    public List<UserAccount> lockEnabledAdmins() {
        return jdbc.query("SELECT id, username, password_hash, enabled, role FROM app_user "
                + "WHERE role = 'ADMIN' AND enabled = TRUE ORDER BY id FOR UPDATE", ROW);
    }

    @Override
    public void updateRole(long id, UserRole role) {
        jdbc.update("UPDATE app_user SET role = ? WHERE id = ?", role.name(), id);
    }
}
