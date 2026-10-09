package collector.bu.entity;

public record UserAccount(long id, String username, String passwordHash, boolean enabled, UserRole role) {
}
