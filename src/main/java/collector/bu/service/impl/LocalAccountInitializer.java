package collector.bu.service.impl;

import collector.bu.dao.UserDao;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Optional local bootstrap; no built-in password and no existing account is overwritten. */
@Service
@Profile("local")
public class LocalAccountInitializer implements ApplicationRunner {
    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public LocalAccountInitializer(UserDao userDao, PasswordEncoder passwordEncoder,
            @Value("${LOGIN_BOOTSTRAP_USERNAME:}") String username,
            @Value("${LOGIN_BOOTSTRAP_PASSWORD:}") String password) {
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isEmpty() && password.isEmpty()) {
            return;
        }
        if (!username.matches("\\S+") || username.length() > 64 || password.isBlank()
                || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Local bootstrap requires a username (1-64 characters)"
                    + " and password (at least 8 characters, at most 72 UTF-8 bytes)");
        }
        if (userDao.findByUsername(username).isEmpty()) {
            userDao.insert(username, passwordEncoder.encode(password));
        }
    }
}
