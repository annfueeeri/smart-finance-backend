package collector.bu.service;

import org.springframework.security.core.Authentication;

public interface AuthService {
    void register(String username, String password, String confirmPassword);

    Authentication login(String username, String password);

    String currentUsername(Authentication authentication);
}
