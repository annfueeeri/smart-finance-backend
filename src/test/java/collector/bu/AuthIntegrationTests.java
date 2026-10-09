package collector.bu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import collector.bu.dao.UserDao;
import collector.bu.service.impl.LocalAccountInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTests {
    private static final String PASSWORD = "Test-only-password-123";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired UserDao userDao;

    @BeforeEach
    void prepareAccounts() {
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'registration-%'");
        jdbc.update("DELETE FROM app_user WHERE username IN (?, ?)", "login-test", "disabled-test");
        String hash = encoder.encode(PASSWORD);
        jdbc.update("INSERT INTO app_user (username, password_hash, enabled) VALUES (?, ?, ?)",
                "login-test", hash, true);
        jdbc.update("INSERT INTO app_user (username, password_hash, enabled) VALUES (?, ?, ?)",
                "disabled-test", hash, false);
    }

    @Test
    void registrationCreatesHashedAccountThatCanLogInWithoutAutomaticallySigningIn() {
        var browser = new Browser();
        browser.refreshCsrf();
        var result = browser.register("registration-new", PASSWORD, PASSWORD);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().get("username").asText()).isEqualTo("registration-new");
        assertThat(result.getBody().size()).isEqualTo(2);
        assertThat(result.getBody().get("role").asText()).isEqualTo("USER");
        var account = userDao.findByUsername("registration-new").orElseThrow();
        assertThat(account.enabled()).isTrue();
        assertThat(account.passwordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, account.passwordHash())).isTrue();
        assertThat(browser.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(browser.login("registration-new", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browser.get("/api/auth/me").getBody().get("username").asText())
                .isEqualTo("registration-new");
    }

    @Test
    void duplicateRegistrationDoesNotChangeExistingOrDisabledAccounts() {
        var browser = new Browser();
        browser.refreshCsrf();
        for (String username : List.of("login-test", "disabled-test")) {
            var original = userDao.findByUsername(username).orElseThrow();
            var result = browser.register(username, "Different-password-456", "Different-password-456");
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(result.getBody().get("code").asText()).isEqualTo("USERNAME_TAKEN");
            assertThat(userDao.findByUsername(username).orElseThrow()).isEqualTo(original);
        }
    }

    @Test
    void registrationRejectsInvalidPayloadsAndNeverCreatesAnAccount() {
        var browser = new Browser();
        browser.refreshCsrf();
        for (var body : List.of(
                Map.of("username", "registration-invalid", "password", PASSWORD),
                Map.of("username", "", "password", PASSWORD, "confirmPassword", PASSWORD),
                Map.of("username", "invalid user", "password", PASSWORD, "confirmPassword", PASSWORD),
                Map.of("username", "x".repeat(65), "password", PASSWORD, "confirmPassword", PASSWORD),
                Map.of("username", "registration-invalid", "password", "short", "confirmPassword", "short"),
                Map.of("username", "registration-invalid", "password", "        ", "confirmPassword", "        "),
                Map.of("username", "registration-invalid", "password", PASSWORD, "confirmPassword", "Mismatch-password"),
                Map.of("username", "registration-invalid", "password", "密".repeat(25), "confirmPassword", "密".repeat(25)),
                Map.of("username", "registration-invalid", "password", "x".repeat(73), "confirmPassword", "x".repeat(73)))) {
            var result = browser.post("/api/auth/register", body);
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(browser.post("/api/auth/register", "{broken").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userDao.findByUsername("registration-invalid")).isEmpty();
    }

    @Test
    void registrationRequiresCsrfTokenFromTheSameSession() {
        assertThat(new Browser().register("registration-csrf", PASSWORD, PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var one = new Browser();
        one.refreshCsrf();
        var two = new Browser();
        two.refreshCsrf();
        two.csrf = one.csrf;
        assertThat(two.register("registration-csrf", PASSWORD, PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userDao.findByUsername("registration-csrf")).isEmpty();
    }

    @Test
    void registrationAcceptsPasswordsAtTheUtf8ByteLimit() {
        var browser = new Browser();
        browser.refreshCsrf();
        String password = "密".repeat(24);
        assertThat(browser.register("registration-unicode", password, password).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(browser.login("registration-unicode", password).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void simultaneousRegistrationCreatesExactlyOneAccount() throws Exception {
        var one = new Browser();
        one.refreshCsrf();
        var two = new Browser();
        two.refreshCsrf();
        var first = CompletableFuture.supplyAsync(() -> one.register("registration-race", PASSWORD, PASSWORD));
        var second = CompletableFuture.supplyAsync(() -> two.register("registration-race", PASSWORD, PASSWORD));
        assertThat(List.of(first.get(15, TimeUnit.SECONDS).getStatusCode(),
                second.get(15, TimeUnit.SECONDS).getStatusCode()))
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class,
                "registration-race")).isEqualTo(1);
    }

    @Test
    void loginRotatesSessionAndAuthenticatesOnlyThatBrowser() {
        var browser = new Browser();
        browser.refreshCsrf();
        String originalCookie = browser.cookie;
        var result = browser.login("login-test", PASSWORD);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().get("username").asText()).isEqualTo("login-test");
        assertThat(result.getBody().size()).isEqualTo(2);
        assertThat(result.getBody().get("role").asText()).isEqualTo("USER");
        assertThat(result.getHeaders().get(HttpHeaders.SET_COOKIE).toString())
                .contains("HttpOnly", "SameSite=Strict");
        assertThat(browser.cookie).isNotEqualTo(originalCookie);
        assertThat(browser.get("/api/auth/me").getBody().get("username").asText())
                .isEqualTo("login-test");
        assertThat(new Browser().get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var oldSession = new Browser();
        oldSession.cookie = originalCookie;
        assertThat(oldSession.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void badPasswordUnknownAccountAndDisabledAccountHaveTheSameResponse() {
        var wrong = new Browser();
        wrong.refreshCsrf();
        var badPassword = wrong.login("login-test", "incorrect");
        var unknown = new Browser();
        unknown.refreshCsrf();
        var missingAccount = unknown.login("missing-test", PASSWORD);
        var disabled = new Browser();
        disabled.refreshCsrf();
        var disabledAccount = disabled.login("disabled-test", PASSWORD);
        for (var result : List.of(badPassword, missingAccount, disabledAccount)) {
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(result.getBody()).isEqualTo(badPassword.getBody());
            assertThat(result.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        }
        assertThat(wrong.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginRequiresCsrfTokenFromTheSameSession() {
        assertThat(new Browser().login("login-test", PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var one = new Browser();
        one.refreshCsrf();
        var two = new Browser();
        two.refreshCsrf();
        two.csrf = one.csrf;
        assertThat(two.login("login-test", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidCredentialsPayloadReturnsBadRequest() {
        var browser = new Browser();
        browser.refreshCsrf();
        for (var body : List.of(Map.of("username", "login-test"),
                Map.of("username", "", "password", PASSWORD),
                Map.of("username", "   ", "password", PASSWORD),
                Map.of("username", "x".repeat(65), "password", PASSWORD),
                Map.of("username", "login-test", "password", "x".repeat(73)))) {
            var result = browser.post("/api/auth/login", body);
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(browser.post("/api/auth/login", "{broken").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void oversizedUtf8PasswordAndSqlInjectionCannotAuthenticate() {
        var browser = new Browser();
        browser.refreshCsrf();
        assertThat(browser.login("login-test", "密".repeat(25)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(browser.login("'/**/OR/**/1=1--", PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void localBootstrapStoresOnlyHashAndNeverOverwritesExistingAccounts() {
        String username = "bootstrap-test";
        jdbc.update("DELETE FROM app_user WHERE username = ?", username);
        try {
            new LocalAccountInitializer(userDao, encoder, username, PASSWORD).run(null);
            var account = userDao.findByUsername(username).orElseThrow();
            assertThat(account.passwordHash()).isNotEqualTo(PASSWORD);
            assertThat(encoder.matches(PASSWORD, account.passwordHash())).isTrue();
            new LocalAccountInitializer(userDao, encoder, username, "Different-password-456").run(null);
            assertThat(userDao.findByUsername(username).orElseThrow().passwordHash())
                    .isEqualTo(account.passwordHash());
        } finally {
            jdbc.update("DELETE FROM app_user WHERE username = ?", username);
        }
    }

    @Test
    void localBootstrapRejectsIncompleteAndOversizedCredentials() {
        for (var initializer : List.of(
                new LocalAccountInitializer(userDao, encoder, "", PASSWORD),
                new LocalAccountInitializer(userDao, encoder, "bootstrap-test", ""),
                new LocalAccountInitializer(userDao, encoder, "bootstrap-test", "short"),
                new LocalAccountInitializer(userDao, encoder, "bootstrap-test", "密".repeat(25)))) {
            assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void logoutRequiresFreshCsrfAndInvalidatesTheSession() {
        var browser = new Browser();
        browser.refreshCsrf();
        assertThat(browser.login("login-test", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Login clears the pre-login token as well as rotating the session id.
        assertThat(browser.post("/api/auth/logout", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        browser.refreshCsrf();
        assertThat(browser.post("/api/auth/logout", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(browser.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void onlyPublicRoutesAreAvailableWithoutAuthentication() {
        var browser = new Browser();
        assertThat(browser.get("/api/health").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browser.get("/v3/api-docs").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(browser.get("/api/private").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private class Browser {
        private String cookie;
        private String csrf;
        private String csrfHeader = "X-CSRF-TOKEN";

        void refreshCsrf() {
            var result = get("/api/auth/csrf");
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            csrf = result.getBody().get("token").asText();
            csrfHeader = result.getBody().get("headerName").asText();
        }

        ResponseEntity<JsonNode> login(String username, String password) {
            return post("/api/auth/login", Map.of("username", username, "password", password));
        }

        ResponseEntity<JsonNode> register(String username, String password, String confirmPassword) {
            return post("/api/auth/register", Map.of("username", username,
                    "password", password, "confirmPassword", confirmPassword));
        }

        ResponseEntity<JsonNode> get(String path) {
            return exchange(path, HttpMethod.GET, null);
        }

        ResponseEntity<JsonNode> post(String path, Object body) {
            return exchange(path, HttpMethod.POST, body);
        }

        private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body) {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
            if (cookie != null) headers.set(HttpHeaders.COOKIE, cookie);
            if (csrf != null && method == HttpMethod.POST) headers.set(csrfHeader, csrf);
            var result = rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
            var cookies = result.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (cookies != null) {
                cookies.stream().filter(value -> value.startsWith("JSESSIONID="))
                        .forEach(value -> cookie = value.split(";", 2)[0]);
            }
            return result;
        }
    }
}
