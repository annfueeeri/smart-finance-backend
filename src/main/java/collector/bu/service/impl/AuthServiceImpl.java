package collector.bu.service.impl;

import collector.bu.service.AuthService;
import collector.bu.service.RegistrationException;
import collector.bu.dao.UserDao;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {
    private final AuthenticationManager authenticationManager;
    private final UserDao users;
    private final PasswordEncoder encoder;

    public AuthServiceImpl(AuthenticationManager authenticationManager, UserDao users, PasswordEncoder encoder) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void register(String username, String password, String confirmPassword) {
        if (username == null || !username.matches("\\S+") || username.length() > 64
                || password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !password.equals(confirmPassword)) {
            throw new RegistrationException(RegistrationException.Reason.INVALID_REQUEST);
        }
        try {
            // The database's unique constraint also handles concurrent registrations safely.
            users.insert(username, encoder.encode(password));
        } catch (DuplicateKeyException exception) {
            throw new RegistrationException(RegistrationException.Reason.USERNAME_TAKEN);
        }
    }

    @Override
    public Authentication login(String username, String password) {
        // BCrypt only accepts up to 72 bytes; never silently truncate a credential.
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password));
    }

    @Override
    public String currentUsername(Authentication authentication) {
        return authentication.getName();
    }
}
