package collector.bu;

import static org.assertj.core.api.Assertions.assertThat;

import collector.bu.dao.UserDao;
import collector.bu.entity.UserRole;
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
class RolesIntegrationTests {
    private static final String PASSWORD = "Role-test-password-123";
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserDao users;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void accounts() {
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'role-test-%'");
        users.insert("role-test-admin", encoder.encode(PASSWORD), UserRole.ADMIN);
        users.insert("role-test-user", encoder.encode(PASSWORD));
    }

    private long id(String username) { return users.findByUsername(username).orElseThrow().id(); }

    @Test
    void administratorCanListAndChangeRolesWithoutExposingPasswords() {
        var admin = signedIn("role-test-admin");
        var result = admin.get("/api/admin/users");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (var user : result.getBody()) {
            assertThat(user.size()).isEqualTo(4);
            assertThat(user.has("id") && user.has("username") && user.has("role") && user.has("enabled"))
                    .isTrue();
        }
        var changed = admin.put(id("role-test-user"), "ADMIN");
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().get("role").asText()).isEqualTo("ADMIN");
        assertThat(users.findByUsername("role-test-user").orElseThrow().role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void unauthenticatedAndRegularUsersCannotReadOrChangeRoles() {
        assertThat(new Browser().get("/api/admin/users").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var anonymous = new Browser();
        anonymous.csrf();
        assertThat(anonymous.put(id("role-test-user"), "ADMIN").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var user = signedIn("role-test-user");
        assertThat(user.get("/api/admin/users").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(user.put(id("role-test-user"), "ADMIN").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(users.findByUsername("role-test-user").orElseThrow().role()).isEqualTo(UserRole.USER);
    }

    @Test
    void roleChangesAffectExistingSessionsOnTheNextRequest() {
        var user = signedIn("role-test-user");
        var admin = signedIn("role-test-admin");
        assertThat(admin.put(id("role-test-user"), "ADMIN").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(user.get("/api/auth/me").getBody().get("role").asText()).isEqualTo("ADMIN");
        assertThat(user.get("/api/admin/users").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin.put(id("role-test-user"), "USER").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(user.get("/api/auth/me").getBody().get("role").asText()).isEqualTo("USER");
        assertThat(user.get("/api/admin/users").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(user.put(id("role-test-user"), "ADMIN").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void roleUpdatesRequireValidCsrfAndValidateInput() {
        var admin = signedIn("role-test-admin");
        admin.token = null;
        assertThat(admin.put(id("role-test-user"), "ADMIN").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        admin.csrf();
        for (var body : List.of(Map.of("role", "SUPERUSER"), Map.of("other", "ADMIN"))) {
            assertThat(admin.exchange("/api/admin/users/" + id("role-test-user") + "/role", HttpMethod.PUT, body)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(admin.put(Long.MAX_VALUE, "ADMIN").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(users.findByUsername("role-test-user").orElseThrow().role()).isEqualTo(UserRole.USER);
    }

    @Test
    void registrationCannotGrantAdministratorPrivileges() {
        var browser = new Browser();
        browser.csrf();
        var result = browser.exchange("/api/auth/register", HttpMethod.POST, Map.of(
                "username", "role-test-signup", "password", PASSWORD, "confirmPassword", PASSWORD,
                "role", "ADMIN"));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().get("role").asText()).isEqualTo("USER");
        assertThat(users.findByUsername("role-test-signup").orElseThrow().role()).isEqualTo(UserRole.USER);
    }

    @Test
    void lastEnabledAdministratorCannotBeDemoted() {
        var admin = signedIn("role-test-admin");
        var result = admin.put(id("role-test-admin"), "USER");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(result.getBody().get("code").asText()).isEqualTo("LAST_ADMIN");
        assertThat(admin.get("/api/auth/me").getBody().get("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void concurrentDemotionsLeaveAnEnabledAdministrator() throws Exception {
        users.insert("role-test-second", encoder.encode(PASSWORD), UserRole.ADMIN);
        var one = signedIn("role-test-admin");
        var two = signedIn("role-test-second");
        var first = CompletableFuture.supplyAsync(() -> one.put(id("role-test-admin"), "USER"));
        var second = CompletableFuture.supplyAsync(() -> two.put(id("role-test-second"), "USER"));
        assertThat(List.of(first.get(15, TimeUnit.SECONDS).getStatusCode(),
                second.get(15, TimeUnit.SECONDS).getStatusCode()))
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void disabledAccountsLoseTheirExistingSessions() {
        var user = signedIn("role-test-user");
        jdbc.update("UPDATE app_user SET enabled=FALSE WHERE username=?", "role-test-user");
        assertThat(user.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void localAdministratorBootstrapIsExplicitAndNeverOverwritesAnExistingRole() {
        new LocalAccountInitializer(users, encoder, "role-test-bootstrap", PASSWORD, UserRole.ADMIN).run(null);
        assertThat(users.findByUsername("role-test-bootstrap").orElseThrow().role()).isEqualTo(UserRole.ADMIN);
        new LocalAccountInitializer(users, encoder, "role-test-user", PASSWORD, UserRole.ADMIN).run(null);
        assertThat(users.findByUsername("role-test-user").orElseThrow().role()).isEqualTo(UserRole.USER);
        jdbc.update("DELETE FROM app_user WHERE username=?", "role-test-bootstrap");
    }

    private Browser signedIn(String username) {
        var browser = new Browser();
        browser.csrf();
        var response = browser.exchange("/api/auth/login", HttpMethod.POST,
                Map.of("username", username, "password", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("role").asText()).isEqualTo(
                users.findByUsername(username).orElseThrow().role().name());
        browser.csrf();
        return browser;
    }

    private class Browser {
        String cookie;
        String token;
        void csrf() { token = get("/api/auth/csrf").getBody().get("token").asText(); }
        ResponseEntity<JsonNode> get(String path) { return exchange(path, HttpMethod.GET, null); }
        ResponseEntity<JsonNode> put(long id, String role) {
            return exchange("/api/admin/users/" + id + "/role", HttpMethod.PUT, Map.of("role", role));
        }
        ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body) {
            var headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (cookie != null) headers.set(HttpHeaders.COOKIE, cookie);
            if (token != null && method != HttpMethod.GET) headers.set("X-CSRF-TOKEN", token);
            var result = rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
            var cookies = result.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (cookies != null) cookies.stream().filter(value -> value.startsWith("JSESSIONID="))
                    .forEach(value -> cookie = value.split(";", 2)[0]);
            return result;
        }
    }
}
